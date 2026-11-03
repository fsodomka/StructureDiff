package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiffTreeNodes.Ghost;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Flashes the nodes of the Structure Diff tree and treemap that appeared, changed or disappeared in a refresh, fading
 * them out over a few seconds; removed nodes stay as {@link DiffTreeNodes.Ghost}s until their flash ends.
 */
final class ChangeFlashes {
	private static final long FLASH_MILLIS = 4000;
	/** How long a node that appeared takes to grow from its center to full size in the treemap. */
	static final long GROW_MILLIS = 250;
	/** The fade's frame time; a grow runs at {@link #GROW_FRAME_MILLIS}, as a few frames in a quarter second look choppy. */
	private static final int FADE_FRAME_MILLIS = 50;
	private static final int GROW_FRAME_MILLIS = 16;
	/**
	 * Recent changes by node key. Nodes that appeared or changed get a background that fades out;
	 * nodes that disappeared stay in the tree as {@link Ghost}s until their flash ends. Each node has its own flash:
	 * content changes to a flashing node do not restart it, while other nodes flash independently; a status change or
	 * removal always flashes.
	 */
	private final Map<List<String>, Flash> flashes = new HashMap<>();
	/** Whether a refresh result with {@link com.fsodomka.structurediff.source.ChangeSource#highlightChanges()} was shown; it is the initial state, not changes to flash, even if a later one starts from an empty tree. */
	private boolean baselineShown;
	private final Timer fadeTimer = new Timer(GROW_FRAME_MILLIS, e -> step());
	/** Repaints the tree and treemap while flashes fade or grow; rebuilds the tree when a ghost's flash ended. */
	private final Runnable repaint;
	private final Runnable rebuild;
	/** Whether the frames are fast enough for growing boxes: while the treemap shows. */
	private final BooleanSupplier fastFrames;

	ChangeFlashes(Runnable repaint, Runnable rebuild, BooleanSupplier fastFrames) {
		this.repaint = repaint;
		this.rebuild = rebuild;
		this.fastFrames = fastFrames;
	}

	/** The running flash of {@code node}, or null. */
	@Nullable Flash get(DefaultMutableTreeNode node) {
		return flashes.isEmpty() ? null : flashes.get(DiffTreeNodes.keyOf(node));
	}

	/** Drops the running flashes and their ghosts; the next rebuild shows the tree without them. */
	void clear() {
		flashes.clear();
	}

	void stop() {
		fadeTimer.stop();
	}

	/**
	 * Updates the flashes for the rebuilt tree {@code root}: drops the ended ones, starts those of the changes since
	 * {@code oldNodes} when {@code highlightChanges} and {@code enabled}, and inserts the ghosts of removed nodes.
	 * Returns the keys of the started flashes.
	 */
	Set<List<String>> update(Map<List<String>, DefaultMutableTreeNode> oldNodes, Map<List<String>, DefaultMutableTreeNode> newNodes,
			boolean highlightChanges, boolean enabled, DefaultMutableTreeNode root, Map<VirtualFile, DefaultMutableTreeNode> repoNodes,
			Map<String, VirtualFile> vcsRoots) {
		long now = System.currentTimeMillis();
		// a flash that ended is dropped before new ones start, so its node can flash again
		flashes.entrySet().removeIf(e -> e.getValue().expired(now) || e.getValue().vanished() != null && newNodes.containsKey(e.getKey()));
		Map<List<String>, Flash> started = Map.of();
		if (highlightChanges && baselineShown && enabled) {
			started = newFlashes(oldNodes, newNodes, flashes.keySet(), now);
			flashes.putAll(started);
		}
		baselineShown |= highlightChanges;
		insertGhosts(root, newNodes, repoNodes, vcsRoots);
		if (flashes.isEmpty()) {
			fadeTimer.stop();
		}
		else if (!fadeTimer.isRunning()) {
			fadeTimer.start();
		}
		return started.keySet();
	}

	/**
	 * A recent change of the node at a key. {@code vanished} is the user object of a node that disappeared,
	 * shown at {@code index} of its parent until the flash ends; null for a node that appeared or changed.
	 * {@code appeared} tells a node that was not there before, which grows in the treemap, from one that changed.
	 * Package-private for the test of {@link #newFlashes}.
	 */
	record Flash(FileStatus status, boolean appeared, Object vanished, int index, long start) {
		/** 1 for the first half of the flash, then fading to 0. */
		double strength(long now) {
			double remaining = 1 - (double) (now - start) / FLASH_MILLIS;
			return Math.clamp(remaining * 2, 0, 1);
		}

		boolean expired(long now) {
			return now - start >= FLASH_MILLIS;
		}

		/** The size of the node's box in the treemap, from 0 to 1, easing out over {@link #GROW_MILLIS}; 1 unless it appeared. */
		double grown(long now) {
			if (!appeared) {
				return 1;
			}
			double t = Math.clamp((double) (now - start) / GROW_MILLIS, 0, 1);
			return 1 - Math.pow(1 - t, 3);
		}
	}

	private void step() {
		long now = System.currentTimeMillis();
		boolean ghostExpired = flashes.values().stream().anyMatch(f -> f.vanished() != null && f.expired(now));
		flashes.values().removeIf(f -> f.expired(now));
		if (flashes.isEmpty()) {
			fadeTimer.stop();
		}
		boolean growing = flashes.values().stream().anyMatch(f -> f.grown(now) < 1);
		fadeTimer.setDelay(growing && fastFrames.getAsBoolean() ? GROW_FRAME_MILLIS : FADE_FRAME_MILLIS);
		(ghostExpired ? rebuild : repaint).run();
	}

	/**
	 * Scrolls the topmost of the flashed {@code paths} into view when none of them is visible, so a flash is not missed
	 * in a tree taller than the view. Paths under a collapsed node are left alone: the user collapsed it.
	 */
	static void revealFlash(JTree tree, List<TreePath> paths) {
		Rectangle visible = tree.getVisibleRect();
		Rectangle topmost = null;
		for (TreePath path : paths) {
			Rectangle bounds = tree.isVisible(path) ? tree.getPathBounds(path) : null;
			if (bounds == null) {
				continue;
			}
			if (bounds.intersects(visible)) {
				return;
			}
			if (topmost == null || bounds.y < topmost.y) {
				topmost = bounds;
			}
		}
		if (topmost != null) {
			tree.scrollRectToVisible(topmost);
		}
	}

	/**
	 * Whether the node whose user object is {@code after} changed since the same key held {@code before}: its status
	 * changed, or it is a member modified again, which keeps its status but gets a new {@link MemberDiff#changeId()}.
	 * A file that stays modified is not a change: every member edit would also flash the file.
	 */
	static boolean changedSince(Object before, Object after) {
		if (DiffTreeNodes.statusOf(before) != DiffTreeNodes.statusOf(after)) {
			return true;
		}
		return before instanceof MemberDiff was && after instanceof MemberDiff now && was.changeId() != now.changeId();
	}

	/**
	 * The flashes for the nodes that appeared, changed or disappeared since {@code oldNodes}. A content change to a
	 * node whose key is in {@code running} is skipped, so its flash is not restarted while it runs, while other nodes
	 * flash independently; a status change replaces the running flash to show the new status. A removal always flashes
	 * as a {@link Ghost}, even while the removed node's own flash runs.
	 */
	static Map<List<String>, Flash> newFlashes(Map<List<String>, DefaultMutableTreeNode> oldNodes,
											   Map<List<String>, DefaultMutableTreeNode> newNodes, Set<List<String>> running, long now) {
		Map<List<String>, Flash> result = new LinkedHashMap<>();
		newNodes.forEach((key, node) -> {
			FileStatus status = DiffTreeNodes.statusOf(node.getUserObject());
			DefaultMutableTreeNode old = oldNodes.get(key);
			boolean statusChanged = old != null && status != DiffTreeNodes.statusOf(old.getUserObject());
			if (status != null && (statusChanged || !running.contains(key))
					&& (old == null || changedSince(old.getUserObject(), node.getUserObject()))
					&& (node.getUserObject() instanceof FileDiff || hadMembers(node, oldNodes))) {
				result.put(key, new Flash(status, old == null, null, -1, now));
			}
		});
		oldNodes.forEach((key, node) -> {
			FileStatus status = DiffTreeNodes.statusOf(node.getUserObject());
			// only the topmost vanished node; its subtree goes with it
			boolean parentRemains = key.size() == 2 || newNodes.containsKey(key.subList(0, key.size() - 1));
			if (status != null && !newNodes.containsKey(key) && parentRemains
					&& (node.getUserObject() instanceof FileDiff || hadMembers(node, newNodes))) {
				result.put(key, new Flash(status, false, node.getUserObject(), node.getParent().getIndex(node), now));
			}
		});
		return result;
	}

	/**
	 * Whether a member node's appearance or disappearance is a change to flash: its file had computed members in the
	 * other tree, or is a modified file new there (its first changed member). Members of an added file all come with it,
	 * and members of a file whose structure was not available yet (before revision still loading) are not changes.
	 */
	private static boolean hadMembers(DefaultMutableTreeNode member, Map<List<String>, DefaultMutableTreeNode> otherNodes) {
		DefaultMutableTreeNode fileNode = DiffTreeNodes.fileNode(member);
		if (fileNode == null) {
			return false;
		}
		DefaultMutableTreeNode other = otherNodes.get(DiffTreeNodes.keyOf(fileNode));
		if (other == null) {
			return DiffTreeNodes.statusOf(fileNode.getUserObject()) == FileStatus.MODIFIED;
		}
		return other.getUserObject() instanceof FileDiff file && file.members() != null;
	}

	private void insertGhosts(DefaultMutableTreeNode root, Map<List<String>, DefaultMutableTreeNode> nodes,
							  Map<VirtualFile, DefaultMutableTreeNode> repoNodes, Map<String, VirtualFile> vcsRoots) {
		flashes.forEach((key, flash) -> {
			if (flash.vanished() == null) {
				return;
			}
			DefaultMutableTreeNode parent = key.size() > 2 ? nodes.get(key.subList(0, key.size() - 1))
					: flash.vanished() instanceof FileDiff file ? repoNodes.getOrDefault(vcsRoots.get(file.path()), root) : root;
			if (parent != null) {
				parent.insert(new DefaultMutableTreeNode(new Ghost(flash.vanished())), Math.min(flash.index(), parent.getChildCount()));
			}
		});
	}
}
