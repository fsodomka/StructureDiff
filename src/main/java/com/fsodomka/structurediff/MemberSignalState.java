package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.Diagnostics;
import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.reason.EditReasonMembers;
import com.fsodomka.structurediff.reason.EditReasonMembers.MemberReason;
import com.fsodomka.structurediff.reason.EditReasonsService;
import com.fsodomka.structurediff.review.FindingMembers;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewFindingsToolWindowFactory;
import com.fsodomka.structurediff.settings.DiagnosticLevel;
import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.fsodomka.structurediff.source.ChangeSource;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The review findings, the agent's edit reasons and the IDE's diagnostics in the members of the shown files, computed off the EDT, and what a
 * node of the changes tree shows of them.
 */
final class MemberSignalState {
	private final Project project;
	private final ChangeSource source;
	/** The files the tree shows; their diagnostics are computed. */
	private final Supplier<List<FileDiff>> shownFiles;
	/** Repaints the views; run when the diagnostics changed. */
	private final Runnable repaint;
	/** Run when the findings changed. */
	private final Runnable findingsChanged;
	/** Expires the computations. */
	private final Disposable parent;
	/** Review findings by absolute file path, see {@link FindingMembers}; EDT only. */
	private Map<String, List<MemberFinding>> findings = Map.of();
	private final AtomicInteger findingsGeneration = new AtomicInteger();
	/** The agent's edit reasons by absolute file path, see {@link EditReasonMembers}; EDT only. */
	private Map<String, List<MemberReason>> reasons = Map.of();
	private final AtomicInteger reasonsGeneration = new AtomicInteger();
	/** The IDE's diagnostics on the shown files with an open editor, see {@link DiagnosticMembers}; EDT only. */
	private Diagnostics diagnostics = Diagnostics.NONE;
	private final AtomicInteger diagnosticsGeneration = new AtomicInteger();
	/**
	 * What each node shows of the three, which the tree rows and every box of the treemap ask for on each paint: filtering a
	 * file's items per node is costly in files with many members and items. Valid while the node's subtree stays as built
	 * (a flash only adds ghosts, which do not count as members) and the data is the same, so each is cleared when its data
	 * is replaced; weak, as the tree is rebuilt with new nodes. EDT only.
	 */
	private final Map<DefaultMutableTreeNode, List<MemberFinding>> findingsByNode = new WeakHashMap<>();
	private final Map<DefaultMutableTreeNode, List<MemberReason>> reasonsByNode = new WeakHashMap<>();
	private final Map<DefaultMutableTreeNode, List<MemberDiagnostic>> diagnosticsByNode = new WeakHashMap<>();
	/** Only touched by the diagnostics computation, which may overlap a cancelled older one. */
	private final Map<String, DiagnosticMembers.CachedKeyPaths> diagnosticsCache = new ConcurrentHashMap<>();

	MemberSignalState(Project project, ChangeSource source, Supplier<List<FileDiff>> shownFiles, Runnable repaint,
					  Runnable findingsChanged, Disposable parent) {
		this.project = project;
		this.source = source;
		this.shownFiles = shownFiles;
		this.repaint = repaint;
		this.findingsChanged = findingsChanged;
		this.parent = parent;
	}

	/** Review findings by absolute file path. */
	Map<String, List<MemberFinding>> findings() {
		return findings;
	}

	/** Recomputes which members the review findings are in, then repaints. */
	void updateFindings() {
		int current = findingsGeneration.incrementAndGet();
		Supplier<Map<String, List<MemberFinding>>> compute = FindingMembers.prepare(project);
		ReadAction.nonBlocking(compute::get)
				.expireWith(parent)
				// cancels an older computation, whose result would be discarded anyway
				.coalesceBy(parent, findingsGeneration)
				.finishOnUiThread(ModalityState.any(), result -> {
					if (current == findingsGeneration.get()) {
						findings = result;
						findingsByNode.clear();
						findingsChanged.run();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	/**
	 * Recomputes the IDE's diagnostics on the shown files, then repaints; none when turned off or when the after side is
	 * not the working tree, whose offsets would not match the editor's document.
	 */
	void updateDiagnostics() {
		int current = diagnosticsGeneration.incrementAndGet();
		StructureDiffSettings settings = StructureDiffSettings.getInstance();
		Set<DiagnosticLevel> shown = settings.diagnosticLevels();
		if (shown.isEmpty() || !source.hasUnversioned()) {
			if (diagnostics != Diagnostics.NONE) {
				diagnostics = Diagnostics.NONE;
				diagnosticsByNode.clear();
				repaint.run();
			}
			return;
		}
		Supplier<Diagnostics> compute = DiagnosticMembers.prepare(project, shownFiles.get(), shown, diagnosticsCache);
		ReadAction.nonBlocking(compute::get)
				.expireWith(parent)
				// cancels an older computation, whose result would be discarded anyway
				.coalesceBy(parent, diagnosticsGeneration)
				.finishOnUiThread(ModalityState.any(), result -> {
					if (current == diagnosticsGeneration.get()) {
						diagnostics = result;
						diagnosticsByNode.clear();
						repaint.run();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	/**
	 * Findings shown on a node, most severe first: all of a file node's; on a member node those it is the innermost
	 * shown member of. Findings in members that are not shown (unchanged, renamed, nested) count on the file only.
	 */
	List<MemberFinding> findingsOf(DefaultMutableTreeNode node) {
		return findingsByNode.computeIfAbsent(node, n -> MemberSignals.findingsOf(n, findings));
	}

	/** Recomputes which members the edit reasons are in, then repaints. */
	void updateReasons() {
		int current = reasonsGeneration.incrementAndGet();
		if (!EditReasonsService.isEnabled()) {
			boolean hadReasons = !reasons.isEmpty();
			reasons = Map.of();
			reasonsByNode.clear();
			if (hadReasons) {
				repaint.run();
			}
			return;
		}
		Supplier<Map<String, List<MemberReason>>> compute = EditReasonMembers.prepare(project, shownFiles.get());
		ReadAction.nonBlocking(compute::get)
				.expireWith(parent)
				.coalesceBy(parent, reasonsGeneration)
				.finishOnUiThread(ModalityState.any(), result -> {
					if (current == reasonsGeneration.get()) {
						reasons = result;
						reasonsByNode.clear();
						repaint.run();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	/** Edit reasons shown on a node, newest first, placed as {@link #findingsOf(DefaultMutableTreeNode)}. */
	List<MemberReason> reasonsOf(DefaultMutableTreeNode node) {
		return EditReasonsService.isEnabled()
				? reasonsByNode.computeIfAbsent(node, n -> MemberSignals.distinct(MemberSignals.placedOn(n, reasons, MemberReason::keyPath)))
				: List.of();
	}

	/** Diagnostics shown on a node, most severe first, placed as {@link #findingsOf(DefaultMutableTreeNode)}. */
	List<MemberDiagnostic> diagnosticsOf(DefaultMutableTreeNode node) {
		return diagnosticsByNode.computeIfAbsent(node, n -> MemberSignals.placedOn(n, diagnostics.byPath(), MemberDiagnostic::keyPath));
	}

	/** Opens the node's file at its most severe diagnostic. */
	void openDiagnostic(DefaultMutableTreeNode node) {
		List<MemberDiagnostic> diags = diagnosticsOf(node);
		DefaultMutableTreeNode fileNode = node;
		while (fileNode != null && !(fileNode.getUserObject() instanceof FileDiff)) {
			fileNode = (DefaultMutableTreeNode) fileNode.getParent();
		}
		VirtualFile file = fileNode == null ? null
				: LocalFileSystem.getInstance().findFileByPath(((FileDiff) fileNode.getUserObject()).path());
		if (!diags.isEmpty() && file != null) {
			new OpenFileDescriptor(project, file, diags.getFirst().offset()).navigate(true);
		}
	}

	void showFindings(List<MemberFinding> found) {
		ReviewFindingsToolWindowFactory.show(project, found);
	}
}
