package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.StructureDiffSettings;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ReviewFindingsServiceTest extends BasePlatformTestCase {
	private static final String TEXT = "class A {\n\tint ttl = 5;\n}\n";
	/** Two class-level findings of one category on the declaration line share a fingerprint; the third does not. */
	private static final String SARIF = """
			{"version": "2.1.0", "runs": [{"results": [
			  %s, %s, %s
			]}]}
			""".formatted(
			result("CORRECTNESS-1", "First on the class.", 1, 0, "class A {"),
			result("CORRECTNESS-2", "Second on the class.", 1, 0, "class A {"),
			result("CORRECTNESS-3", "On the field.", 2, 11, "int ttl = 5;"));

	private static String result(String ruleId, String message, int line, int offset, String snippet) {
		return """
				{"ruleId": "%s", "message": {"text": "%s"}, "locations": [{"physicalLocation": {
				  "artifactLocation": {"uri": "A.txt"},
				  "region": {"startLine": %d, "charOffset": %d, "charLength": %d, "snippet": {"text": "%s"}}}}]}
				""".formatted(ruleId, message, line, offset, snippet.length(), snippet);
	}

	private ReviewFindingsService service;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		service = ReviewFindingsService.getInstance(getProject());
		service.loadState(new ReviewFindingsService.State());
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			// the listener's loads of files created by the test must not finish after the light project is torn down
			waitForLoad();
			service.clearAll();
			service.loadState(new ReviewFindingsService.State());
			StructureDiffSettings.getInstance().loadState(new StructureDiffSettings.State());
		} finally {
			super.tearDown();
		}
	}

	private List<String> highlighted() {
		return service.placements().stream().map(placement -> placement.finding().title()).toList();
	}

	private List<String> dismissed() {
		return service.dismissedPlacements().stream().map(placement -> placement.finding().title()).toList();
	}

	public void testDismissAndRestoreActOnAllFindingsWithTheFingerprint() {
		myFixture.addFileToProject("A.txt", TEXT);
		VirtualFile review = myFixture.addFileToProject(StructureDiffSettings.DEFAULT_REVIEWS_DIR + "/20261015-120000-review.sarif", SARIF)
				.getVirtualFile();
		service.load(review, true, false);
		waitForLoad();
		assertEquals(List.of("First on the class.", "Second on the class.", "On the field."), highlighted());

		Finding first = service.placements().iterator().next().finding();
		service.dismiss(first);
		assertEquals(List.of("On the field."), highlighted());
		assertEquals(List.of("First on the class.", "Second on the class."), dismissed());

		service.restore(first);
		assertEquals(List.of("On the field.", "First on the class.", "Second on the class."), highlighted());
		assertTrue(dismissed().isEmpty());
	}

	/** Loads an older and a newer review and shows the newer one. */
	private VirtualFile showNewerOfTwoReviews() {
		myFixture.addFileToProject("A.txt", TEXT);
		myFixture.addFileToProject(StructureDiffSettings.DEFAULT_REVIEWS_DIR + "/20261015-110000-review.sarif",
				"""
				{"version": "2.1.0", "runs": [{"results": [%s]}]}
				""".formatted(result("CORRECTNESS-1", "From the older review.", 2, 11, "int ttl = 5;")));
		VirtualFile newer = myFixture.addFileToProject(StructureDiffSettings.DEFAULT_REVIEWS_DIR + "/20261015-120000-review.sarif", SARIF)
				.getVirtualFile();
		service.load(newer, true, false);
		waitForLoad();
		assertEquals(3, highlighted().size());
		return newer;
	}

	private void assertOlderReviewShown() {
		waitForLoad();
		assertEquals(List.of("From the older review."), highlighted());
		assertEquals("20261015-110000-review.sarif", service.reviews().iterator().next().file().getName());
	}

	// the listener sees the VFS events

	public void testDeletingTheShownReviewShowsThePreviousOne() throws IOException {
		VirtualFile newer = showNewerOfTwoReviews();
		WriteAction.run(() -> newer.delete(this));
		assertOlderReviewShown();
	}

	public void testRenamingTheShownReviewAwayShowsThePreviousOne() throws IOException {
		VirtualFile newer = showNewerOfTwoReviews();
		WriteAction.run(() -> newer.rename(this, "20261015-120000-review.txt"));
		assertOlderReviewShown();
	}

	public void testMovingTheShownReviewAwayShowsThePreviousOne() throws IOException {
		VirtualFile newer = showNewerOfTwoReviews();
		WriteAction.run(() -> newer.move(this, newer.getParent().getParent()));
		assertOlderReviewShown();
	}

	public void testRenamingTheShownReviewToAnotherReviewNameKeepsIt() throws IOException {
		VirtualFile newer = showNewerOfTwoReviews();
		WriteAction.run(() -> newer.rename(this, "20261015-130000-review.sarif"));
		waitForLoad();
		assertEquals(3, highlighted().size());
		assertEquals("20261015-130000-review.sarif", service.reviews().iterator().next().file().getName());
	}

	/** A review file with one finding on the field. */
	private VirtualFile review(String name, String message) {
		return myFixture.addFileToProject(StructureDiffSettings.DEFAULT_REVIEWS_DIR + "/" + name,
				"""
				{"version": "2.1.0", "runs": [{"results": [%s]}]}
				""".formatted(result("CORRECTNESS-1", message, 2, 11, "int ttl = 5;"))).getVirtualFile();
	}

	private String shownReview() {
		assertEquals(1, service.reviews().size());
		return service.reviews().iterator().next().file().getName();
	}

	public void testOlderReviewReplacesNewerOnlyWhenAsked() {
		myFixture.addFileToProject("A.txt", TEXT);
		VirtualFile older = review("20261015-110000-review.sarif", "Older.");
		VirtualFile newer = review("20261015-120000-review.sarif", "Newer.");
		service.load(newer, false, false);
		waitForLoad();

		// a late event for an older file must not hide the newer review
		service.load(older, false, false);
		waitForLoad();
		assertEquals("20261015-120000-review.sarif", shownReview());
		assertEquals(List.of("Newer."), highlighted());

		// as when the user opens an older review
		service.load(older, true, false);
		waitForLoad();
		assertEquals("20261015-110000-review.sarif", shownReview());
		assertEquals(List.of("Older."), highlighted());
	}

	public void testClearAllIsRememberedUntilReload() {
		myFixture.addFileToProject("A.txt", TEXT);
		VirtualFile review = review("20261015-120000-review.sarif", "Newer.");
		service.loadNewest();
		waitForLoad();
		assertEquals("20261015-120000-review.sarif", shownReview());

		service.clearAll();
		assertTrue(service.reviews().isEmpty());
		assertTrue(service.placements().isEmpty());
		assertEquals(Set.of(review.getPath()), service.getState().cleared);

		// as on the next start: the persisted state keeps the cleared review away
		ReviewFindingsService.State persisted = new ReviewFindingsService.State();
		persisted.cleared.addAll(service.getState().cleared);
		service.loadState(persisted);
		service.loadNewest();
		waitForLoad();
		assertTrue(service.reviews().isEmpty());

		service.reload();
		waitForLoad();
		assertEquals("20261015-120000-review.sarif", shownReview());
		assertTrue(service.getState().cleared.isEmpty());
	}

	public void testClearedReviewIsShownWhenANewerOneAppears() {
		myFixture.addFileToProject("A.txt", TEXT);
		review("20261015-110000-review.sarif", "Older.");
		service.loadNewest();
		waitForLoad();
		service.clearAll();

		// the listener loads the new file
		review("20261015-120000-review.sarif", "Newer.");
		waitForLoad();
		assertEquals("20261015-120000-review.sarif", shownReview());
	}

	public void testLoadsOfOneFileAreCoalesced() throws IOException {
		myFixture.addFileToProject("A.txt", TEXT);
		VirtualFile review = review("20261015-120000-review.sarif", "First write.");
		waitForLoad();
		AtomicInteger applied = new AtomicInteger();
		getProject().getMessageBus().connect(getTestRootDisposable()).<ReviewFindingsListener>subscribe(ReviewFindingsListener.TOPIC,
				() -> applied.incrementAndGet());

		// as for a create and a content change in a row: only the last parse, of the last content, is applied
		service.load(review, false, false);
		WriteAction.run(() -> VfsUtil.saveText(review, SARIF));
		service.load(review, false, false);
		waitForLoad();
		assertEquals(1, applied.get());
		assertEquals(List.of("First on the class.", "Second on the class.", "On the field."), highlighted());
	}

	public void testDeletingTheReviewedCodeMarksTheFindingGone() {
		VirtualFile a = myFixture.addFileToProject("A.txt", TEXT).getVirtualFile();
		service.load(review("20261015-120000-review.sarif", "On the field."), false, false);
		waitForLoad();
		Document document = FileDocumentManager.getInstance().getDocument(a);
		AtomicInteger changes = new AtomicInteger();
		getProject().getMessageBus().connect(getTestRootDisposable()).<ReviewFindingsListener>subscribe(ReviewFindingsListener.TOPIC,
				() -> changes.incrementAndGet());

		// an edit elsewhere keeps it
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "// header\n"));
		assertEquals(ReviewAnchor.Status.EXACT, service.placements().iterator().next().status());
		assertEquals(0, changes.get());

		int start = document.getText().indexOf("int ttl = 5;");
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.deleteString(start, start + "int ttl = 5;".length()));
		Placement placement = service.placements().iterator().next();
		assertEquals(ReviewAnchor.Status.GONE, placement.status());
		assertNull(placement.highlighter());
		assertEquals(0, DocumentMarkupModel.forDocument(document, getProject(), true).getAllHighlighters().length);
		assertEquals(1, changes.get());
	}

	public void testDeletingTheReviewedFileMarksTheFindingMissing() throws IOException {
		VirtualFile a = myFixture.addFileToProject("A.txt", TEXT).getVirtualFile();
		service.load(review("20261015-120000-review.sarif", "On the field."), false, false);
		waitForLoad();
		assertEquals(ReviewAnchor.Status.EXACT, service.placements().iterator().next().status());

		// the listener sees the deletion
		WriteAction.run(() -> a.delete(this));
		Placement placement = service.placements().iterator().next();
		assertEquals(ReviewAnchor.Status.MISSING_FILE, placement.status());
		assertNull(placement.highlighter());
	}

	public void testUndoingTheDeletionPlacesTheFindingAgain() {
		VirtualFile a = myFixture.addFileToProject("A.txt", TEXT).getVirtualFile();
		service.load(review("20261015-120000-review.sarif", "On the field."), false, false);
		waitForLoad();
		Document document = FileDocumentManager.getInstance().getDocument(a);
		int start = document.getText().indexOf("int ttl = 5;");
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.deleteString(start, start + "int ttl = 5;".length()));
		assertEquals(ReviewAnchor.Status.GONE, service.placements().iterator().next().status());

		// retyped in parts: only the whole snippet brings it back
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(start, "int ttl"));
		assertEquals(ReviewAnchor.Status.GONE, service.placements().iterator().next().status());
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(start + "int ttl".length(), " = 5;"));

		Placement placement = service.placements().iterator().next();
		assertEquals(ReviewAnchor.Status.EXACT, placement.status());
		assertNotNull(placement.highlighter());
		assertEquals("int ttl = 5;", document.getText(placement.highlighter().getTextRange()));
		assertEquals(1, DocumentMarkupModel.forDocument(document, getProject(), true).getAllHighlighters().length);
	}

	public void testSnippetTypedFarAwayDoesNotPlaceTheFindingAgain() {
		VirtualFile a = myFixture.addFileToProject("A.txt", TEXT).getVirtualFile();
		service.load(review("20261015-120000-review.sarif", "On the field."), false, false);
		waitForLoad();
		Document document = FileDocumentManager.getInstance().getDocument(a);
		int start = document.getText().indexOf("int ttl = 5;");
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.deleteString(start, start + "int ttl = 5;".length()));

		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(document.getTextLength(),
				"\n".repeat(60) + "int ttl = 5;\nint ttl = 5;\n"));

		Placement placement = service.placements().iterator().next();
		assertEquals(ReviewAnchor.Status.GONE, placement.status());
		assertNull(placement.highlighter());
	}

	public void testIsReviewFile() {
		assertTrue(service.isReviewFile(review("20261015-120000-review.sarif", "M.")));
		assertTrue(service.isReviewFile(review("20261015-120000-review.sarif.json", "M.")));
		assertFalse(service.isReviewFile(review("20261015-120000-review.json", "M.")));
		assertFalse(service.isReviewFile(review("nested/20261015-120000-review.sarif", "M.")));
		assertFalse(service.isReviewFile(myFixture.addFileToProject("other/20261015-120000-review.sarif", "{}").getVirtualFile()));
	}

	public void testIsReviewFileInANestedReviewsDirectory() {
		StructureDiffSettings.getInstance().getState().reviewsDir = "build/reviews";
		assertTrue(service.isReviewFile(myFixture.addFileToProject("build/reviews/20261015-120000-review.sarif", "{}").getVirtualFile()));
		assertFalse(service.isReviewFile(review("20261015-120000-review.sarif", "M.")));
		assertFalse(service.isReviewFile(myFixture.addFileToProject("other/reviews/20261015-120000-review.sarif", "{}").getVirtualFile()));
	}

	public void testChangingTheReviewsDirectoryShowsItsNewestReview() {
		myFixture.addFileToProject("A.txt", TEXT);
		service.load(review("20261015-120000-review.sarif", "From reviews."), false, false);
		waitForLoad();
		myFixture.addFileToProject("ai/20261015-110000-review.sarif", """
				{"version": "2.1.0", "runs": [{"results": [%s]}]}
				""".formatted(result("CORRECTNESS-1", "From ai.", 2, 11, "int ttl = 5;")));
		waitForLoad();
		assertEquals(List.of("From reviews."), highlighted());

		StructureDiffSettings.getInstance().getState().reviewsDir = "ai";
		service.reviewsDirChanged();
		waitForLoad();
		assertEquals(List.of("From ai."), highlighted());
	}

	public void testOpensAnExternalReviewAndKeepsItUntilDeleted() throws IOException {
		myFixture.addFileToProject("A.txt", TEXT);
		VirtualFile external = myFixture.addFileToProject("elsewhere/qodana.sarif.json", SARIF).getVirtualFile();
		assertFalse(service.isReviewFile(external));

		assertTrue(service.loadExternal(external));
		waitForLoad();
		assertEquals(3, highlighted().size());
		assertTrue(service.reviews().iterator().next().external());

		// not a review file of its root, which drops a watched review
		service.dropRemoved();
		assertEquals(3, highlighted().size());

		WriteAction.run(() -> external.delete(this));
		waitForLoad();
		assertTrue(service.reviews().isEmpty());
		assertTrue(highlighted().isEmpty());
	}

	public void testChangingTheReviewsDirectoryKeepsClearedReviewsCleared() {
		myFixture.addFileToProject("A.txt", TEXT);
		service.load(review("20261015-120000-review.sarif", "From reviews."), false, false);
		waitForLoad();
		service.clearAll();

		// the same directory, written differently: the cleared review stays cleared
		StructureDiffSettings.getInstance().getState().reviewsDir = "reviews/";
		service.reviewsDirChanged();
		waitForLoad();
		assertTrue(highlighted().isEmpty());
	}

	public void testANewReviewReplacesAnExternalOne() {
		myFixture.addFileToProject("A.txt", TEXT);
		// sorts after every timestamped review name
		VirtualFile external = myFixture.addFileToProject("elsewhere/qodana.sarif.json", SARIF).getVirtualFile();
		assertTrue(service.loadExternal(external));
		waitForLoad();

		// written by the skill: the listener loads it without replacing newer reviews
		review("20261015-120000-review.sarif", "From the skill.");
		waitForLoad();
		assertEquals(List.of("From the skill."), highlighted());
		assertFalse(service.reviews().iterator().next().external());
	}

	/** Twice: loading the newest review lists the reviews directory in the background, then parses the file it finds. */
	private static void waitForLoad() {
		for (int i = 0; i < 2; i++) {
			NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
			PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		}
	}
}
