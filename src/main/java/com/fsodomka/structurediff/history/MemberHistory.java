package com.fsodomka.structurediff.history;

import com.fsodomka.structurediff.FileDiff;
import com.fsodomka.structurediff.MemberDiff;
import com.fsodomka.structurediff.StructureDiffComputer;
import com.fsodomka.structurediff.history.HistoryRow.Kind;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsFileRevisionEx;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Walks the revisions of a file, newest first, and finds the commits that changed one member, following its key back
 * through signature changes and the file back through renames. No UI; see {@code docs/specs/2026-10-26-member-history-design.md}.
 */
public final class MemberHistory {
	/** Loads revision contents; each Git load is a process, so a few run at once. */
	private static final ExecutorService PREFETCH =
			AppExecutorUtil.createBoundedApplicationPoolExecutor("Member history content", 4);

	/** Revisions loaded ahead of the one compared: two per loading thread. */
	private static final int PREFETCH_AHEAD = 8;
	/** Revision contents kept by {@link #newCache}: more than a batch, so a prefetch is not evicted before its use. */
	private static final int CACHE_SIZE = 128;

	private MemberHistory() {
	}

	/** A thread-safe revision content cache for {@link #walk}, dropping the least recently used beyond {@link #CACHE_SIZE}. */
	public static Map<String, String> newCache() {
		return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
				return size() > CACHE_SIZE;
			}
		});
	}

	/**
	 * Computes in a read action that gives way to write actions, retrying after them, so parsing a large revision
	 * does not hold up typing. Throws {@link ProcessCanceledException} when the current progress is canceled.
	 */
	static <T> T read(ThrowableComputable<T, RuntimeException> computation) {
		return ReadAction.nonBlocking(computation::compute).executeSynchronously();
	}

	/**
	 * Rows found by one {@link #walk}. {@code keyPath} is the member's key path to continue with at {@code nextIndex};
	 * {@code nextIndex} is -1 when the history is complete.
	 */
	public record Batch(List<HistoryRow> rows, List<String> keyPath, int nextIndex) {
	}

	/**
	 * Compares at most {@code maxPairs} consecutive revision pairs from {@code fromIndex}. {@code currentPath} is the
	 * file's path for revisions that do not know their own. Loads contents, so call outside a read action and off the EDT.
	 * {@code onRow} receives each row as it is found. {@code cache} holds revision contents across walks; see {@link #newCache}.
	 */
	public static Batch walk(Project project, List<? extends VcsFileRevision> revisions, FilePath currentPath,
							 List<String> keyPath, int fromIndex, int maxPairs, @Nullable ProgressIndicator indicator,
							 Consumer<HistoryRow> onRow, Map<String, String> cache) {
		// revision contents are loaded ahead in parallel; only following the member's key needs revision order
		int end = (int) Math.min(revisions.size(), (long) fromIndex + maxPairs + 1);
		List<Future<?>> prefetched = new ArrayList<>();
		// a few revisions ahead of the one compared, so few loads are wasted when the walk ends early
		IntFunction<Future<?>> prefetch = j -> {
			for (int k = fromIndex + prefetched.size(); k < Math.min(end, j + PREFETCH_AHEAD + 1); k++) {
				Revision revision = new Revision(revisions.get(k), path(revisions.get(k), currentPath));
				prefetched.add(PREFETCH.submit(() -> StructureDiffComputer.cachedContent(revision, cache)));
			}
			return j - fromIndex < prefetched.size() ? prefetched.get(j - fromIndex) : null;
		};
		try {
			return walk(project, revisions, currentPath, keyPath, fromIndex, maxPairs, indicator, onRow, cache, prefetch);
		}
		finally {
			prefetched.forEach(future -> future.cancel(false));
		}
	}

	private static Batch walk(Project project, List<? extends VcsFileRevision> revisions, FilePath currentPath,
							  List<String> keyPath, int fromIndex, int maxPairs, @Nullable ProgressIndicator indicator,
							  Consumer<HistoryRow> onRow, Map<String, String> cache, IntFunction<Future<?>> prefetched) {
		List<HistoryRow> rows = new ArrayList<>();
		Consumer<HistoryRow> emit = row -> {
			rows.add(row);
			onRow.accept(row);
		};
		List<String> tracked = keyPath;
		for (int i = fromIndex; i < revisions.size(); i++) {
			if (i - fromIndex == maxPairs) {
				return new Batch(rows, tracked, i);
			}
			if (indicator != null) {
				indicator.checkCanceled();
				indicator.setText2(i + " commits checked");
			}
			VcsFileRevision newer = revisions.get(i);
			VcsFileRevision older = i + 1 < revisions.size() ? revisions.get(i + 1) : null;
			FilePath newerPath = path(newer, currentPath);
			FilePath olderPath = older == null ? null : path(older, currentPath);
			boolean moved = olderPath != null && !olderPath.equals(newerPath);
			Change change = new Change(older == null ? null : new Revision(older, olderPath), new Revision(newer, newerPath));
			await(prefetched.apply(i));
			await(prefetched.apply(i + 1));
			FileDiff loaded = StructureDiffComputer.load(change, cache, null);
			if (loaded.afterText() == null || older != null && loaded.beforeText() == null) {
				emit.accept(new HistoryRow(newer, older, Kind.END, "content unavailable", tracked, tracked));
				return new Batch(rows, tracked, -1);
			}
			// a member added since the latest commit has no history; without this check every revision would be walked
			if (i == 0 && !contains(project, newerPath, loaded.afterText(), tracked)) {
				emit.accept(new HistoryRow(newer, older, Kind.END, "not in the latest commit", tracked, tracked));
				return new Batch(rows, tracked, -1);
			}
			if (older != null && loaded.afterText().equals(loaded.beforeText())) {
				if (moved) {
					emit.accept(new HistoryRow(newer, older, Kind.MOVED, paths(olderPath, newerPath), tracked, tracked));
				}
				continue;
			}
			FileDiff file = read(() -> StructureDiffComputer.withMembers(project, loaded));
			if (file.members() == null) {
				emit.accept(new HistoryRow(newer, older, Kind.END, "history ends here (no structure)", tracked, tracked));
				return new Batch(rows, tracked, -1);
			}
			List<MemberDiff> found = find(file.members(), tracked);
			List<String> before = beforeKeyPath(tracked, found);
			if (found.size() < tracked.size()) {
				// the member is unchanged; an enclosing member may still have changed its key
				if (older == null) {
					emit.accept(new HistoryRow(newer, null, Kind.END, "history ends here", tracked, tracked));
					return new Batch(rows, tracked, -1);
				}
				if (moved) {
					emit.accept(new HistoryRow(newer, older, Kind.MOVED, paths(olderPath, newerPath), tracked, before));
				}
				tracked = before;
				continue;
			}
			MemberDiff member = found.getLast();
			emit.accept(row(newer, older, member, moved ? paths(olderPath, newerPath) : null, tracked, before));
			if (member.status() == FileStatus.ADDED) {
				return new Batch(rows, tracked, -1);
			}
			tracked = before;
		}
		return new Batch(rows, tracked, -1);
	}

	/** Waits for a prefetch, cancellably; its failure is left to the sequential load, which logs it. */
	private static void await(@Nullable Future<?> future) {
		if (future == null) {
			return;
		}
		try {
			ProgressIndicatorUtils.awaitWithCheckCanceled(future);
		}
		catch (ProcessCanceledException e) {
			throw e;
		}
		catch (RuntimeException e) {
			// loaded again below
		}
	}

	/** The file's path at {@code revision}, which follows renames for Git; {@code fallback} when the revision does not know it. */
	public static FilePath path(VcsFileRevision revision, FilePath fallback) {
		return revision instanceof VcsFileRevisionEx ex ? ex.getPath() : fallback;
	}

	private static HistoryRow row(VcsFileRevision newer, @Nullable VcsFileRevision older, MemberDiff member,
								  @Nullable String movedPaths, List<String> newerKeyPath, List<String> olderKeyPath) {
		// a side with no lines is left out, as in "+1" rather than "+1 -0"
		String added = member.linesAdded() > 0 ? "+" + member.linesAdded() : "";
		String removed = member.linesRemoved() > 0 ? "-" + member.linesRemoved() : "";
		String lines = added.isEmpty() || removed.isEmpty() ? added + removed : added + " " + removed;
		if (member.status() == FileStatus.ADDED) {
			return new HistoryRow(newer, older, Kind.ADDED, "+" + member.linesAdded(), newerKeyPath, olderKeyPath);
		}
		boolean relabeled = !member.label().equals(member.beforeLabel());
		if (movedPaths != null) {
			// a rename or signature change in the same commit is kept in the detail
			String renamed = relabeled ? "   " + member.beforeLabel() + " → " + member.label() : "";
			return new HistoryRow(newer, older, Kind.MOVED, movedPaths + renamed + "   " + lines, newerKeyPath, olderKeyPath);
		}
		if (!StructureDiffComputer.name(member.label()).equals(StructureDiffComputer.name(member.beforeLabel()))) {
			return new HistoryRow(newer, older, Kind.RENAMED,
					StructureDiffComputer.name(member.beforeLabel()) + " → " + StructureDiffComputer.name(member.label()),
					newerKeyPath, olderKeyPath);
		}
		if (relabeled) {
			return new HistoryRow(newer, older, Kind.SIGNATURE, member.beforeLabel() + " → " + member.label() + "   " + lines,
					newerKeyPath, olderKeyPath);
		}
		return new HistoryRow(newer, older, Kind.MODIFIED, lines, newerKeyPath, olderKeyPath);
	}

	/**
	 * The key path in the before revision of {@code localChange} of the member at {@code keyPath} in its after revision,
	 * so a member whose signature changed locally is found in the latest commit. Loads contents, so call off the EDT.
	 */
	public static List<String> committedKeyPath(Project project, Change localChange, List<String> keyPath) {
		FileDiff loaded = StructureDiffComputer.load(localChange, new HashMap<>(), null);
		if (loaded.beforeText() == null || loaded.afterText() == null) {
			return keyPath;
		}
		FileDiff file = read(() -> StructureDiffComputer.withMembers(project, loaded));
		return file.members() == null ? keyPath : beforeKeyPath(keyPath, find(file.members(), keyPath));
	}

	/**
	 * Whether {@code text} has the member at {@code keyPath}, matched by key only, as {@link #find} matches it;
	 * a match by name would let the walk go through every revision without finding the key.
	 */
	private static boolean contains(Project project, FilePath path, String text, List<String> keyPath) {
		// a step's name is compared to names, which have no parameters or #n, so the key itself matches no other sibling
		List<StructureDiffComputer.Step> steps = keyPath.stream()
				.map(key -> new StructureDiffComputer.Step(key, key))
				.toList();
		return read(() ->
				StructureDiffComputer.locate(project, path.getName(), path.getFileType(), text, steps) != null);
	}

	private static String paths(FilePath older, FilePath newer) {
		return older.getPath() + " → " + newer.getPath();
	}

	/**
	 * The changed members along {@code keyPath}, outermost first; shorter than {@code keyPath} where a level is
	 * unchanged, since the diff lists no unchanged members and so none of their children.
	 */
	private static List<MemberDiff> find(List<MemberDiff> members, List<String> keyPath) {
		List<MemberDiff> found = new ArrayList<>();
		List<MemberDiff> level = members;
		for (String key : keyPath) {
			MemberDiff match = level.stream()
					.filter(m -> m.status() != FileStatus.DELETED && m.key().equals(key))
					.findFirst().orElse(null);
			if (match == null) {
				break;
			}
			found.add(match);
			level = match.children();
		}
		return found;
	}

	/** {@code keyPath} with the before keys of the {@code found} levels; the other levels kept their keys. */
	private static List<String> beforeKeyPath(List<String> keyPath, List<MemberDiff> found) {
		List<String> result = new ArrayList<>(keyPath);
		for (int i = 0; i < found.size(); i++) {
			result.set(i, found.get(i).beforeKey());
		}
		return List.copyOf(result);
	}

	/** A history revision as a content revision, so {@link StructureDiffComputer#cachedContent} and its cache can be reused. */
	record Revision(VcsFileRevision revision, FilePath path) implements ContentRevision {
		@Override
		public @Nullable String getContent() throws VcsException {
			try {
				byte[] content = revision.loadContent();
				return content == null ? null : new String(content, path.getCharset());
			}
			catch (IOException e) {
				throw new VcsException(e);
			}
		}

		@Override
		public @NotNull FilePath getFile() {
			return path;
		}

		@Override
		public @NotNull VcsRevisionNumber getRevisionNumber() {
			return Objects.requireNonNull(revision.getRevisionNumber());
		}
	}
}
