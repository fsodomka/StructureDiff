package com.fsodomka.structurediff;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Collapsed nodes stay collapsed when the tree is rebuilt with other repositories' changes. */
public class CollapsedRepoTest extends BasePlatformTestCase {
	private static final Predicate<Object> IS_FILE = userObject -> userObject.toString().endsWith(".java");

	private Tree tree;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode("")));
		tree.setRootVisible(false);
		// as DefaultTreeUI sets it; without it Tree does not collapse top-level nodes
		tree.setShowsRootHandles(true);
	}

	/** Files of repo A and, when {@code bMembers} > 0, repo B; grouped by repo only when both have files. */
	private static DefaultMutableTreeNode build(int bMembers) {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("");
		boolean grouped = bMembers > 0;
		for (String repo : grouped ? List.of("A", "B") : List.of("A")) {
			DefaultMutableTreeNode file = new DefaultMutableTreeNode(repo + "/F.java");
			int members = repo.equals("B") ? bMembers : 2;
			for (int i = 0; i < members; i++) {
				file.add(new DefaultMutableTreeNode(repo + "#m" + i));
			}
			if (grouped) {
				DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode("repo:" + repo);
				repoNode.add(file);
				root.add(repoNode);
			}
			else {
				root.add(file);
			}
		}
		return root;
	}

	/** Rebuilds the tree as StructureDiffPanel.show() does. */
	private void show(int bMembers) {
		DefaultMutableTreeNode oldRoot = (DefaultMutableTreeNode) tree.getModel().getRoot();
		boolean firstShow = oldRoot.getChildCount() == 0;
		Set<List<String>> collapsed = Trees.collapsed(tree, oldRoot, String::valueOf, IS_FILE);
		DefaultMutableTreeNode root = build(bMembers);
		((DefaultTreeModel) tree.getModel()).setRoot(root);
		Trees.expandAll(tree);
		if (!firstShow) {
			Trees.restoreCollapsed(tree, root, collapsed, String::valueOf, IS_FILE);
		}
	}

	private TreePath path(String... userObjects) {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getModel().getRoot();
		TreePath path = new TreePath(node);
		for (String userObject : userObjects) {
			DefaultMutableTreeNode parent = node;
			for (int i = 0; i < parent.getChildCount(); i++) {
				DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
				if (child.getUserObject().equals(userObject)) {
					node = child;
				}
			}
			assertNotSame(userObject, parent, node);
			path = path.pathByAddingChild(node);
		}
		return path;
	}

	public void testCollapsedRepoStaysCollapsedAfterEditInOtherRepo() {
		show(2);
		tree.collapsePath(path("repo:A"));

		show(3);
		assertFalse(tree.isExpanded(path("repo:A")));
		show(4);
		assertFalse(tree.isExpanded(path("repo:A")));
	}

	public void testCollapsedFileStaysCollapsedWhenGroupingByRepoStartsAndStops() {
		show(0);
		tree.collapsePath(path("A/F.java"));

		show(1);
		assertTrue(tree.isExpanded(path("repo:A")));
		assertFalse(tree.isExpanded(path("repo:A", "A/F.java")));
		show(0);
		assertFalse(tree.isExpanded(path("A/F.java")));
	}
}
