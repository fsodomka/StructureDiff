package com.fsodomka.structurediff;

import com.fsodomka.structurediff.MemberLinks.Link;
import com.fsodomka.structurediff.MemberLinks.Links;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

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
	/** The changed members related to the selected one by {@link #usages}, painted with {@link StructureDiffPanel#RELATED}; EDT only. */
	private Set<DefaultMutableTreeNode> relatedNodes = Set.of();
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

	/** Whether {@code node} is a changed member related to the selected one by the shown usages. */
	boolean isRelated(DefaultMutableTreeNode node) {
		return relatedNodes.contains(node);
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
			usagesSearch = null;
		}
		usagesGeneration++;
	}

	/** Sets {@link #usages} and highlights the members they relate in the current tree. */
	private void setUsages(@Nullable Links links) {
		usages = links;
		Set<DefaultMutableTreeNode> related = links == null ? Set.of() : relatedNodes((DefaultMutableTreeNode) tree.getModel().getRoot(), links);
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
					PsiElement element = psi == null ? null : StructureDiffComputer.elements(psi, List.of(path)).getFirst();
					return element == null ? null : MemberLinks.links(project, element);
				})
				.inSmartMode(project)
				.expireWith(parent)
				.finishOnUiThread(ModalityState.defaultModalityState(), links -> {
					if (generation != usagesGeneration) {
						return;
					}
					usagesSearch = null;
					if (links == null) {
						showUsagesMessage("Member not found in the working copy");
						return;
					}
					setUsages(links);
					usagesPanel.show(member.label(), links);
				})
				.submit(AppExecutorUtil.getAppExecutorService());
		usagesSearch.onError(e -> {
			if (e instanceof ProcessCanceledException || e instanceof CancellationException) {
				return;
			}
			LOG.error("Usages could not be found", e);
			UIUtil.invokeLaterIfNeeded(() -> {
				if (generation == usagesGeneration) {
					usagesSearch = null;
					showUsagesMessage("Usages could not be found");
				}
			});
		});
	}

	/**
	 * The member nodes below {@code root} that hold {@code links}. A link to a member that is not in the tree highlights
	 * the deepest code member on its path, which holds it as nested code (nested changes hidden, or an unchanged nested
	 * member); not a class, whose unchanged members are not related to the class.
	 */
	static Set<DefaultMutableTreeNode> relatedNodes(DefaultMutableTreeNode root, Links links) {
		Set<DefaultMutableTreeNode> result = new HashSet<>();
		for (Link link : Stream.concat(links.callers().stream(), links.callees().stream()).toList()) {
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
				result.add(node);
			}
		}
		return result;
	}
}
