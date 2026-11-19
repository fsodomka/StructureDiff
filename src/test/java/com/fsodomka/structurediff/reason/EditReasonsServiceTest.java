package com.fsodomka.structurediff.reason;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.reason.EditReasonMembers.MemberReason;
import com.fsodomka.structurediff.review.ReviewsDirs;
import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.intellij.icons.AllIcons;
import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.EditorGutterComponentEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.diff.Diff;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class EditReasonsServiceTest extends BasePlatformTestCase {
	private static final String TEXT = """
			<?xml version="1.0"?>
			<root>
			  <a>
			    <b>x</b>
			  </a>
			  <c/>
			</root>
			""";
	private static final String EDITS = StructureDiffSettings.DEFAULT_REVIEWS_DIR + "/" + EditReasonsService.EDITS_DIR + "/";

	private EditReasonsService service;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		service = EditReasonsService.getInstance(getProject());
		// the service keeps the snapshot the previous test pushed, which would give its files to this one's anchors
		service.snapshotChanged(List.of());
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			waitForLoad();
			service.dispose();
		} finally {
			super.tearDown();
		}
	}

	private static String record(Instant time, String toolUseId, String file, int start, int end, String reason) {
		return """
				{"session":"s","time":"%s","turn":"t","agent":null,"tool":"Edit","toolUseId":"%s","file":"%s",\
				"lines":[%d,%d],"reason":%s,"source":"agent"}
				""".formatted(time, toolUseId, file, start, end, reason == null ? "null" : "\"" + reason + "\"");
	}

	/**
	 * Adds an edits file stamped with the time now: the test file system counts timestamps from 0, which the service
	 * would skip as a file last written long ago.
	 */
	private VirtualFile addEdits(String path, String jsonl) {
		return stamp(myFixture.addFileToProject(path, jsonl).getVirtualFile(), jsonl, Instant.now());
	}

	private VirtualFile stamp(VirtualFile file, String jsonl, Instant time) {
		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			try {
				file.setBinaryContent(jsonl.getBytes());
				// the content write keeps the file system's own counter; this sets the time the service reads
				PersistentFS.getInstance().setTimeStamp(file, time.toEpochMilli());
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		});
		return file;
	}

	private VirtualFile loadEdits(String jsonl) {
		VirtualFile edits = addEdits(EDITS + "session.jsonl", jsonl);
		service.load();
		waitForLoad();
		return edits;
	}

	private Map<String, List<MemberReason>> members() {
		return ApplicationManager.getApplication().runReadAction(
				(Computable<Map<String, List<MemberReason>>>) () -> EditReasonMembers.prepare(getProject(), List.of()).get());
	}

	public void testALiveReasonFollowsLaterEditsAndLandsInItsMember() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "Add c.") + record(now, "t2", "a.xml", 4, 4, null));

		VirtualFile edits = myFixture.findFileInTempDir(EDITS + "session.jsonl");
		List<String> editsPaths = EditReasonsService.editsPaths(ReviewsDirs.roots(getProject()).stream().map(VirtualFile::getPath).toList());
		assertTrue(EditReasonsService.isEditsFile(edits, editsPaths));
		// a directory created with the file in it comes as one event: the reviews and edits directories count
		assertTrue(EditReasonsService.containsEditsDir(edits.getParent(), editsPaths));
		assertTrue(EditReasonsService.containsEditsDir(edits.getParent().getParent(), editsPaths));
		assertFalse(EditReasonsService.containsEditsDir(file, editsPaths));
		EditReasonsService.Anchored c = service.reasons().stream().filter(a -> "Add c.".equals(a.reason().reason())).findFirst().orElseThrow();
		assertFalse(c.approximate());
		Document document = FileDocumentManager.getInstance().getDocument(file);
		RangeMarker marker = c.marker();
		assertEquals("  <c/>", document.getText(marker.getTextRange()));

		// two lines inserted above: the anchor moves with the text
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(document.getLineStartOffset(1), "<!-- 1 -->\n<!-- 2 -->\n"));
		assertEquals("  <c/>", document.getText(marker.getTextRange()));

		List<MemberReason> reasons = members().get(file.getPath());
		assertEquals(2, reasons.size());
		MemberReason added = reasons.stream().filter(r -> !r.pending()).findFirst().orElseThrow();
		assertEquals(List.of("root", "c"), added.keyPath());
		MemberReason pending = reasons.stream().filter(MemberReason::pending).findFirst().orElseThrow();
		assertEquals(List.of("root", "a", "b"), pending.keyPath());
	}

	public void testAReasonArrivingLaterKeepsTheAnchorOfItsPendingRecord() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant now = Instant.now();
		VirtualFile edits = loadEdits(record(now, "t1", "a.xml", 6, 6, null));
		Document document = FileDocumentManager.getInstance().getDocument(file);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "\n\n"));

		// the reason is recorded with the lines of the time of the edit, now two lines off
		String appended = record(now, "t1", "a.xml", 6, 6, null) + record(now.plusSeconds(1), "t1", "a.xml", 6, 6, "Add c.");
		stamp(edits, appended, Instant.now());
		service.load();
		waitForLoad();

		EditReasonsService.Anchored c = service.reasons().iterator().next();
		assertEquals("Add c.", c.reason().reason());
		assertEquals("  <c/>", document.getText(c.marker().getTextRange()));
	}

	public void testDisablingEditReasonsClearsAndStopsReloadingUntilEnabled() {
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "Still here."));
		assertEquals(List.of("Still here."), service.reasons().stream().map(a -> a.reason().reason()).toList());
		PropertiesComponent properties = PropertiesComponent.getInstance();
		try {
			properties.setValue(EditReasonsService.SHOW_OPTION_KEY, false, true);
			service.viewOptionChanged();
			waitForLoad();
			assertEquals(List.of("Still here."), service.reasons().stream().map(a -> a.reason().reason()).toList());

			service.load();
			waitForLoad();
			assertEquals("load is a no-op while hidden", List.of("Still here."),
					service.reasons().stream().map(a -> a.reason().reason()).toList());

			properties.setValue(EditReasonsService.SHOW_OPTION_KEY, true);
			service.load();
			waitForLoad();
			assertEquals(List.of("Still here."), service.reasons().stream().map(a -> a.reason().reason()).toList());
		} finally {
			properties.setValue(EditReasonsService.SHOW_OPTION_KEY, true);
			service.load();
			waitForLoad();
		}
	}

	public void testOlderRecordsAreApproximateAndOldOnesAreLeftOut() {
		myFixture.addFileToProject("a.xml", TEXT);
		Instant now = Instant.now();
		loadEdits(record(now.minus(Duration.ofHours(1)), "t1", "a.xml", 6, 6, "Earlier.")
				+ record(now.minus(Duration.ofDays(8)), "t2", "a.xml", 4, 4, "Too old."));

		assertEquals(1, service.reasons().size());
		assertTrue(service.reasons().iterator().next().approximate());
	}

	public void testAChangeWithoutLinesStandsForTheFileOnItsFirstLine() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		loadEdits(record(Instant.now(), "t1", "a.xml", 0, 0, "Wrote it.").replace("\"lines\":[0,0]", "\"lines\":null"));

		RangeMarker marker = service.reasons().iterator().next().marker();
		assertNotNull(marker);
		Document document = FileDocumentManager.getInstance().getDocument(file);
		assertEquals(0, document.getLineNumber(marker.getStartOffset()));
		assertFalse(service.reasons().iterator().next().approximate());
	}

	public void testAnEditsFileLastWrittenBeforeTheCutOffIsNotRead() {
		myFixture.addFileToProject("a.xml", TEXT);
		String jsonl = record(Instant.now(), "t1", "a.xml", 6, 6, "Never read.");
		stamp(myFixture.addFileToProject(EDITS + "session.jsonl", jsonl).getVirtualFile(), jsonl,
				Instant.now().minus(Duration.ofDays(8)));
		service.load();
		waitForLoad();

		assertEmpty(service.reasons());
	}

	public void testLinesPastTheEndAreClamped() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		loadEdits(record(Instant.now(), "t1", "a.xml", 50, 60, "Shrunk since."));

		RangeMarker marker = service.reasons().iterator().next().marker();
		Document document = FileDocumentManager.getInstance().getDocument(file);
		assertEquals(document.getLineCount() - 1, document.getLineNumber(marker.getStartOffset()));
	}

	public void testChangingTheReviewsDirectoryLoadsItsReasons() {
		myFixture.addFileToProject("a.xml", TEXT);
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "From reviews."));
		addEdits("ai/" + EditReasonsService.EDITS_DIR + "/session.jsonl", record(now, "t2", "a.xml", 4, 4, "From ai."));
		try {
			StructureDiffSettings.getInstance().getState().reviewsDir = "ai";
			// as the settings page announces it
			ApplicationManager.getApplication().getMessageBus().syncPublisher(StructureDiffSettings.ReviewsDirListener.TOPIC).reviewsDirChanged();
			// the listener refreshes the new directory on another thread before it loads
			PlatformTestUtil.waitWithEventsDispatching("the reasons are reloaded from the new directory",
					() -> service.reasons().stream().anyMatch(anchored -> "From ai.".equals(anchored.reason().reason())), 10);
			assertEquals(List.of("From ai."), service.reasons().stream().map(a -> a.reason().reason()).toList());
		} finally {
			StructureDiffSettings.getInstance().loadState(new StructureDiffSettings.State());
		}
	}

	public void testKeyPathsAreReusedWhileTheFileIsUnchanged() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		loadEdits(record(Instant.now(), "t1", "a.xml", 6, 6, "Add c."));

		members();
		EditReasonMembers.CachedKeyPaths first = service.keyPathCache().get(file.getPath());
		assertNotNull(first);
		members();
		assertSame(first, service.keyPathCache().get(file.getPath()));

		Document document = FileDocumentManager.getInstance().getDocument(file);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "\n"));
		assertEquals(List.of("root", "c"), members().get(file.getPath()).getFirst().keyPath());
		assertNotSame(first, service.keyPathCache().get(file.getPath()));
	}

	public void testAReasonFromBeforeTheStartIsFoundByItsTextOrDropped() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant earlier = Instant.now().minusSeconds(3600);
		// recorded on line 4, but <c/> has since moved to line 6; the second's text is gone
		String moved = record(earlier, "t1", "a.xml", 4, 4, "On c.").replace("\"reason\"", "\"text\":\"    <c/>\",\"reason\"");
		String gone = record(earlier, "t2", "a.xml", 5, 5, "On d.").replace("\"reason\"", "\"text\":\"<d/>\",\"reason\"");
		loadEdits(moved + gone);

		assertEquals(List.of("On c."), service.reasons().stream().map(a -> a.reason().reason()).toList());
		EditReasonsService.Anchored c = service.reasons().iterator().next();
		// placed by its text, but "<c/>" is too short to be sure it is this one
		assertTrue(c.approximate());
		assertEquals("  <c/>", FileDocumentManager.getInstance().getDocument(file).getText(c.marker().getTextRange()));
		assertEquals(List.of("root", "c"), members().get(file.getPath()).getFirst().keyPath());
	}

	public void testADroppedReasonIsPlacedOnceItsTextComesBack() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant earlier = Instant.now().minusSeconds(3600);
		loadEdits(record(earlier, "t1", "a.xml", 5, 5, "On d.").replace("\"reason\"", "\"text\":\"<d/>\",\"reason\""));
		assertEmpty(service.reasons());

		// the search is skipped while the document is unchanged, and runs again once it changes
		service.load();
		waitForLoad();
		assertEmpty(service.reasons());
		Document document = FileDocumentManager.getInstance().getDocument(file);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(document.getLineStartOffset(5), "  <d/>\n"));
		service.load();
		waitForLoad();

		assertEquals(List.of("On d."), service.reasons().stream().map(a -> a.reason().reason()).toList());
		assertEquals("  <d/>", document.getText(service.reasons().iterator().next().marker().getTextRange()));
	}

	public void testADroppedReasonIsNotSearchedAgainWhileItsDocumentIsUnchanged() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant earlier = Instant.now().minusSeconds(3600);
		loadEdits(record(earlier, "t1", "a.xml", 5, 5, "On d.").replace("\"reason\"", "\"text\":\"<d/>\",\"reason\""));
		Document document = FileDocumentManager.getInstance().getDocument(file);
		assertEquals("the drop is remembered at the document's stamp",
				List.of(document.getModificationStamp()), List.copyOf(service.goneStamps().values()));

		// the same record now carries text the document has, the document unchanged: a search would find it, so a
		// reason still missing shows the search was skipped
		VirtualFile edits = myFixture.findFileInTempDir(EDITS + "session.jsonl");
		stamp(edits, record(earlier, "t1", "a.xml", 5, 5, "On d.").replace("\"reason\"", "\"text\":\"<c/>\",\"reason\""),
				Instant.now());
		service.load();
		waitForLoad();
		assertEmpty("not searched while the document is unchanged", service.reasons());

		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "\n"));
		service.load();
		waitForLoad();
		assertEquals("searched again once the document changed", List.of("On d."),
				service.reasons().stream().map(a -> a.reason().reason()).toList());
		assertTrue("found, so no longer remembered as gone", service.goneStamps().isEmpty());

		// a record no longer read takes its drop with it
		stamp(edits, record(earlier, "t2", "a.xml", 5, 5, "Gone too.").replace("\"reason\"", "\"text\":\"<e/>\",\"reason\""),
				Instant.now());
		service.load();
		waitForLoad();
		assertEquals(1, service.goneStamps().size());
		stamp(edits, "", Instant.now());
		service.load();
		waitForLoad();
		assertTrue(service.goneStamps().isEmpty());
	}

	public void testAReasonWhoseFileAppearsLaterIsPlacedThen() {
		loadEdits(record(Instant.now(), "t1", "late.xml", 6, 6, "Add c."));
		assertNull("no file yet, no place", service.reasons().iterator().next().marker());

		VirtualFile file = myFixture.addFileToProject("late.xml", TEXT).getVirtualFile();
		service.load();
		waitForLoad();

		RangeMarker marker = service.reasons().iterator().next().marker();
		assertNotNull("placed once the file exists", marker);
		assertEquals("  <c/>", FileDocumentManager.getInstance().getDocument(file).getText(marker.getTextRange()));
	}

	public void testAnApproximateReasonOutsideTheChangedLinesIsLeftOut() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant earlier = Instant.now().minusSeconds(3600);
		Instant now = Instant.now();
		// <b> (line 4) and <c/> (line 6) as recorded before the IDE started, <a> (line 3) live
		loadEdits(record(earlier, "t1", "a.xml", 4, 4, "On b.") + record(earlier, "t2", "a.xml", 6, 6, "On c.")
				+ record(now, "t3", "a.xml", 3, 3, "On a."));
		// only <c/> changed since the base
		FileDiff diff = new FileDiff(null, file.getPath(), file.getFileType(), FileStatus.MODIFIED,
				TEXT.replace("  <c/>\n", ""), TEXT, 1, 0, null);

		List<MemberReason> shown = ApplicationManager.getApplication().runReadAction(
				(Computable<Map<String, List<MemberReason>>>) () -> EditReasonMembers.prepare(getProject(), List.of(diff)).get())
				.get(file.getPath());
		// the live one stays though its line is unchanged: it followed the edits
		assertEquals(Set.of("On c.", "On a."), shown.stream().map(MemberReason::reason).collect(Collectors.toSet()));
		// a file the diff does not show keeps all its reasons
		assertEquals(3, members().get(file.getPath()).size());
	}

	public void testWritingAnEditsFileReloadsTheReasonsFromTheListener() {
		myFixture.addFileToProject("a.xml", TEXT);
		// no load(): the VFS listener classifies the event and applies the reload on the EDT
		addEdits(EDITS + "session.jsonl", record(Instant.now(), "t1", "a.xml", 6, 6, "From the VFS."));
		waitForLoad();

		assertEquals(List.of("From the VFS."), service.reasons().stream().map(a -> a.reason().reason()).toList());
	}

	public void testAReasonShowsInTheGutterAndItsClickTintsTheRange() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "Add c.") + record(now, "t2", "a.xml", 4, 4, null));

		// a VFS event may start another load, whose apply lands a moment later
		PlatformTestUtil.waitWithEventsDispatching("both reasons are placed", () -> gutterIcons(document).size() == 2, 5);
		List<RangeHighlighter> icons = gutterIcons(document);
		assertEquals(2, icons.size());
		RangeHighlighter added = icons.stream()
				.filter(icon -> icon.getGutterIconRenderer().getAccessibleTooltipText().contains(" · Add c."))
				.findFirst().orElseThrow();
		// the platform's own grey all but disappears on the gutter; the icon is tinted with the theme's link color
		GutterIconRenderer renderer = added.getGutterIconRenderer();
		assertNotNull(renderer);
		assertNotSame(AllIcons.Actions.Edit, renderer.getIcon());
		assertEquals(document.getLineStartOffset(5), added.getStartOffset());
		assertEquals(document.getLineEndOffset(5), added.getEndOffset());
		assertTrue("a pending reason shows as pending", icons.stream()
				.anyMatch(icon -> icon.getGutterIconRenderer().getAccessibleTooltipText().contains(" · reason pending")));

		// the icon follows later edits above it, as the anchor does
		WriteCommandAction.runWriteCommandAction(getProject(),
				() -> document.insertString(document.getLineStartOffset(1), "<!-- 1 -->\n<!-- 2 -->\n"));
		assertEquals(document.getLineStartOffset(7), added.getStartOffset());
		assertEquals(document.getLineEndOffset(7), added.getEndOffset());

		// a click tints the range: the snapshot shows <c/> as added, so the pencil is barred; the snapshot's after text is
		// the document as it is now, comments included, or the diff would name other lines than the anchor stands on
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>x</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, document.getText(),
				0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the changed range is barred", () -> bars(document).size() == 1, 5);
		RangeHighlighter barred = gutterIcons(document).stream()
				.filter(icon -> icon.getGutterIconRenderer().getAccessibleTooltipText().contains(" · Add c."))
				.findFirst().orElseThrow();
		((EditReasonGutter) barred.getGutterIconRenderer()).reveal(editor);
		assertFalse("the click does not select", editor.getSelectionModel().hasSelection());
		assertEquals(List.of(document.getLineStartOffset(7)),
				tints(document).stream().map(RangeHighlighter::getStartOffset).toList());
	}

	public void testOverlappingAndTouchingRangesOfAReasonShareOneBar() {
		Document document = EditorFactory.getInstance().createDocument("1\n2\n3\n4\n5\n6\n7\n8\n9\n");
		TextRange lines1to5 = new TextRange(document.getLineStartOffset(0), document.getLineEndOffset(4));
		TextRange lines2to3 = new TextRange(document.getLineStartOffset(1), document.getLineEndOffset(2));
		TextRange line6 = new TextRange(document.getLineStartOffset(5), document.getLineEndOffset(5));
		TextRange line9 = new TextRange(document.getLineStartOffset(8), document.getLineEndOffset(8));

		assertEquals(List.of(lines1to5.union(line6), line9),
				EditReasonHighlighter.barSpans(document, List.of(line9, lines2to3, line6, lines1to5)));
	}

	public void testOneGutterIconPerReasonAsInTheTree() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		Instant now = Instant.now();
		// one tool call with two ranges, a record per range: the tree lists the reason once
		loadEdits(record(now, "t1", "a.xml", 6, 6, "One reason, two ranges.")
				+ record(now, "t2", "a.xml", 4, 4, "One reason, two ranges.")
				+ record(now, "t3", "a.xml", 3, 3, "Another reason."));

		// a VFS event may start another load, whose apply lands a moment later
		PlatformTestUtil.waitWithEventsDispatching("both reasons are placed", () -> gutterIcons(document).size() == 2, 5);
		assertEquals("the first of the ranges stands for the reason, the other icon is its own",
				List.of(document.getLineStartOffset(2), document.getLineStartOffset(3)),
				gutterIcons(document).stream().map(RangeHighlighter::getStartOffset).sorted().toList());
	}

	public void testAReasonBarsOnlyTheRangesTheSnapshotChanges() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		service.snapshotChanged(List.of());
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "One reason, two ranges.")
				+ record(now, "t2", "a.xml", 4, 4, "One reason, two ranges."));

		// a VFS event may start another load, whose apply lands a moment later
		PlatformTestUtil.waitWithEventsDispatching("nothing is changed yet, so nothing is barred",
				() -> gutterIcons(document).size() == 1 && bars(document).isEmpty(), 5);

		// the snapshot adds <c/> only: that range is barred, the unchanged <b>x</b> is not
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>x</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the changed range is barred", () -> bars(document).size() == 1, 5);

		assertEquals(List.of(document.getLineStartOffset(5)),
				bars(document).stream().map(RangeHighlighter::getStartOffset).toList());
	}

	public void testEachBarOfAReasonTakesItsOwnPlacesColor() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		service.snapshotChanged(List.of());
		Instant now = Instant.now();
		// one reason over a changed line (<b>x</b>) and an added one (<c/>): two kinds, one reason
		loadEdits(record(now, "t1", "a.xml", 4, 4, "Two kinds, one reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 6, 6, "Two kinds, one reason."));
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));

		PlatformTestUtil.waitWithEventsDispatching("both changed ranges are barred", () -> bars(document).size() == 2, 5);
		List<RangeHighlighter> bars = bars(document);
		assertEquals("the head is the first changed range", document.getLineStartOffset(3),
				gutterIcons(document).getFirst().getStartOffset());
		List<Color> colors = bars.stream().sorted(java.util.Comparator.comparingInt(RangeHighlighter::getStartOffset))
				.map(bar -> reasonBar(bar).color()).toList();
		assertEquals("each place in its own kind's color", List.of(EditReasonGutter.color(ChangeKind.MODIFIED),
				EditReasonGutter.color(ChangeKind.ADDED)), colors);
	}

	public void testABarAppearsWhenAnUnchangedRangeStartsToChange() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		service.snapshotChanged(List.of());
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "One reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 4, 4, "One reason."));
		String path = service.reasons().iterator().next().path();
		String addedOnly = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>x</b>\n  </a>\n</root>\n";
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, addedOnly, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("only the added range is barred", () -> bars(document).size() == 1, 5);

		// a later edit changes <b>x</b> too: the other range's bar appears, though the pencil and its kind stay
		String bothChanged = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, bothChanged, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the second range is barred as well", () -> bars(document).size() == 2, 5);
	}

	public void testTwoReasonsOverTheSameLinesTakeTheirOwnLanes() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		service.snapshotChanged(List.of());
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 4, 4, "One reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 4, 4, "Another reason."));
		// <b>x</b> changed: both reasons sit on that line
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("both reasons are barred", () -> bars(document).size() == 2, 5);

		assertTrue("reasons over the same lines sit in different lanes",
				lane(bars(document).get(0)) != lane(bars(document).get(1)));
	}

	public void testReasonsOverDifferentLinesShareALane() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		service.snapshotChanged(List.of());
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 3, 3, "One reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 6, 6, "Another reason."));
		// <a> changed and <c/> added: one range above the other, so one lane is enough
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <A/>\n    <b>x</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("both reasons are barred", () -> bars(document).size() == 2, 5);

		assertEquals("ranges that do not meet share the margin's first lane", lane(bars(document).get(0)),
				lane(bars(document).get(1)));
	}

	private static int lane(RangeHighlighter bar) {
		return reasonBar(bar).lane();
	}

	private static EditReasonHighlighter.ReasonBar reasonBar(RangeHighlighter bar) {
		return (EditReasonHighlighter.ReasonBar) bar.getLineMarkerRenderer();
	}

	public void testThePencilStandsOnTheChangedRangeOfItsReason() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		service.snapshotChanged(List.of());
		Instant now = Instant.now();
		// the newer record is on unchanged code, the older one on the line the snapshot adds
		loadEdits(record(now, "t1", "a.xml", 6, 6, "Same reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 2, 2, "Same reason."));

		// a VFS event may start another load, whose apply lands a moment later
		PlatformTestUtil.waitWithEventsDispatching("both ranges are placed, nothing is barred yet",
				() -> gutterIcons(document).size() == 1 && bars(document).isEmpty(), 5);
		assertEquals("without a snapshot the newest record stands for it", document.getLineStartOffset(1),
				gutterIcons(document).getFirst().getStartOffset());

		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>x</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the snapshot's kind moves the pencil", () -> {
			List<RangeHighlighter> icons = gutterIcons(document);
			return icons.size() == 1 && icons.getFirst().getStartOffset() == document.getLineStartOffset(5);
		}, 5);

		assertEquals("the pencil stands on the changed range", document.getLineStartOffset(5),
				gutterIcons(document).getFirst().getStartOffset());
		assertEquals("and only that range is barred", List.of(document.getLineStartOffset(5)),
				bars(document).stream().map(RangeHighlighter::getStartOffset).toList());
		assertFalse("the tooltip leaves the other ranges to their hover",
				gutterIcons(document).getFirst().getGutterIconRenderer().getAccessibleTooltipText().contains("also marks"));
	}

	public void testTheNewestRecordOfAReasonStandsForIt() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 4, 4, "Same reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 6, 6, "Same reason."));

		List<RangeHighlighter> icons = gutterIcons(document);
		assertEquals(1, icons.size());
		assertEquals(document.getLineStartOffset(5), icons.getFirst().getStartOffset());
	}

	public void testPendingReasonsEachKeepTheirIcon() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, null) + record(now, "t2", "a.xml", 4, 4, null));

		// a VFS event may start another load, whose apply lands a moment later
		PlatformTestUtil.waitWithEventsDispatching("each pending change marks where the agent works",
				() -> gutterIcons(document).size() == 2, 5);
	}

	public void testHidingEditReasonsRemovesTheGutterIcons() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		loadEdits(record(Instant.now(), "t1", "a.xml", 6, 6, "Add c."));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed", () -> gutterIcons(document).size() == 1, 5);

		PropertiesComponent properties = PropertiesComponent.getInstance();
		try {
			properties.setValue(EditReasonsService.SHOW_OPTION_KEY, false, true);
			service.viewOptionChanged();
			assertEquals("hiding clears them at once", List.of(), gutterIcons(document));

			// shown again: the next load places them again
			properties.setValue(EditReasonsService.SHOW_OPTION_KEY, true);
			service.load();
			PlatformTestUtil.waitWithEventsDispatching("the reason is placed again",
					() -> gutterIcons(document).size() == 1, 5);
		} finally {
			properties.setValue(EditReasonsService.SHOW_OPTION_KEY, true);
		}
	}

	public void testTheHoverFindsThePencilWhereFoldsMovedIt() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		loadEdits(record(Instant.now(), "t1", "a.xml", 6, 6, "Add c."));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed", () -> gutterIcons(document).size() == 1, 5);
		EditorGutterComponentEx gutter = (EditorGutterComponentEx)editor.getGutter();
		RangeHighlighter icon = gutterIcons(document).getFirst();
		Point center = gutter.getCenterPoint(icon.getGutterIconRenderer());
		assertNotNull(center);
		assertEquals("Add c.", service.hintAt(editor, center).reason().reason());
		assertNull("a line away names nothing", service.hintAt(editor, new Point(center.x, center.y + editor.getLineHeight())));

		// a fold above the pencil: its document line is no longer its visual line
		editor.getFoldingModel().runBatchFoldingOperation(() -> {
			for (int line : new int[]{1, 3}) {
				FoldRegion region = editor.getFoldingModel().addFoldRegion(document.getLineStartOffset(line),
						document.getLineStartOffset(line + 1), "...");
				assertNotNull(region);
				region.setExpanded(false);
			}
		});

		Point folded = gutter.getCenterPoint(icon.getGutterIconRenderer());
		assertNotNull(folded);
		assertFalse("the fold moved the icon up from its document line", center.y == folded.y);
		assertEquals("the icon is named where it shows", "Add c.", service.hintAt(editor, folded).reason().reason());
	}

	public void testAPencilNamesItsOwnReasonWhenTwoShareALine() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		// both start on line 4, so their pencils sit side by side on one line; the second covers one line more
		loadEdits(record(now, "t1", "a.xml", 4, 4, "First reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 4, 5, "Second reason."));
		PlatformTestUtil.waitWithEventsDispatching("both are placed", () -> gutterIcons(document).size() == 2, 5);
		// both ranges are changed (<b>x</b> became y), so a click on either pencil tints it
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("both are barred", () -> bars(document).size() == 2, 5);
		EditorGutterComponentEx gutter = (EditorGutterComponentEx)editor.getGutter();
		int line = document.getLineNumber(gutterIcons(document).getFirst().getStartOffset());

		Map<Point, EditReasonGutter> icons = new LinkedHashMap<>();
		for (Pair<GutterMark, Rectangle> mark : gutter.getGutterRenderersAndRectangles(line)) {
			if (mark.getFirst() instanceof EditReasonGutter pencil) {
				Rectangle rect = mark.getSecond();
				icons.put(new Point(rect.x + rect.width / 2, rect.y + rect.height / 2), pencil);
			}
		}
		assertEquals("each reason has its own pencil", 2, icons.size());
		icons.forEach((icon, pencil) -> assertEquals("the pencil names its own reason", pencil.reason().reason(),
				service.hintAt(editor, icon).reason().reason()));

			// a click on the second pencil shows that reason, not the line's first one
		List.copyOf(icons.values()).get(1).reveal(editor);
		assertEquals("the second reason's range is tinted", List.of(document.getLineEndOffset(4)),
				tints(document).stream().map(RangeHighlighter::getEndOffset).toList());
	}

	public void testABarIsFoundWhereItsLinesAreNotWhereItLastPainted() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 4, 4, "Both changed.") + record(now, "t2", "a.xml", 6, 6, "Both changed."));
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		service.snapshotChanged(List.of(new FileDiff(null, service.reasons().iterator().next().path(), null,
				FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed with both bars", () -> bars(document).size() == 2, 5);
		RangeHighlighter second = bars(document).stream()
				.filter(bar -> document.getLineNumber(bar.getStartOffset()) == 5).findFirst().orElseThrow();
		EditReasonHighlighter.ReasonBar renderer = (EditReasonHighlighter.ReasonBar)second.getLineMarkerRenderer();
		Graphics2D graphics = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics();
		try {
			renderer.paint(editor, graphics, new Rectangle(60, editor.offsetToXY(second.getStartOffset()).y, 20, editor.getLineHeight()));
		}
		finally {
			graphics.dispose();
		}
		int x = 0;
		while (x < 100 && !renderer.atX(x)) {
			x++;
		}
		int painted = editor.offsetToXY(second.getStartOffset()).y + editor.getLineHeight() / 2;

		// three lines above it, and no paint since: the bar's lines moved down, its last paint did not
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "\n\n\n"));
		assertNull("where the bar painted, another line is now", service.hintAt(editor, new Point(x, painted)));
		int moved = editor.offsetToXY(second.getStartOffset()).y + editor.getLineHeight() / 2;
		assertEquals("Both changed.", service.hintAt(editor, new Point(x, moved)).reason().reason());
	}

	public void testAShortTextFoundElsewhereStaysApproximate() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant earlier = Instant.now().minusSeconds(3600);
		// both moved two lines: the declaration is distinctive, "</a>" could be any closing tag of its kind
		loadEdits(record(earlier, "t1", "a.xml", 3, 3, "On the declaration.")
				.replace("\"reason\"", "\"text\":\"<?xml version=\\\"1.0\\\"?>\",\"reason\"")
				+ record(earlier, "t2", "a.xml", 7, 7, "On a.").replace("\"reason\"", "\"text\":\"</a>\",\"reason\""));

		Document document = FileDocumentManager.getInstance().getDocument(file);
		EditReasonsService.Anchored declaration = service.reasons().stream()
				.filter(a -> "On the declaration.".equals(a.reason().reason())).findFirst().orElseThrow();
		assertFalse(declaration.approximate());
		assertEquals(0, document.getLineNumber(declaration.marker().getStartOffset()));
		EditReasonsService.Anchored a = service.reasons().stream()
				.filter(r -> "On a.".equals(r.reason().reason())).findFirst().orElseThrow();
		assertTrue("found, but too short to be sure", a.approximate());
		assertEquals("  </a>", document.getText(a.marker().getTextRange()));
	}

	public void testTheSmallPencilShowsTheWholeReason() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		// one reason over two changed ranges: its pencil stands on the first, a small one on the second
		loadEdits(record(now, "t1", "a.xml", 4, 4, "Both changed.") + record(now, "t2", "a.xml", 6, 6, "Both changed."));
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		service.snapshotChanged(List.of(new FileDiff(null, service.reasons().iterator().next().path(), null,
				FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed with both bars", () -> {
			List<RangeHighlighter> bars = bars(document);
			return gutterIcons(document).size() == 2 && bars.size() == 2;
		}, 5);
		List<RangeHighlighter> icons = gutterIcons(document).stream()
				.sorted(java.util.Comparator.comparingInt(RangeHighlighter::getStartOffset)).toList();
		assertTrue("the second place has the smaller pencil",
				icons.get(1).getGutterIconRenderer().getIcon().getIconWidth()
						< icons.get(0).getGutterIconRenderer().getIcon().getIconWidth());
		assertTrue("the first pencil names its place", icons.get(0).getGutterIconRenderer().getAccessibleTooltipText()
				.contains("[1/2]"));
		assertTrue("the small pencil names its own place", icons.get(1).getGutterIconRenderer().getAccessibleTooltipText()
				.contains("[2/2]"));
		RangeHighlighter lowerBar = bars(document).stream()
				.filter(bar -> document.getLineNumber(bar.getStartOffset()) == 5).findFirst().orElseThrow();
		assertSame("the lower bar's hover names the lower place", icons.get(1).getGutterIconRenderer(),
				reasonBar(lowerBar).pencil());
		((EditReasonGutter)icons.get(1).getGutterIconRenderer()).reveal(editor);
		assertEquals("both ranges of the reason are tinted",
				List.of(document.getLineStartOffset(3), document.getLineStartOffset(5)),
				tints(document).stream().map(RangeHighlighter::getStartOffset).sorted().toList());
	}

	public void testTheSmallPencilLeavesTheEditorWhereItIs() {
		StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>\n<root>\n");
		for (int i = 0; i < 40; i++) {
			xml.append("  <item/>\n");
		}
		xml.append("</root>\n");
		String text = xml.toString();
		myFixture.configureByText("a.xml", text);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		// one reason over two changed ranges: its pencil stands on the first, far above, a small one on the second
		loadEdits(record(now, "t1", "a.xml", 3, 3, "One reason, two ranges.")
				+ record(now, "t2", "a.xml", 30, 30, "One reason, two ranges."));
		StringBuilder beforeXml = new StringBuilder("<?xml version=\"1.0\"?>\n<root>\n");
		for (int i = 0; i < 40; i++) {
			beforeXml.append(i == 0 || i == 27 ? "  <before/>\n" : "  <item/>\n");
		}
		beforeXml.append("</root>\n");
		service.snapshotChanged(List.of(new FileDiff(null, service.reasons().iterator().next().path(), null,
				FileStatus.MODIFIED, beforeXml.toString(), text, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed with both bars", () -> bars(document).size() == 2, 5);

		// the small pencil on screen, the first pencil's range scrolled out of view above it: a click must tint the
		// reason where it is, not scroll away to the first pencil
		int barLine = document.getLineStartOffset(29);
		editor.getScrollingModel().scrollVertically(editor.offsetToXY(barLine).y);
		Rectangle visible = editor.getScrollingModel().getVisibleArea();
		assertTrue("the setup has the pencil's range out of view",
				editor.offsetToXY(document.getLineStartOffset(2)).y < visible.y);

		RangeHighlighter lower = gutterIcons(document).stream()
				.filter(icon -> document.getLineNumber(icon.getStartOffset()) == 29).findFirst().orElseThrow();
		((EditReasonGutter)lower.getGutterIconRenderer()).reveal(editor);

		assertEquals("the editor stayed where the small pencil is", visible, editor.getScrollingModel().getVisibleArea());
		assertEquals("both ranges of the reason are tinted",
				List.of(document.getLineStartOffset(2), document.getLineStartOffset(29)),
				tints(document).stream().map(RangeHighlighter::getStartOffset).sorted().toList());
	}

	private List<RangeHighlighter> gutterIcons(Document document) {
		return Arrays.stream(DocumentMarkupModel.forDocument(document, getProject(), true).getAllHighlighters())
				.filter(highlighter -> highlighter.getGutterIconRenderer() instanceof EditReasonGutter)
				.toList();
	}

	public void testAClickTintsEveryRangeOfTheReason() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "One reason.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 4, 4, "One reason."));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed", () -> gutterIcons(document).size() == 1, 5);
		// both ranges are changed: <b>x</b> became y and <c/> was added
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("both changed ranges are barred", () -> bars(document).size() == 2, 5);

		((EditReasonGutter) gutterIcons(document).getFirst().getGutterIconRenderer()).reveal(editor);

		assertFalse("a click does not select anything", editor.getSelectionModel().hasSelection());
		assertEquals("both of its ranges are tinted", List.of(document.getLineStartOffset(3), document.getLineStartOffset(5)),
				tints(document).stream().map(RangeHighlighter::getStartOffset).sorted().toList());

		// moving the caret takes the tint off again
		editor.getCaretModel().moveToOffset(document.getLineStartOffset(1));
		assertEquals(List.of(), tints(document));
	}

	public void testAClickTintsOnlyTheRangesTheSnapshotChanges() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		// one reason over an unchanged line (<a>), a changed one (<b>x</b>) and an added one (<c/>)
		loadEdits(record(now, "t1", "a.xml", 3, 3, "One reason, three ranges.")
				+ record(now.plusSeconds(1), "t2", "a.xml", 4, 4, "One reason, three ranges.")
				+ record(now.plusSeconds(2), "t3", "a.xml", 6, 6, "One reason, three ranges."));
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("only the changed ranges are barred", () -> bars(document).size() == 2, 5);
		assertEquals("the pencil stands on the first changed range", document.getLineStartOffset(3),
				gutterIcons(document).getFirst().getStartOffset());

		((EditReasonGutter) gutterIcons(document).getFirst().getGutterIconRenderer()).reveal(editor);

		assertEquals("a click tints the changed ranges only, not the unchanged one the reason also covers",
				List.of(document.getLineStartOffset(3), document.getLineStartOffset(5)),
				tints(document).stream().map(RangeHighlighter::getStartOffset).sorted().toList());
	}

	public void testAClickOnAGreyPencilTintsNothing() {
		myFixture.configureByText("a.xml", TEXT);
		Editor editor = myFixture.getEditor();
		Document document = editor.getDocument();
		Instant now = Instant.now();
		// no snapshot pushed: the range has no known kind, so the pencil is grey and has no bar
		loadEdits(record(now, "t1", "a.xml", 6, 6, "One reason."));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed", () -> gutterIcons(document).size() == 1, 5);
		assertTrue("nothing is known to have changed, so there is no bar", bars(document).isEmpty());

		((EditReasonGutter) gutterIcons(document).getFirst().getGutterIconRenderer()).reveal(editor);

		assertEquals("a grey pencil tints nothing: its lines may be code the snapshot doesn't change", List.of(),
				tints(document));
	}

	public void testARevealTintGoesWhenItsEditorCloses() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "One reason."));
		PlatformTestUtil.waitWithEventsDispatching("the reason is placed", () -> gutterIcons(document).size() == 1, 5);
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";
		String path = service.reasons().iterator().next().path();
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("the changed range is barred", () -> bars(document).size() == 1, 5);

		// the tint lives in the document's markup model, so it must not outlive the editor it was revealed in: its
		// caret listener dies with the editor, and no other one could take the tint off
		Editor second = EditorFactory.getInstance().createEditor(document);
		try {
			((EditReasonGutter) gutterIcons(document).getFirst().getGutterIconRenderer()).reveal(second);
			assertEquals(List.of(document.getLineStartOffset(5)),
					tints(document).stream().map(RangeHighlighter::getStartOffset).toList());
		}
		finally {
			EditorFactory.getInstance().releaseEditor(second);
		}
		assertEquals("the closed editor's tint goes with it", List.of(), tints(document));
	}

	private List<RangeHighlighter> tints(Document document) {
		return Arrays.stream(DocumentMarkupModel.forDocument(document, getProject(), true).getAllHighlighters())
				.filter(highlighter -> highlighter.getTextAttributes() != null
						&& highlighter.getTextAttributes().getBackgroundColor() != null)
				.toList();
	}

	private List<RangeHighlighter> bars(Document document) {
		return Arrays.stream(DocumentMarkupModel.forDocument(document, getProject(), true).getAllHighlighters())
				.filter(highlighter -> highlighter.getLineMarkerRenderer() != null)
				.toList();
	}

	public void testTheKindOfAChange() {
		assertEquals(ChangeKind.ADDED, EditReasonsService.kind(List.of(new Diff.Change(0, 0, 0, 2, null)), 0, 1));
		assertEquals(ChangeKind.MODIFIED, EditReasonsService.kind(List.of(new Diff.Change(3, 3, 1, 2, null)), 4, 4));
		assertEquals(ChangeKind.REMOVED, EditReasonsService.kind(List.of(new Diff.Change(2, 2, 1, 0, null)), 2, 2));
		assertNull("a range the diff does not change has no kind",
				EditReasonsService.kind(List.of(new Diff.Change(5, 5, 0, 1, null)), 0, 2));
		assertNull(EditReasonsService.kind(List.of(), 2, 2));
	}

	public void testTheOldLinesOfAChange() {
		LineChanges changes = new LineChanges(List.of(new Diff.Change(2, 2, 3, 0, null), new Diff.Change(8, 5, 1, 2, null)));
		assertEquals("a deletion names the old lines it took", new LineChanges.BeforeLines(2, 4), changes.beforeLines(2, 2));
		assertEquals("a replacement names the old lines it replaced", new LineChanges.BeforeLines(8, 8),
				changes.beforeLines(6, 6));
		assertNull("lines no change covers have none", changes.beforeLines(0, 0));
		assertNull("an addition has none", new LineChanges(List.of(new Diff.Change(0, 0, 0, 2, null))).beforeLines(0, 1));
	}

	public void testEachPlaceShowsItsOwnKindOfChange() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		Instant now = Instant.now();
		// one reason: <b>y</b> became <b>x</b> (line 4), and <d/> was deleted before </root> (line 7)
		loadEdits(record(now, "t1", "a.xml", 4, 4, "One reason.") + record(now, "t2", "a.xml", 7, 7, "One reason."));
		String before = TEXT.replace("<b>x</b>", "<b>y</b>").replace("  <c/>\n", "  <c/>\n  <d/>\n");
		service.snapshotChanged(List.of(new FileDiff(null, service.reasons().iterator().next().path(), null,
				FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		PlatformTestUtil.waitWithEventsDispatching("both places are marked", () -> gutterIcons(document).size() == 2, 5);
		List<EditReasonGutter> pencils = gutterIcons(document).stream()
				.sorted(java.util.Comparator.comparingInt(RangeHighlighter::getStartOffset))
				.map(icon -> (EditReasonGutter)icon.getGutterIconRenderer()).toList();
		assertEquals("the changed line is modified", ChangeKind.MODIFIED, pencils.get(0).kind());
		assertEquals("the place after the deletion is removed, though the reason's first pencil is not",
				ChangeKind.REMOVED, pencils.get(1).kind());
		List<RangeHighlighter> bars = bars(document);
		assertEquals("only the modified place has a bar", 1, bars.size());
		assertEquals("in its own color", EditReasonGutter.color(ChangeKind.MODIFIED), reasonBar(bars.getFirst()).color());
	}

	public void testARemovedReasonMarksItsOldLinesInTheDiff() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		// <d/> and <e/> were deleted after <c/>; the diff names the line after them, </root>, line 7
		loadEdits(record(Instant.now(), "t1", "a.xml", 7, 7, "Drop d and e."));
		String before = TEXT.replace("  <c/>\n", "  <c/>\n  <d/>\n  <e/>\n");
		FileDiff diff = new FileDiff(null, service.reasons().iterator().next().path(), null, FileStatus.MODIFIED, before,
				TEXT, 0, 0, null);
		service.snapshotChanged(List.of(diff));
		PlatformTestUtil.waitWithEventsDispatching("the removed pencil is placed", () -> gutterIcons(document).stream()
				.anyMatch(icon -> ((EditReasonGutter)icon.getGutterIconRenderer()).kind() == ChangeKind.REMOVED), 5);
		EditReasonGutter removed = (EditReasonGutter)gutterIcons(document).getFirst().getGutterIconRenderer();
		assertTrue("the hover counts the deleted lines", removed.hoverText().endsWith(" · 2 lines deleted"));
		assertTrue("no bar on the line after the gap", bars(document).isEmpty());
		List<MemberReason> inTree = ApplicationManager.getApplication().runReadAction(
				(Computable<Map<String, List<MemberReason>>>) () -> EditReasonMembers.prepare(getProject(), List.of(diff)).get())
				.get(diff.path());
		assertEquals("the tree puts it on the deleted member, from the old text", "d",
				inTree.getFirst().keyPath().getLast());

		Document beforeDocument = EditorFactory.getInstance().createDocument(before);
		Editor left = EditorFactory.getInstance().createEditor(beforeDocument, getProject());
		try {
			service.markBefore(left, diff);
			List<RangeHighlighter> pencils = Arrays.stream(left.getMarkupModel().getAllHighlighters())
					.filter(highlighter -> highlighter.getGutterIconRenderer() instanceof EditReasonGutter)
					.toList();
			assertEquals("one pencil on the old lines", 1, pencils.size());
			assertEquals("it covers <d/> and <e/>", "  <d/>\n  <e/>",
					beforeDocument.getText(pencils.getFirst().getTextRange()));
			EditReasonGutter pencil = (EditReasonGutter)pencils.getFirst().getGutterIconRenderer();
			assertEquals(ChangeKind.REMOVED, pencil.kind());
			pencil.reveal(left);
			assertEquals("a click there tints the old lines", List.of(beforeDocument.getLineStartOffset(6)),
					tints(beforeDocument).stream().map(RangeHighlighter::getStartOffset).toList());
		}
		finally {
			EditorFactory.getInstance().releaseEditor(left);
		}
	}

	public void testThePencilsTintByTheKindOfChangeFromTheSnapshot() {
		myFixture.configureByText("a.xml", TEXT);
		Document document = myFixture.getEditor().getDocument();
		Instant now = Instant.now();
		loadEdits(record(now, "t1", "a.xml", 6, 6, "Added line.") + record(now, "t2", "a.xml", 4, 4, "Modified line."));
		String path = service.reasons().iterator().next().path();
		String before = """
				<?xml version="1.0"?>
				<root>
				  <a>
				    <b>y</b>
				  </a>
				</root>
				""";
		service.snapshotChanged(List.of(new FileDiff(null, path, null, FileStatus.MODIFIED, before, TEXT, 0, 0, null)));
		waitForLoad();

		List<RangeHighlighter> icons = gutterIcons(document);
		RangeHighlighter added = icons.stream()
				.filter(icon -> icon.getGutterIconRenderer().getAccessibleTooltipText().endsWith(" · Added line."))
				.findFirst().orElseThrow();
		RangeHighlighter modified = icons.stream()
				.filter(icon -> icon.getGutterIconRenderer().getAccessibleTooltipText().endsWith(" · Modified line."))
				.findFirst().orElseThrow();
		assertNotSame("each kind has its own tint", added.getGutterIconRenderer().getIcon(),
				modified.getGutterIconRenderer().getIcon());
	}

	public void testTheKindOfAReasonComesFromTheSnapshotUnderIt() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		Instant now = Instant.now();
		// record lines are 1-based: 4 is <b>x</b>, changed, 6 is <c/>, added, 3 is <a>, unchanged
		loadEdits(record(now, "t1", "a.xml", 4, 4, "Changed member.") + record(now, "t2", "a.xml", 6, 6, "Added line.")
				+ record(now, "t3", "a.xml", 3, 3, "Unchanged line."));
		String before = "<?xml version=\"1.0\"?>\n<root>\n  <a>\n    <b>y</b>\n  </a>\n</root>\n";

		List<MemberReason> reasons = reasonsIn(file,
				new FileDiff(null, file.getPath(), null, FileStatus.MODIFIED, before, TEXT, 0, 0, null));

		assertEquals(ChangeKind.MODIFIED, kindOf(reasons, "Changed member."));
		assertEquals(ChangeKind.ADDED, kindOf(reasons, "Added line."));
		assertNull("code the snapshot doesn't change has no kind, so the gutter's pencil and the tree's mark are grey",
				kindOf(reasons, "Unchanged line."));
	}

	private List<MemberReason> reasonsIn(VirtualFile file, FileDiff diff) {
		Map<String, List<MemberReason>> members = ApplicationManager.getApplication().runReadAction(
				(Computable<Map<String, List<MemberReason>>>) () -> EditReasonMembers.prepare(getProject(), List.of(diff)).get());
		return members.get(file.getPath());
	}

	private static ChangeKind kindOf(List<MemberReason> reasons, String text) {
		return reasons.stream().filter(reason -> text.equals(reason.reason())).findFirst().orElseThrow().kind();
	}

	private static void waitForLoad() {
		for (int i = 0; i < 2; i++) {
			NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
			PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		}
	}
}
