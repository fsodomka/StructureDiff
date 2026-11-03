package com.fsodomka.structurediff;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;

/** A double-click opens a node without folding it, and folds only a node that opens nothing. */
public class TreesOpenHandlerTest extends BasePlatformTestCase {
	private Tree tree;
	private TreePath opensPath;
	private TreePath groupPath;
	private int opened;
	private long when = System.currentTimeMillis();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
		DefaultMutableTreeNode opens = new DefaultMutableTreeNode("opens");
		opens.add(new DefaultMutableTreeNode("member"));
		DefaultMutableTreeNode group = new DefaultMutableTreeNode("group");
		group.add(new DefaultMutableTreeNode("child"));
		root.add(opens);
		root.add(group);
		tree = new Tree(new DefaultTreeModel(root));
		tree.setRootVisible(false);
		// as DefaultTreeUI sets it; without it Tree does not collapse top-level nodes
		tree.setShowsRootHandles(true);
		opensPath = new TreePath(new Object[]{root, opens});
		groupPath = new TreePath(new Object[]{root, group});
		Trees.installOpenHandler(tree, () -> {
			if (tree.getSelectionPath() != null && tree.getSelectionPath().getLastPathComponent() == opens) {
				opened++;
				return true;
			}
			return false;
		});
		tree.expandPath(opensPath);
		tree.expandPath(groupPath);
		tree.setBounds(0, 0, 300, 300);
		tree.doLayout();
	}

	private void doubleClick(TreePath path) {
		tree.setSelectionPath(path);
		Rectangle bounds = tree.getPathBounds(path);
		int x = bounds.x + bounds.width / 2;
		int y = bounds.y + bounds.height / 2;
		// past the multi-click interval, so ClickListener does not count this as the third and fourth click
		when += 10_000;
		for (int clickCount = 1; clickCount <= 2; clickCount++) {
			for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
				tree.dispatchEvent(new MouseEvent(tree, id, when, InputEvent.BUTTON1_DOWN_MASK, x, y,
						clickCount, false, MouseEvent.BUTTON1));			}
		}
	}

	public void testDoubleClickOpensWithoutFolding() {
		doubleClick(opensPath);
		assertEquals(1, opened);
		assertTrue(tree.isExpanded(opensPath));
	}

	public void testDoubleClickFoldsANodeThatOpensNothing() {
		doubleClick(groupPath);
		assertEquals(0, opened);
		assertFalse(tree.isExpanded(groupPath));
		doubleClick(groupPath);
		assertTrue(tree.isExpanded(groupPath));
	}
}
