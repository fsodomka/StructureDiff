package com.fsodomka.structurediff;

import com.fsodomka.structurediff.MemberLinks.Link;
import com.fsodomka.structurediff.MemberLinks.Links;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.diff.MemberLocator;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.CancellablePromise;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

/** Searches the callers and callees of the selected member for the {@link UsagesPanel}, and the changed members they relate. */
final class UsagesController {
	private static final Logger LOG = Logger.getInstance(UsagesController.class);

	/** The key of the node whose usages are shown or searched; null when none is. */
	private List<String> usagesKey;
	/** The usages of {@link #usagesKey}; null while the first search runs or when there are none to show. */
	private Links usages;
	/** The {@link #contentStamp} of the files when {@link #usages} were searched; a different one searches again. */
	private int usagesStamp;
	/** The running search; null when none runs. */
	private CancellablePromise<Links> usagesSearch;
	private int usagesGeneration;
	/** The changed members related to the selected one by {@link #usages}, painted with their {@link Relation}; EDT only. */
	private Map<DefaultMutableTreeNode, Relation> relatedNodes = Map.of();
	/** Whether the map was {@link #faded} when the running search started; EDT only. */
	private boolean fadedOnSearch;
	private final Project project;
	private final UsagesPanel usagesPanel;
	private final JTree tree;
	/** Repaints the tree and the treemap when the related members change. */
	private final Runnable repaint;
	private final Disposable parent;

	UsagesController(Project project, UsagesPanel usagesPanel, JTree tree, Runnable repaint, Disposable parent) {
		this.project = project;
		this.usagesPanel = usagesPanel;
		this.tree = tree;
		this.repaint = repaint;
		this.parent = parent;
	}

	/** How {@code node}, a changed member, is related to the selected one by the shown usages; null when it is not. */
	@Nullable Relation relationOf(DefaultMutableTreeNode node) {
		return relatedNodes.get(node);
	}

	/**
	 * Which nodes stay at full strength while the usages are shown, for one paint: all when the map is not
	 * {@link #faded}, otherwise those selected or inside a selected node, or that are or hold a related or selected node.
	 */
	Predicate<DefaultMutableTreeNode> focus() {
		return focus(relatedNodes.keySet(), faded(), tree.getSelectionPaths());
	}

	/** {@link #focus()}; built once, so each test walks only the node's own ancestors. */
	static Predicate<DefaultMutableTreeNode> focus(Set<DefaultMutableTreeNode> related, boolean faded,
												   TreePath @Nullable [] selection) {
		if (!faded) {
			return node -> true;
		}
		Set<TreeNode> selected = Collections.newSetFromMap(new IdentityHashMap<>());
		// the selected and related nodes and every node holding one
		Set<TreeNode> holders = Collections.newSetFromMap(new IdentityHashMap<>());
		for (TreePath path : selection == null ? new TreePath[0] : selection) {
			selected.add((TreeNode) path.getLastPathComponent());
			addWithAncestors((TreeNode) path.getLastPathComponent(), holders);
		}
		for (DefaultMutableTreeNode node : related) {
			addWithAncestors(node, holders);
		}
		return node -> {
			if (holders.contains(node)) {
				return true;
			}
			for (TreeNode ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
				if (selected.contains(ancestor)) {
					return true;
				}
			}
			return false;
		};
	}

	private static void addWithAncestors(TreeNode node, Set<TreeNode> result) {
		// stops at a node already added, whose ancestors are too
		TreeNode n = node;
		while (n != null && result.add(n)) {
			n = n.getParent();
		}
	}

	private boolean faded() {
		return faded(!relatedNodes.isEmpty(), usagesSearch != null, fadedOnSearch);
	}

	/**
	 * Whether the members out of focus fade: while members are related, and while a search runs that started with the
	 * map faded ({@code fadedOnSearch}). A search keeps the map as it was, so a new selection changes it at most once,
	 * when its result comes, and stepping through members that relate none does not fade it on every step.
	 */
	static boolean faded(boolean related, boolean searching, boolean fadedOnSearch) {
		return related || searching && fadedOnSearch;
	}

	/** Drops the shown usages and the highlight, cancelling a running search. */
	void cancel() {
		cancelUsagesSearch();
		usagesKey = null;
		setUsages(null);
	}

	/** Cancels a running search; its result is dropped even if it already finished and waits for the EDT. */
	private void cancelUsagesSearch() {
		if (usagesSearch != null) {
			usagesSearch.cancel();
			searchDone();
		}
		usagesGeneration++;
	}

	/** Drops the finished or cancelled search; repaints, as {@link #focus()} depends on whether one runs. */
	private void searchDone() {
		usagesSearch = null;
		repaint.run();
	}

	/** Sets {@link #usages} and highlights the members they relate in the current tree. */
	private void setUsages(@Nullable Links links) {
		usages = links;
		Map<DefaultMutableTreeNode, Relation> related = links == null ? Map.of() : relatedNodes((DefaultMutableTreeNode) tree.getModel().getRoot(), links);
		if (!related.equals(relatedNodes)) {
			relatedNodes = related;
			repaint.run();
		}
	}

	/** Shows {@code message} in place of the usages, dropping the highlight. */
	private void showUsagesMessage(String message) {
		setUsages(null);
		usagesPanel.showMessage(message);
	}

	/**
	 * Stamp of the files' contents: when it differs from {@link #usagesStamp}, an edit may have changed the usages.
	 * Cheap to recompute: a string caches its hash, and the texts of unchanged files are kept across refreshes.
	 */
	static int contentStamp(List<FileDiff> files) {
		int stamp = 1;
		for (FileDiff file : files) {
			stamp = 31 * (31 * stamp + file.path().hashCode()) + Objects.hashCode(file.afterText());
		}
		return stamp;
	}

	/**
	 * Searches the callers and callees of the selected {@code node}'s member. When the same member is selected
	 * after the tree was rebuilt, the shown usages are matched to the new nodes; they are searched again only when the
	 * files changed or the last search found nothing, and stay shown until the new search replaces them.
	 */
	void update(@Nullable DefaultMutableTreeNode node, List<FileDiff> files) {
		List<String> key = node == null ? null : DiffTreeNodes.keyOf(node);
		int stamp = contentStamp(files);
		boolean sameMember = key != null && key.equals(usagesKey);
		// before cancel() drops the highlight; a search replacing a running one keeps that one's state
		fadedOnSearch = faded();
		if (sameMember) {
			setUsages(usages);
			if (stamp == usagesStamp && (usages != null || usagesSearch != null)) {
				return;
			}
			cancelUsagesSearch();
		}
		else {
			cancel();
			usagesKey = key;
		}
		usagesStamp = stamp;
		if (node == null || !(node.getUserObject() instanceof MemberDiff member)) {
			usagesPanel.clear();
			return;
		}
		FileDiff file = Objects.requireNonNull(DiffTreeNodes.fileOf(node));
		ContentRevision after = file.change().getAfterRevision();
		if (after == null || member.afterOffset() < 0) {
			showUsagesMessage("A removed member has no usages to look up");
			return;
		}
		VirtualFile workingFile = after instanceof CurrentContentRevision current ? current.getVirtualFile() : null;
		if (workingFile == null) {
			showUsagesMessage("Usages need the working-tree version of this file");
			return;
		}
		List<MemberDiff> path = DiffTreeNodes.memberPath(node);
		int generation = usagesGeneration;
		if (usages == null) {
			usagesPanel.showBusy(member.label());
		}
		usagesSearch = ReadAction.nonBlocking(() -> {
					PsiFile psi = workingFile.isValid() ? PsiManager.getInstance(project).findFile(workingFile) : null;
					PsiElement element = psi == null ? null : MemberLocator.elements(psi, List.of(path)).getFirst();
					return element == null ? null : MemberLinks.links(project, element);
				})
				.inSmartMode(project)
				.expireWith(parent)
				.finishOnUiThread(ModalityState.defaultModalityState(), links -> {
					if (generation != usagesGeneration) {
						return;
					}
					searchDone();
					if (links == null) {
						showUsagesMessage("Member not found in the working copy");
						return;
					}
					setUsages(links);
					usagesPanel.show(member.label(), links);
				})
				.submit(AppExecutorUtil.getAppExecutorService());
		repaint.run();
		usagesSearch.onError(e -> {
			// ProcessCanceledException is a CancellationException
			boolean cancelled = e instanceof CancellationException;
			if (!cancelled) {
				LOG.error("Usages could not be found", e);
			}
			// a search cancelled by anything but cancelUsagesSearch (which moves the generation on) is done too, or it
			// would keep the map faded and block searching the member again
			UIUtil.invokeLaterIfNeeded(() -> {
				if (generation == usagesGeneration) {
					searchDone();
					if (!cancelled) {
						showUsagesMessage("Usages could not be found");
					}
				}
			});
		});
	}

	/**
	 * The member nodes below {@code root} that hold {@code links}. A link to a member that is not in the tree highlights
	 * the deepest code member on its path, which holds it as nested code (nested changes hidden, or an unchanged nested
	 * member); not a class, whose unchanged members are not related to the class. A node reached as both a caller and a
	 * callee is {@link Relation#BOTH}.
	 */
	static Map<DefaultMutableTreeNode, Relation> relatedNodes(DefaultMutableTreeNode root, Links links) {
		Map<DefaultMutableTreeNode, Relation> result = new HashMap<>();
		addRelated(root, links.callers(), Relation.CALLER, result);
		addRelated(root, links.callees(), Relation.CALLEE, result);
		return result;
	}

	private static void addRelated(DefaultMutableTreeNode root, List<Link> links, Relation relation,
								   Map<DefaultMutableTreeNode, Relation> result) {
		for (Link link : links) {
			DefaultMutableTreeNode node = DiffTreeNodes.child(root, link.file().getPath());
			if (node == null || link.keys().isEmpty()) {
				continue;
			}
			int matched = 0;
			for (String part : link.keys()) {
				DefaultMutableTreeNode next = DiffTreeNodes.child(node, part);
				if (next == null) {
					break;
				}
				node = next;
				matched++;
			}
			if (matched == link.keys().size() || matched > 0 && node.getUserObject() instanceof MemberDiff member && !member.container()) {
				result.merge(node, relation, Relation::merge);
			}
		}
	}
}
