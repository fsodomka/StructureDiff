package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.reason.EditReasonsService;
import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class ReviewFindingsPanelTest extends BasePlatformTestCase {
	private static final String SARIF = """
			{"version": "2.1.0", "runs": [{"results": [
			  %s, %s, %s
			]}]}
			""".formatted(
			result("CORRECTNESS-1", "A.txt", 1, 0, "class A {"),
			result("CORRECTNESS-2", "A.txt", 2, 11, "int ttl = 5;"),
			result("CORRECTNESS-3", "B.txt", 1, 0, "class B {"));

	private static String result(String ruleId, String path, int line, int offset, String snippet) {
		return """
				{"ruleId": "%s", "message": {"text": "%s"}, "locations": [{"physicalLocation": {
				  "artifactLocation": {"uri": "%s"},
				  "region": {"startLine": %d, "charOffset": %d, "charLength": %d, "snippet": {"text": "%s"}}}}]}
				""".formatted(ruleId, ruleId, path, line, offset, snippet.length(), snippet);
	}

	private ReviewFindingsService service;
	private Tree tree;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		service = ReviewFindingsService.getInstance(getProject());
		service.loadState(new ReviewFindingsService.State());
		myFixture.addFileToProject("A.txt", "class A {\n\tint ttl = 5;\n}\n");
		myFixture.addFileToProject("B.txt", "class B {\n}\n");
		VirtualFile review = myFixture.addFileToProject(StructureDiffSettings.DEFAULT_REVIEWS_DIR + "/20261015-120000-review.sarif", SARIF)
				.getVirtualFile();
		service.load(review, true, false);
		waitForLoad();
		Content content = ContentFactory.getInstance().createContent(null, "", false);
		ReviewFindingsPanel panel = new ReviewFindingsPanel(getProject(), content);
		Disposer.register(getTestRootDisposable(), panel);
		tree = UIUtil.findComponentOfType(panel, Tree.class);
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			waitForLoad();
			service.clearAll();
			service.loadState(new ReviewFindingsService.State());
		} finally {
			super.tearDown();
		}
	}

	private static void waitForLoad() {
		NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
	}

	private TreePath path(Predicate<Object> userObject) {
		for (int row = 0; row < tree.getRowCount(); row++) {
			TreePath path = tree.getPathForRow(row);
			if (userObject.test(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())) {
				return path;
			}
		}
		throw new AssertionError("no visible node");
	}

	private TreePath finding(String ruleId) {
		return path(o -> o instanceof Placement placement && placement.finding().ruleId().equals(ruleId));
	}

	private TreePath file(String path) {
		return path(o -> o.toString().startsWith("FileGroup[path=" + path + ","));
	}

	/** A file of the Dismissed section; the sections cannot be collapsed, since the tree shows no root handles. */
	private TreePath dismissedFile(String path) {
		for (int row = 0; row < tree.getRowCount(); row++) {
			TreePath file = tree.getPathForRow(row);
			if (file.getPathCount() == 3 && file.getPathComponent(1).toString().startsWith("Section[title=Dismissed,")
					&& file.getLastPathComponent().toString().startsWith("FileGroup[path=" + path + ",")) {
				return file;
			}
		}
		throw new AssertionError("no visible node");
	}

	private Finding findingOf(TreePath path) {
		return ((Placement) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()).finding();
	}

	private List<String> selectedRuleIds() {
		TreePath[] paths = tree.getSelectionPaths();
		return paths == null ? List.of() : Arrays.stream(paths).map(path -> findingOf(path).ruleId()).toList();
	}

	private static String edit(String toolUseId, String file, int start, int end, String time) {
		return """
				{"session":"s","time":"%s","turn":"t","agent":null,"tool":"Edit","toolUseId":"%s","file":"%s",\
				"lines":[%d,%d],"reason":"One purpose.","source":"agent"}
				""".formatted(time, toolUseId, file, start, end);
	}

	/** The text of the tree's rows that are places of a reason, the 5th level: root, section, run, group, place. */
	private List<String> reasonPlaces() {
		// twice: the second pass reaches nodes the first brought into view
		TreeUtil.expandAll(tree);
		TreeUtil.expandAll(tree);
		List<String> places = new ArrayList<>();
		for (int row = 0; row < tree.getRowCount(); row++) {
			TreePath path = tree.getPathForRow(row);
			if (path.getPathCount() == 5) {
				places.add(rowText(row));
			}
		}
		return places;
	}

	private String rowText(int row) {
		return tree.getCellRenderer().getTreeCellRendererComponent(tree, tree.getPathForRow(row).getLastPathComponent(),
				false, true, true, row, false).toString();
	}

	/**
	 * One reason's records as one agent wrote them: a.txt 8-9, a.txt 2-3, b.txt 5, then a.txt 2 again (inside 2-3) and
	 * a.txt 4 (touching it). The gutter draws a.txt as two places, and so must the list: by file in the order of the
	 * first edit, by line inside a file, one row per merged place.
	 */
	public void testAReasonsPlacesAreMergedAndOrderedByFileThenLine() throws Exception {
		String text = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
		VirtualFile a = myFixture.addFileToProject("a.java", text).getVirtualFile();
		VirtualFile b = myFixture.addFileToProject("b.java", text).getVirtualFile();
		String jsonl = edit("t1", "a.java", 8, 9, "2026-11-19T12:00:01Z") + edit("t2", "a.java", 2, 3, "2026-11-19T12:00:02Z")
				+ edit("t3", "b.java", 5, 5, "2026-11-19T12:00:03Z") + edit("t4", "a.java", 2, 2, "2026-11-19T12:00:04Z")
				+ edit("t5", "a.java", 4, 4, "2026-11-19T12:00:05Z");
		EditReasonsService reasons = EditReasonsService.getInstance(getProject());
		try {
			reasons.snapshotChanged(List.of());
			VirtualFile edits = myFixture.addFileToProject("reviews/edits/session.jsonl", jsonl).getVirtualFile();
			WriteCommandAction.runWriteCommandAction(getProject(), () -> {
				try {
					edits.setBinaryContent(jsonl.getBytes());
					// the test file system counts timestamps from 0, which the service would skip as long ago
					PersistentFS.getInstance().setTimeStamp(edits, Instant.now().toEpochMilli());
				}
				catch (java.io.IOException e) {
					throw new RuntimeException(e);
				}
			});
			reasons.load();
			waitForLoad();
			reasons.snapshotChanged(List.of(
					new FileDiff(null, a.getPath(), null, FileStatus.MODIFIED, "old\n", text, 0, 0, null),
					new FileDiff(null, b.getPath(), null, FileStatus.MODIFIED, "old\n", text, 0, 0, null)));
			waitForLoad();

			assertEquals(List.of("a.java:2", "a.java:8", "b.java:5"), reasonPlaces());
			// the section and the run count places (3), not records (5)
			List<String> counted = new ArrayList<>();
			for (int row = 0; row < tree.getRowCount(); row++) {
				if (tree.getPathForRow(row).getPathCount() <= 3 && rowText(row).endsWith("  3")) {
					counted.add(rowText(row));
				}
			}
			assertEquals(counted.toString(), 2, counted.size());
		}
		finally {
			waitForLoad();
			reasons.dispose();
		}
	}

	public void testDismissKeepsCollapsedNodesAndSelection() {
		tree.collapsePath(file("B.txt"));
		tree.setSelectionPath(finding("CORRECTNESS-2"));

		service.dismiss(findingOf(finding("CORRECTNESS-1")));

		assertTrue(tree.isCollapsed(file("B.txt")));
		assertEquals(List.of("CORRECTNESS-2"), selectedRuleIds());
		// the Dismissed section had no findings before, so it is expanded
		assertTrue(tree.isVisible(finding("CORRECTNESS-1")));
	}

	public void testSelectionFollowsARestoredFinding() {
		service.dismiss(findingOf(finding("CORRECTNESS-3")));
		tree.setSelectionPath(finding("CORRECTNESS-3"));

		service.restore(findingOf(finding("CORRECTNESS-3")));

		assertEquals(List.of("CORRECTNESS-3"), selectedRuleIds());
	}

	public void testFindingMovedUnderACollapsedNodeIsNotSelected() {
		service.dismiss(findingOf(finding("CORRECTNESS-2")));
		TreePath dismissedA = dismissedFile("A.txt");
		tree.collapsePath(dismissedA);
		assertTrue(tree.isCollapsed(dismissedA));
		tree.setSelectionPath(finding("CORRECTNESS-1"));

		service.dismiss(findingOf(finding("CORRECTNESS-1")));

		assertEquals(List.of(), selectedRuleIds());
		assertTrue(tree.isCollapsed(dismissedFile("A.txt")));
	}
}
