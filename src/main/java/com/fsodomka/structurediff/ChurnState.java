package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.diff.MemberLocator;
import com.fsodomka.structurediff.source.ChangeSource;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * How often the lines of each shown member were changed by the commits behind the changes (and the working copy on top
 * of them in Branch vs main), for the treemap's {@link ViewOptions.Fill#CHURN} fill; see
 * {@code docs/specs/2026-11-20-treemap-churn-heat-design.md}. Computed only while that fill is chosen, and only for
 * sources made of commits.
 */
final class ChurnState {
	private static final Logger LOG = Logger.getInstance(ChurnState.class);
	/**
	 * Counts several files at once; each count is mostly git processes. One thread per CPU, from 4 (a small machine still
	 * overlaps the process starts) to 12 (measured on 12 CPUs: 22 files took 313 ms on 4 threads, 226 on 12, 215 on 24).
	 */
	private static final ExecutorService COUNTERS = AppExecutorUtil.createBoundedApplicationPoolExecutor("Structure Diff churn",
			Math.clamp(Runtime.getRuntime().availableProcessors(), 4, 12));

	/**
	 * The churn of a node: the rewrites per line of the member, the maximum of its members for a container or file.
	 *
	 * @param created the member, or its file, is new in the shown changes: its first change is not a rewrite, so it shows
	 *                in its own color until its value is above zero
	 */
	record Churn(double value, boolean created) {
	}

	/** A file's value by member key path; the empty path is the whole file. */
	private record FileChurn(Map<List<String>, Churn> byKeyPath) {
	}

	/** A file's counters for one text and commit range, reused while neither changed. */
	private record CachedCounters(String afterText, ChangeSource.CommitRange range, int[] counters) {
	}

	private final Project project;
	private final ChangeSource source;
	private final Supplier<List<FileDiff>> shownFiles;
	private final BooleanSupplier active;
	private final Runnable repaint;
	private final Disposable parent;
	private final Object coalesce = new Object();
	private final Map<String, CachedCounters> cache = new ConcurrentHashMap<>();
	private Map<String, FileChurn> churn = Map.of();
	private boolean computing;
	/** Why the last computation showed nothing, null when it succeeded. */
	private @Nullable String problem;
	/** The files {@link #churn} was computed for; null when it is not, or a computation failed. */
	private @Nullable List<Object> computedFor;
	private volatile int generation;
	private volatile boolean disposed;

	ChurnState(Project project, ChangeSource source, Supplier<List<FileDiff>> shownFiles, BooleanSupplier active, Runnable repaint,
			   Disposable parent) {
		this.project = project;
		this.source = source;
		this.shownFiles = shownFiles;
		this.active = active;
		this.repaint = repaint;
		this.parent = parent;
		Disposer.register(parent, () -> disposed = true);
	}

	/** Whether the fill can be offered: only changes made of commits have a churn. */
	boolean available() {
		return source.hasCommits();
	}

	/** Recomputes the churn of the shown files while the fill is chosen, then repaints. Call on the EDT. */
	void update() {
		if (disposed) {
			return;
		}
		int current = ++generation;
		if (!active.getAsBoolean()) {
			computing = false;
			return;
		}
		if (!available()) {
			churn = Map.of();
			computedFor = null;
			computing = false;
			repaint.run();
			return;
		}
		List<FileDiff> targets = shownFiles.get().stream()
				.filter(file -> file.status() != FileStatus.DELETED && file.members() != null && file.afterText() != null).toList();
		// choosing the fill again with the same files and commits shows the values at once
		List<Object> key = targets.stream().map(file -> (Object) List.of(file.path(), file.afterText())).toList();
		if (key.equals(computedFor)) {
			computing = false;
			repaint.run();
			return;
		}
		computing = true;
		problem = null;
		repaint.run();
		AppExecutorUtil.getAppExecutorService().execute(() -> {
			Map<String, int[]> counters;
			try {
				counters = countersOf(targets, () -> disposed || current != generation);
			}
			catch (ProcessCanceledException e) {
				throw e;
			}
			catch (RuntimeException e) {
				LOG.warn("Cannot count the changes of the shown files", e);
				ApplicationManager.getApplication().invokeLater(() -> finish(current, null, "Cannot read the commits; see idea.log", null),
						ModalityState.any(), project.getDisposed());
				return;
			}
			if (counters == null) {
				// superseded while counting: the newer update shows its own result
				return;
			}
			ReadAction.nonBlocking(() -> compute(targets, counters))
					.inSmartMode(project)
					.expireWith(parent)
					.coalesceBy(coalesce)
					.finishOnUiThread(ModalityState.any(), result -> finish(current, result, null, key))
					.submit(AppExecutorUtil.getAppExecutorService())
					.onError(e -> ApplicationManager.getApplication().invokeLater(
							() -> finish(current, null, "Cannot find the members of the changed lines; see idea.log", null),
							ModalityState.any(), project.getDisposed()));
		});
	}

	/** Forgets what was computed, so the next {@link #update} counts again: the commits may have changed under the same files. */
	void reload() {
		computedFor = null;
		update();
	}

	/** @param key the files the result is for, null on a failure, so the next update tries again */
	private void finish(int current, @Nullable Map<String, FileChurn> result, @Nullable String problem, @Nullable List<Object> key) {
		if (current == generation) {
			if (result != null) {
				churn = result;
			}
			computedFor = key;
			this.problem = problem;
			computing = false;
			repaint.run();
		}
	}

	/** The line counters of the text of each file, by path; files outside every range are left out; null once superseded. */
	private @Nullable Map<String, int[]> countersOf(List<FileDiff> files, BooleanSupplier stale) {
		List<ChangeSource.CommitRange> ranges = source.commitRanges();
		Map<String, int[]> result = new HashMap<>();
		Map<FileDiff, ChangeSource.CommitRange> missing = new LinkedHashMap<>();
		for (FileDiff file : files) {
			ChangeSource.CommitRange range = ranges.stream()
					.filter(r -> file.path().startsWith(r.root().getPath() + "/")).findFirst().orElse(null);
			if (range == null) {
				continue;
			}
			CachedCounters cached = cache.get(file.path());
			if (cached != null && cached.afterText().equals(file.afterText()) && cached.range().equals(range)) {
				result.put(file.path(), cached.counters());
			}
			else {
				missing.put(file, range);
			}
		}
		// each file is a git log and a git show per commit that touched it, in a process each: the files count in parallel
		List<Future<int[]>> pending = new ArrayList<>();
		missing.forEach((file, range) -> pending.add(COUNTERS.submit(() -> stale.getAsBoolean() ? null : countersOf(file, range, stale))));
		try {
			int i = 0;
			for (Map.Entry<FileDiff, ChangeSource.CommitRange> entry : missing.entrySet()) {
				int[] counters = pending.get(i++).get();
				if (counters == null || stale.getAsBoolean()) {
					return null;
				}
				FileDiff file = entry.getKey();
				cache.put(file.path(), new CachedCounters(file.afterText(), entry.getValue(), counters));
				result.put(file.path(), counters);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		catch (ExecutionException e) {
			throw e.getCause() instanceof RuntimeException failure ? failure : new IllegalStateException(e.getCause());
		}
		finally {
			// a result not waited for, after a stop or a failure, is no longer needed
			pending.forEach(task -> task.cancel(true));
		}
		cache.keySet().retainAll(result.keySet());
		return result;
	}

	/**
	 * The counters of the lines of {@code file}'s shown text: from its text at the start of the changes, through each
	 * commit that touched it (under the name it had then), to the shown text, which is the working copy where
	 * {@code range} says so. Null once superseded.
	 */
	private int @Nullable [] countersOf(FileDiff file, ChangeSource.CommitRange range, BooleanSupplier stale) {
		FilePath path = ChangesUtil.getFilePath(file.change());
		String[] current = ChurnLines.lines(file.beforeText() == null ? "" : file.beforeText());
		int[] counters = new int[current.length];
		// a commit's text is the next one's start when the next one follows it: read once
		String lastHash = null;
		String lastPath = null;
		String lastText = null;
		for (ChurnLines.Step step : commitsTouching(range, path)) {
			if (stale.getAsBoolean()) {
				return null;
			}
			String beforeText = step.pathBefore() == null ? ""
					: step.parent() != null && step.parent().equals(lastHash) && step.pathBefore().equals(lastPath) ? lastText
					: contentAt(range, step.pathBefore(), step.parent());
			String afterText = step.pathAfter() == null ? "" : contentAt(range, step.pathAfter(), step.commit());
			String[] before = ChurnLines.lines(beforeText);
			String[] after = ChurnLines.lines(afterText);
			// commits that are not next to each other: the lines only follow to what the commit changed
			counters = ChurnLines.advance(counters, current, before, false);
			counters = ChurnLines.advance(counters, before, after, true);
			current = after;
			lastHash = step.pathAfter() == null ? null : step.commit();
			lastPath = step.pathAfter();
			lastText = afterText;
		}
		return ChurnLines.advance(counters, current, ChurnLines.lines(file.afterText()), range.workingCopy());
	}

	/** The commits of {@code range} that changed {@code path} or what it was named before, oldest first. */
	private List<ChurnLines.Step> commitsTouching(ChangeSource.CommitRange range, FilePath path) {
		GitLineHandler handler = new GitLineHandler(project, range.root(), GitCommand.LOG);
		handler.addParameters("--follow", "--name-status", "--format=%x01%H %P");
		handler.addParameters(range.logArgs());
		handler.endOptions();
		handler.addRelativePaths(path);
		GitCommandResult result = Git.getInstance().runCommand(handler);
		if (!result.success()) {
			throw new IllegalStateException("git log failed: " + result.getErrorOutputAsJoinedString());
		}
		// not --reverse: with --follow it stops the rename tracking
		List<ChurnLines.Step> steps = new ArrayList<>(ChurnLines.parseLog(result.getOutput()));
		java.util.Collections.reverse(steps);
		return steps;
	}

	/** The text of {@code relativePath} at {@code revision}; the log said the file exists there, so a failure is not "empty". */
	private String contentAt(ChangeSource.CommitRange range, String relativePath, @Nullable String revision) {
		if (revision == null) {
			return "";
		}
		try {
			FilePath path = VcsUtil.getFilePath(range.root().getPath() + "/" + relativePath, false);
			String content = GitContentRevision.createRevision(path, new GitRevisionNumber(revision), project).getContent();
			return content == null ? "" : content;
		}
		catch (VcsException e) {
			throw new IllegalStateException("Cannot read " + relativePath + " at " + revision, e);
		}
	}

	private Map<String, FileChurn> compute(List<FileDiff> files, Map<String, int[]> counters) {
		Map<String, FileChurn> result = new HashMap<>();
		for (FileDiff file : files) {
			int[] fileCounters = counters.get(file.path());
			if (fileCounters == null) {
				continue;
			}
			String text = file.afterText();
			Map<List<String>, TextRange> ranges = MemberLocator.memberRanges(project, nameOf(file.path()), file.fileType(), text);
			int[] lineStarts = ChurnLines.lineStarts(text);
			Map<List<String>, Churn> byKeyPath = new HashMap<>();
			boolean fileNew = file.status() == FileStatus.ADDED;
			double max = values(file.members(), List.of(), fileNew, ranges, lineStarts, fileCounters, byKeyPath);
			byKeyPath.put(List.of(), new Churn(max, fileNew));
			result.put(file.path(), new FileChurn(byKeyPath));
		}
		return Map.copyOf(result);
	}

	/** Fills {@code out} for {@code members} and returns the largest value among them. */
	private static double values(List<MemberDiff> members, List<String> path, boolean parentNew, Map<List<String>, TextRange> ranges,
								 int[] lineStarts, int[] counters, Map<List<String>, Churn> out) {
		double max = 0;
		for (MemberDiff member : members) {
			if (member.status() == FileStatus.DELETED) {
				continue;
			}
			List<String> keyPath = new ArrayList<>(path);
			keyPath.add(member.key());
			boolean created = parentNew || member.status() == FileStatus.ADDED;
			// a container showing its changed members is as hot as the hottest of them; its own lines are mostly theirs
			double value;
			if (!member.children().isEmpty()) {
				value = values(member.children(), keyPath, created, ranges, lineStarts, counters, out);
			}
			else {
				TextRange range = ranges.get(keyPath);
				value = range == null ? 0 : ChurnLines.churn(counters, ChurnLines.lineOf(lineStarts, range.getStartOffset()),
						ChurnLines.lineOf(lineStarts, Math.max(range.getStartOffset(), range.getEndOffset() - 1)), created);
			}
			out.put(keyPath, new Churn(value, created));
			max = Math.max(max, value);
		}
		return max;
	}

	private static String nameOf(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	/** The churn of a file or member node; null for other nodes and those without a value. */
	@Nullable Churn churnOf(DefaultMutableTreeNode node) {
		List<String> keyPath = new ArrayList<>();
		DefaultMutableTreeNode fileNode = node;
		while (fileNode != null && fileNode.getUserObject() instanceof MemberDiff member) {
			keyPath.addFirst(member.key());
			fileNode = (DefaultMutableTreeNode) fileNode.getParent();
		}
		if (fileNode == null || !(fileNode.getUserObject() instanceof FileDiff file)) {
			return null;
		}
		FileChurn fileChurn = churn.get(file.path());
		return fileChurn == null ? null : fileChurn.byKeyPath().get(keyPath);
	}

	/** The legend's note: what the number is. */
	String legend() {
		return "changes per line · green: new";
	}

	/** The message over the treemap while the fill is chosen and there is nothing to show yet; null otherwise. */
	@Nullable TreemapPanel.Overlay overlay() {
		if (!active.getAsBoolean()) {
			return null;
		}
		if (!available()) {
			return new TreemapPanel.Overlay("Churn needs commits\nChoose Branch vs main, or follow commits in the Git log", false, false);
		}
		if (computing) {
			return DumbService.isDumb(project) ? new TreemapPanel.Overlay("Waiting for indexing…", true, false)
					: new TreemapPanel.Overlay("Counting changes…", true, false);
		}
		return problem == null ? null : new TreemapPanel.Overlay(problem, false, false);
	}
}
