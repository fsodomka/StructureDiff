package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.StructureDiffSettings;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
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
