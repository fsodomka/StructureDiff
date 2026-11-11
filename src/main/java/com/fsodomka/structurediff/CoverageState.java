package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.diff.MemberLocator;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.text.DateFormatUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The share of each shown member's lines the IDE's selected coverage run executed, for the treemap's
 * {@link ViewOptions.Fill#COVERAGE} fill. Computed only while that fill is chosen.
 */
final class CoverageState {
	private static final Logger LOG = Logger.getInstance(CoverageState.class);

	/** Covered and coverable lines of a node; {@code stale} when its file changed after the run. */
	record Counts(int covered, int coverable, boolean stale) {
		double share() {
			return coverable == 0 ? 0 : covered / (double) coverable;
		}

		/** The share in whole percent; 0 and 100 only when none or all are covered, as rounded 99.6 % would read as all. */
		long percent() {
			return covered == 0 ? 0 : covered >= coverable ? 100 : Math.clamp(Math.round(100 * share()), 1, 99);
		}
	}

	/** A file's counts by member key path, every prefix included; the empty path is the whole file. */
	private record FileCounts(Map<List<String>, int[]> byKeyPath, boolean stale) {
	}

	/**
	 * A file's counts for one text and run, shared by the project's panels: Branch and Git Log make a new panel on each
	 * switch to them; {@code counts} null when the run has no lines for the file.
	 */
	private record CachedFile(CharSequence text, CoverageLines.Run run, @Nullable Map<List<String>, int[]> counts) {
	}

	private static final Key<ConcurrentMap<String, CachedFile>> CACHE = Key.create("structureDiff.coverageCache");

	/** What one computation spent its time on, for the log. */
	private static final class Timing {
		final AtomicInteger attempts = new AtomicInteger();
		volatile long computeNanos;
		volatile long hitsNanos;
		volatile long keyPathsNanos;
		volatile int cached;
	}

	/** A shown file; its document is loaded in the read action, as loading the unopened ones on the EDT reads them from disk. */
	private record Target(String path, VirtualFile file, boolean stale) {
	}

	private final Project project;
	private final @Nullable CoverageLines lines;
	private final Supplier<List<FileDiff>> shownFiles;
	private final BooleanSupplier active;
	private final Runnable repaint;
	private final Disposable parent;
	private final Object coalesce = new Object();
	private Map<String, FileCounts> counts = Map.of();
	private @Nullable CoverageLines.Run run;
	private boolean computing;
	/** The run {@link #counts} were computed for; a failed computation keeps them only for the same run. */
	private @Nullable CoverageLines.Run countsRun;
	/** Why the last computation showed nothing new; null when it succeeded. */
	private @Nullable String problem;
	/** The run and files {@link #counts} were computed for; a choice of the fill with the same ones reuses them. */
	private @Nullable List<Object> computedFor;
	private int generation;
	/** Set when {@link #parent} is disposed; replaces the deprecated {@code Disposer.isDisposed}. */
	private volatile boolean disposed;
	private final ConcurrentMap<String, CachedFile> cache;

	CoverageState(Project project, Supplier<List<FileDiff>> shownFiles, BooleanSupplier active, Runnable repaint, Disposable parent) {
		this.project = project;
		this.lines = project.getService(CoverageLines.class);
		this.shownFiles = shownFiles;
		this.active = active;
		this.repaint = repaint;
		this.parent = parent;
		this.cache = ((UserDataHolderEx) project).putUserDataIfAbsent(CACHE, new ConcurrentHashMap<>());
		Disposer.register(parent, () -> disposed = true);
		if (lines != null) {
			lines.onChange(this::update, parent);
		}
	}

	/** Whether the IDE has the Coverage plugin, so the fill can be offered. */
	boolean available() {
		return lines != null;
	}

	/** Recomputes the counts of the shown files while the fill is chosen, then repaints. Call on the EDT. */
	void update() {
		// a change of the run queued before the panel closed
		if (disposed) {
			return;
		}
		int current = ++generation;
		if (lines == null || !active.getAsBoolean()) {
			// keeps the counts, so choosing the fill again shows them at once; drops a computation in progress
			computing = false;
			return;
		}
		run = lines.run();
		if (run == null) {
			counts = Map.of();
			countsRun = null;
			computedFor = null;
			computing = false;
			problem = null;
			repaint.run();
			return;
		}
		// the counts of other runs are not shown again, and would keep the texts they were computed for
		CoverageLines.Run selected = run;
		cache.values().removeIf(cachedFile -> !cachedFile.run().equals(selected));
		List<Target> targets = new ArrayList<>();
		List<Object> key = new ArrayList<>();
		key.add(run);
		FileDocumentManager documents = FileDocumentManager.getInstance();
		for (FileDiff fileDiff : shownFiles.get()) {
			VirtualFile file = fileDiff.status() == FileStatus.DELETED ? null : LocalFileSystem.getInstance().findFileByPath(fileDiff.path());
			if (file == null || file.isDirectory()) {
				continue;
			}
			// edited since the run: its lines may have moved
			boolean stale = documents.isFileModified(file) || file.getTimeStamp() > run.timestamp();
			targets.add(new Target(fileDiff.path(), file, stale));
			// a document loaded from the file starts with the file's stamp, so the key holds whether it is loaded or not
			Document cached = documents.getCachedDocument(file);
			key.add(List.of(fileDiff.path(), cached == null ? file.getModificationStamp() : cached.getModificationStamp(), stale));
		}
		if (key.equals(computedFor)) {
			// a computation in progress was for other files or another run
			computing = false;
			repaint.run();
			return;
		}
		computing = true;
		problem = null;
		repaint.run();
		CoverageLines source = lines;
		CoverageLines.Run forRun = run;
		Timing timing = new Timing();
		// loading the run outside the read action, as it cannot be cancelled and would hold up every write action
		AppExecutorUtil.getAppExecutorService().execute(() -> {
			long start = System.currentTimeMillis();
			CoverageLines.Data data;
			try {
				data = source.load();
			}
			catch (RuntimeException e) {
				LOG.warn("Cannot load the coverage run " + forRun.name(), e);
				// not computed, so the next update tries again
				ApplicationManager.getApplication().invokeLater(() -> finish(current, null, null, "Cannot load the coverage run; see idea.log"),
						ModalityState.any(), project.getDisposed());
				return;
			}
			long loadedAt = System.currentTimeMillis();
			ApplicationManager.getApplication().invokeLater(() -> {
				if (current != generation) {
					return;
				}
				if (data == null) {
					// not taken as computed: the run's data may come later, under the same run and texts
					finish(current, null, null, "The coverage run's data is not loaded yet");
					return;
				}
				long mapStart = System.currentTimeMillis();
				ReadAction.nonBlocking(() -> compute(data, forRun, targets, timing))
						.inSmartMode(project)
						.expireWith(parent)
						.coalesceBy(coalesce)
						.finishOnUiThread(ModalityState.any(), result -> {
							if (LOG.isDebugEnabled()) LOG.debug("Coverage of " + targets.size() + " files (" + timing.cached + " cached): loaded in "
									+ (loadedAt - start) + " ms; mapped in " + (System.currentTimeMillis() - mapStart) + " ms elapsed, "
									+ timing.attempts.get() + " attempts, last computing " + timing.computeNanos / 1_000_000 + " ms (classes "
									+ timing.hitsNanos / 1_000_000 + " ms, members " + timing.keyPathsNanos / 1_000_000 + " ms)");
							finish(current, key, result, null);
						})
						.submit(AppExecutorUtil.getAppExecutorService())
						// a failure, or a cancellation by coalesceBy, which comes with a newer generation that finish ignores
						.onError(e -> ApplicationManager.getApplication().invokeLater(
								() -> finish(current, null, null, "Cannot find the members of the covered lines; see idea.log"),
								ModalityState.any(), project.getDisposed()));
			}, ModalityState.any(), project.getDisposed());
		});
	}

	/**
	 * Shows {@code result} unless a newer update started. On a failure, {@code key} and {@code result} are null, so the
	 * next update computes again, and {@code problem} says why; the counts shown stay while they are of the same run.
	 */
	private void finish(int current, @Nullable List<Object> key, @Nullable Map<String, FileCounts> result, @Nullable String problem) {
		if (current == generation) {
			if (result != null) {
				counts = result;
				countsRun = run;
			}
			else if (run == null || !run.equals(countsRun)) {
				counts = Map.of();
				countsRun = null;
			}
			this.problem = problem;
			computedFor = key;
			computing = false;
			repaint.run();
		}
	}

	private Map<String, FileCounts> compute(CoverageLines.Data source, CoverageLines.Run run, List<Target> targets, Timing timing) {
		timing.attempts.incrementAndGet();
		long computeStart = System.nanoTime();
		long hitsNanos = 0;
		long keyPathsNanos = 0;
		int cachedFiles = 0;
		Map<String, FileCounts> result = new HashMap<>();
		FileDocumentManager documents = FileDocumentManager.getInstance();
		for (Target target : targets) {
			Document document = target.file().isValid() ? documents.getDocument(target.file()) : null;
			if (document == null) {
				continue;
			}
			CharSequence text = document.getImmutableCharSequence();
			CachedFile cachedFile = cache.get(target.path());
			if (cachedFile != null && cachedFile.text() == text && cachedFile.run().equals(run)) {
				cachedFiles++;
				if (cachedFile.counts() != null) {
					result.put(target.path(), new FileCounts(cachedFile.counts(), target.stale()));
				}
				continue;
			}
			long hitsStart = System.nanoTime();
			CoverageLines.Hits hits = source.hits(target.file());
			hitsNanos += System.nanoTime() - hitsStart;
			if (hits == null) {
				cache.put(target.path(), new CachedFile(text, run, null));
				continue;
			}
			// the first non-blank character of each line, so the line belongs to the member starting on it
			List<Integer> offsets = new ArrayList<>();
			List<Boolean> covered = new ArrayList<>();
			for (int i = 0; i < hits.lines().length; i++) {
				int line = hits.lines()[i];
				if (line < 0 || line >= document.getLineCount()) {
					// an inlined function's lines from another file, or a line number the run did not record
					continue;
				}
				int offset = document.getLineStartOffset(line);
				int end = document.getLineEndOffset(line);
				while (offset < end && Character.isWhitespace(text.charAt(offset))) {
					offset++;
				}
				offsets.add(offset);
				covered.add(hits.covered()[i]);
			}
			List<List<String>> keyPaths;
			long keyPathsStart = System.nanoTime();
			try {
				keyPaths = MemberLocator.memberKeyPaths(project, target.file().getName(), target.file().getFileType(), text,
						offsets.stream().mapToInt(Integer::intValue).toArray());
			}
			catch (ProcessCanceledException e) {
				throw e;
			}
			catch (RuntimeException e) {
				LOG.warn("Cannot find the members of the covered lines in " + target.path(), e);
				continue;
			}
			keyPathsNanos += System.nanoTime() - keyPathsStart;
			Map<List<String>, int[]> byKeyPath = aggregate(keyPaths, covered);
			cache.put(target.path(), new CachedFile(text, run, byKeyPath));
			result.put(target.path(), new FileCounts(byKeyPath, target.stale()));
		}
		timing.computeNanos = System.nanoTime() - computeStart;
		timing.hitsNanos = hitsNanos;
		timing.keyPathsNanos = keyPathsNanos;
		timing.cached = cachedFiles;
		return Map.copyOf(result);
	}

	/** Covered and coverable lines by key path: each line counts on its member's path and every prefix of it. */
	static Map<List<String>, int[]> aggregate(List<List<String>> keyPaths, List<Boolean> covered) {
		Map<List<String>, int[]> byKeyPath = new HashMap<>();
		for (int i = 0; i < keyPaths.size(); i++) {
			List<String> keyPath = keyPaths.get(i);
			for (int depth = 0; depth <= keyPath.size(); depth++) {
				int[] count = byKeyPath.computeIfAbsent(List.copyOf(keyPath.subList(0, depth)), k -> new int[2]);
				count[1]++;
				if (covered.get(i)) {
					count[0]++;
				}
			}
		}
		return byKeyPath;
	}

	/** The counts of a file or member node; null for other nodes and those without coverable lines. */
	@Nullable Counts countsOf(DefaultMutableTreeNode node) {
		List<String> keyPath = new ArrayList<>();
		DefaultMutableTreeNode fileNode = node;
		while (fileNode != null && fileNode.getUserObject() instanceof MemberDiff member) {
			keyPath.addFirst(member.key());
			fileNode = (DefaultMutableTreeNode) fileNode.getParent();
		}
		if (fileNode == null || !(fileNode.getUserObject() instanceof FileDiff file)) {
			return null;
		}
		FileCounts fileCounts = counts.get(file.path());
		int[] count = fileCounts == null ? null : fileCounts.byKeyPath().get(keyPath);
		return count == null ? null : new Counts(count[0], count[1], fileCounts.stale());
	}

	/** The legend's text: the run and when it ran, the time alone when today; empty without a run. */
	String legend() {
		if (run == null) {
			return "";
		}
		Date when = new Date(run.timestamp());
		boolean today = DateFormatUtil.formatDate(when).equals(DateFormatUtil.formatDate(new Date()));
		return run.name() + " · " + (today ? DateFormatUtil.formatTime(when) : DateFormatUtil.formatDateTime(when));
	}

	/** The message over the treemap while the fill is chosen and there is nothing to show yet; null otherwise. */
	@Nullable TreemapPanel.Overlay overlay() {
		if (lines == null || !active.getAsBoolean()) {
			return null;
		}
		if (run == null) {
			return new TreemapPanel.Overlay("No coverage data\nRun with Coverage, or reopen a run in Run | Manage Coverage Reports", false, true);
		}
		if (computing) {
			// the treemap stays usable through indexing, which can take minutes
			return DumbService.isDumb(project) ? new TreemapPanel.Overlay("Waiting for indexing…", true, false)
					: new TreemapPanel.Overlay("Analysing coverage…", true, true);
		}
		return problem == null ? null : new TreemapPanel.Overlay(problem, false, false);
	}

	/** Whether a computation is in progress. */
	boolean computing() {
		return computing;
	}
}
