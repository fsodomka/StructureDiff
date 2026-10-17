package com.fsodomka.structurediff;

import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Tree helpers shared by the tool windows. */
public final class Trees {
	private Trees() {
	}

	/**
	 * Expands every node now. {@link TreeUtil#expandAll(JTree)} expands later, in the background, so whatever reads the
	 * expanded rows right after it, such as restoring collapsed nodes or scrolling to a node, would see them collapsed.
	 */
	public static void expandAll(JTree tree) {
		if (tree instanceof Tree platformTree) {
			// one bulk expansion; expanding row by row is quadratic in the number of expanded nodes
			List<TreePath> paths = new ArrayList<>();
			Object root = tree.getModel().getRoot();
			if (root != null) {
				collectInner(tree.getModel(), new TreePath(root), paths);
			}
			platformTree.expandPaths(paths);
			return;
		}
		for (int row = 0; row < tree.getRowCount(); row++) {
			tree.expandRow(row);
		}
	}

	/** {@code path} and its descendants that are not leaves, parents first. */
	private static void collectInner(TreeModel model, TreePath path, List<TreePath> paths) {
		Object node = path.getLastPathComponent();
		if (model.isLeaf(node)) {
			return;
		}
		paths.add(path);
		for (int i = 0; i < model.getChildCount(node); i++) {
			collectInner(model, path.pathByAddingChild(model.getChild(node, i)), paths);
		}
	}

	/**
	 * The collapsed nodes under {@code root} that have children, each as the {@code keyPart}s of its user object and its
	 * ancestors' below {@code root}, for {@link #restoreCollapsed} in a rebuilt tree.
	 */
	public static Set<List<String>> collapsed(JTree tree, DefaultMutableTreeNode root, Function<Object, String> keyPart) {
		return collapsed(tree, root, keyPart, userObject -> false);
	}

	/**
	 * {@link #collapsed(JTree, DefaultMutableTreeNode, Function)} where the key of a node whose user object matches
	 * {@code keyStart} starts at that node, so it does not change when its ancestors do.
	 */
	public static Set<List<String>> collapsed(JTree tree, DefaultMutableTreeNode root, Function<Object, String> keyPart,
											  Predicate<Object> keyStart) {
		Set<List<String>> collapsed = new HashSet<>();
		forEachInner(root, List.of(), keyPart, keyStart, (node, key) -> {
			if (!tree.isExpanded(new TreePath(node.getPath()))) {
				collapsed.add(key);
			}
		});
		return collapsed;
	}

	/** Collapses the nodes of {@link #collapsed}; children before parents, since collapsing a path expands its ancestors. */
	public static void restoreCollapsed(JTree tree, DefaultMutableTreeNode root, Set<List<String>> collapsed,
										Function<Object, String> keyPart) {
		restoreCollapsed(tree, root, collapsed, keyPart, userObject -> false);
	}

	/** Collapses the nodes of {@link #collapsed(JTree, DefaultMutableTreeNode, Function, Predicate)}. */
	public static void restoreCollapsed(JTree tree, DefaultMutableTreeNode root, Set<List<String>> collapsed,
										Function<Object, String> keyPart, Predicate<Object> keyStart) {
		forEachInner(root, List.of(), keyPart, keyStart, (node, key) -> {
			if (collapsed.contains(key)) {
				tree.collapsePath(new TreePath(node.getPath()));
			}
		});
	}

	/** Calls {@code action} with each descendant of {@code node} that has children and its key, children before parents. */
	private static void forEachInner(DefaultMutableTreeNode node, List<String> key, Function<Object, String> keyPart,
									 Predicate<Object> keyStart, BiConsumer<DefaultMutableTreeNode, List<String>> action) {
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (child.isLeaf()) {
				continue;
			}
			List<String> childKey = keyStart.test(child.getUserObject()) ? new ArrayList<>() : new ArrayList<>(key);
			childKey.add(keyPart.apply(child.getUserObject()));
			forEachInner(child, childKey, keyPart, keyStart, action);
			action.accept(child, childKey);
		}
	}

	/** Calls {@code open} on double-click and Enter; it returns whether it opened something, which consumes the event. */
	public static void installOpenHandler(JTree tree, BooleanSupplier open) {
		new DoubleClickListener() {
			@Override
			protected boolean onDoubleClick(@NotNull MouseEvent event) {
				return open.getAsBoolean();
			}
		}.installOn(tree);
		tree.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER && open.getAsBoolean()) {
					e.consume();
				}
			}
		});
	}
}
