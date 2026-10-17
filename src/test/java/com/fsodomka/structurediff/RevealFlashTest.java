package com.fsodomka.structurediff;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;
import java.util.List;

public class RevealFlashTest extends BasePlatformTestCase {
	private JTree tree;
	private DefaultMutableTreeNode folded;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		for (int i = 0; i < 50; i++) {
			root.add(new DefaultMutableTreeNode("row " + i));
		}
		folded = new DefaultMutableTreeNode("folded");
		folded.add(new DefaultMutableTreeNode("hidden"));
		root.add(folded);
		tree = new JTree(root);
		tree.setRootVisible(false);
		tree.setRowHeight(20);
		JScrollPane scrollPane = new JScrollPane(tree);
		scrollPane.setSize(200, 100);
		// not displayed, so validate() would not lay it out
		scrollPane.doLayout();
		scrollPane.getViewport().doLayout();
	}

	private TreePath row(int index) {
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
		return new TreePath(((DefaultMutableTreeNode) root.getChildAt(index)).getPath());
	}

	public void testScrollsToTopmostFlashBelowView() {
		Rectangle visible = tree.getVisibleRect();
		assertEquals(0, visible.y);
		assertTrue(visible.height > 0 && visible.height < 600);
		StructureDiffPanel.revealFlash(tree, List.of(row(40), row(30)));
		assertTrue(tree.getVisibleRect().intersects(tree.getPathBounds(row(30))));
		assertFalse(tree.getVisibleRect().intersects(tree.getPathBounds(row(40))));
	}

	public void testKeepsViewWhenAFlashIsVisible() {
		StructureDiffPanel.revealFlash(tree, List.of(row(40), row(1)));
		assertEquals(0, tree.getVisibleRect().y);
	}

	public void testRevealsFlashInNewTreeExpandedByExpandAll() {
		// a fresh root, as show() sets it: its files are collapsed until expanded
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		for (int i = 0; i < 30; i++) {
			DefaultMutableTreeNode file = new DefaultMutableTreeNode("file " + i);
			file.add(new DefaultMutableTreeNode("member " + i));
			root.add(file);
		}
		tree.setModel(new DefaultTreeModel(root));
		TreePath member = new TreePath(((DefaultMutableTreeNode) root.getChildAt(25).getChildAt(0)).getPath());

		Trees.expandAll(tree);
		assertTrue(tree.isVisible(member));
		// not displayed, so the viewport does not take the expanded size by itself
		tree.getParent().getParent().doLayout();
		((JViewport) tree.getParent()).doLayout();
		StructureDiffPanel.revealFlash(tree, List.of(member));

		assertTrue(tree.getVisibleRect().intersects(tree.getPathBounds(member)));
	}

	public void testIgnoresFlashUnderCollapsedNode() {
		TreePath hidden = new TreePath(((DefaultMutableTreeNode) folded.getChildAt(0)).getPath());
		StructureDiffPanel.revealFlash(tree, List.of(hidden));
		assertEquals(0, tree.getVisibleRect().y);
		assertFalse(tree.isExpanded(new TreePath(folded.getPath())));
	}
}
