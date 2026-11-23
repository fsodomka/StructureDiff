package com.fsodomka.structurediff.reason;

import com.fsodomka.structurediff.FileDiffs;
import com.fsodomka.structurediff.StatusColors;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.review.ReviewsDirs;
import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.intellij.diff.util.Side;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.diff.Diff;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The edit reasons of recent agent sessions: the {@code edits/*.jsonl} files in the reviews directory of each repository
 * root, anchored in the documents of the changed files and shown as gutter icons there. A record that arrives while the
 * IDE runs is anchored at once, so its range follows later edits; one loaded later (e.g. after a restart) is anchored at
 * its recorded lines, which may have drifted, and marked approximate. EDT only, except reading.
 */
@Service(Service.Level.PROJECT)
public final class EditReasonsService implements Disposable {
	static final String EDITS_DIR = "edits";
	/** Shared with the View Options toggle; defaults on to preserve the existing behavior. */
	public static final String SHOW_OPTION_KEY = "structureDiff.showEditReasons";
	private static final String SUFFIX = ".jsonl";
	/**
	 * Older records are left out, a backstop: a reason on code the snapshot no longer shows changed already dims, so the
	 * age only bounds how much is read.
	 */
	private static final Duration MAX_AGE = Duration.ofDays(7);
	/**
	 * Characters, without whitespace, a record's text needs for a match to place it exactly: a short one ({@code }}, {@code
	 * return null;}) is found on many lines, and the nearest of them need not be the one the reason was about.
	 */
	static final int MIN_EXACT_TEXT = 16;
	private static final Logger LOG = Logger.getInstance(EditReasonsService.class);

	/**
	 * A reason placed in its file.
	 *
	 * @param file   null when the record names no file or it doesn't exist
	 * @param marker null without a file or lines
	 */
	public record Anchored(EditReason reason, VirtualFile root, @Nullable VirtualFile file, @Nullable RangeMarker marker,
                           boolean approximate) {
		/** Identifies the anchor across loads and in the kinds: its root and the reason's identity. */
		public String key() {
			return EditReasonsService.key(root, reason);
		}

		/** Absolute path of the changed file, or null for a change kept by tool call. */
		public @Nullable String path() {
			return reason.file() == null ? null : root.getPath() + "/" + reason.file();
		}
	}

	private final Project project;
	/** Records from before this are not anchored live: their lines may have drifted. */
	private final Instant started = Instant.now();
	private final Map<String, Anchored> anchored = new LinkedHashMap<>();
	/** The gutter icons of the anchors; EDT only. */
	private final EditReasonHighlighter highlighter;
	/** The gutter hover and click reveal; disposed with this service. */
	private final EditReasonHover hover;
	/** See {@link EditReasonMembers}; by file path, accessed from pooled threads. */
	private final Map<String, EditReasonMembers.CachedKeyPaths> keyPathCache = new ConcurrentHashMap<>();
	/** Invalidates a .jsonl read already in progress when edit reasons are switched off. */
	private final AtomicInteger loadGeneration = new AtomicInteger();
	/** By anchor key, the document stamp at which a record's text was not found; written in the read action. */
	private final Map<String, Long> gone = new ConcurrentHashMap<>();
	/** The changed files of the snapshot the tool window shows, for the kind of change at each anchor; empty until it did. */
	private volatile List<FileDiff> files = List.of();
	/** Invalidates a kind computation in progress when a newer one, or a newer anchor set, starts. */
	private final AtomicInteger kindsGeneration = new AtomicInteger();
	/** The last computed kinds, by root and reason identity; EDT. */
	private Map<String, ChangeKind> kinds = Map.of();
	/** Line changes per file of the shown snapshot, so the gutter's pencils and the tree's marks diff each file once. */
	private volatile Map<FileDiff, LineChanges> lineChanges = new ConcurrentHashMap<>();
	public EditReasonsService(Project project) {
		this.project = project;
		highlighter = new EditReasonHighlighter(project);
		// the new directory may not be in the VFS yet
		ApplicationManager.getApplication().getMessageBus().connect(this)
				.subscribe(StructureDiffSettings.ReviewsDirListener.TOPIC, this::refreshAndLoad);
		hover = new EditReasonHover(project, highlighter);
		Disposer.register(this, hover);
	}

	/** The reason under {@code point} in {@code editor}'s gutter: the hover's lookup, apart from the balloon it shows. */
	@Nullable
	EditReasonHighlighter.Hint hintAt(Editor editor, Point point) {
		return hover.hintAt(editor, point);
	}

	/** See {@link EditReasonHover#revealRanges}. */
	void revealRanges(Editor editor, List<RangeMarker> ranges, Color color) {
		hover.revealRanges(editor, ranges, color);
	}

	/**
	 * Shows {@code target} as a click on its gutter pencil does: opens its file with the caret at the start of the range,
	 * tints the reason's places in the pencil's color until the caret moves (no selection, whose blue says nothing of the
	 * reason), and selects the place in the Review panel. A removed reason opens the diff instead; one on code
	 * the snapshot doesn't change only moves the caret, as its grey pencil. EDT.
	 */
	public void showReason(Anchored target) {
		RangeMarker marker = target.marker();
		VirtualFile file = target.file();
		if (marker == null || !marker.isValid() || file == null || !file.isValid()) {
			return;
		}
		ChangeKind kind = kind(target);
		project.getMessageBus().syncPublisher(EditReasonsListener.TOPIC).reasonClicked(target.reason(), marker);
		if (kind == ChangeKind.REMOVED) {
			openBefore(marker);
			return;
		}
		new OpenFileDescriptor(project, file, marker.getStartOffset()).navigate(true);
		Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
		if (editor == null || editor.getDocument() != marker.getDocument() || kind == null) {
			return;
		}
		// the places of the reason, as the gutter tints them: the ones with the same text in the same run, where the
		// snapshot shows a change
		List<RangeMarker> places = new ArrayList<>();
		for (Anchored other : anchored.values()) {
			RangeMarker range = other.marker();
			if (range != null && range.isValid() && range.getDocument() == marker.getDocument()
					&& Objects.equals(other.reason().session(), target.reason().session())
					&& Objects.equals(other.reason().reason(), target.reason().reason()) && kind(other) != null) {
				places.add(range);
			}
		}
		revealRanges(editor, places, target.reason().skill() ? StatusColors.SKILL_TINT : EditReasonGutter.color(kind));
	}

	public static EditReasonsService getInstance(Project project) {
		return project.getService(EditReasonsService.class);
	}

	/** Whether reasons should be read or shown, as set by View Options. */
	public static boolean isEnabled() {
		return PropertiesComponent.getInstance().getBoolean(SHOW_OPTION_KEY, true);
	}

	/** Called after the View Options value changes: cancel reads when off, reload when on. */
	public void viewOptionChanged() {
		if (isEnabled()) {
			// Re-read the current VFS immediately, then refresh from disk for records written outside the IDE.
			load();
			refreshAndLoad();
			return;
		}
		// Keep the cached reasons for a quick re-enable; display is gated by isEnabled().
		loadGeneration.incrementAndGet();
		highlighter.clear();
		project.getMessageBus().syncPublisher(EditReasonsListener.TOPIC).reasonsChanged();
	}

	Map<String, EditReasonMembers.CachedKeyPaths> keyPathCache() {
		return keyPathCache;
	}

	/** For tests: the document stamps at which records' texts were not found, by anchor key. */
	Map<String, Long> goneStamps() {
		return gone;
	}

	public Collection<Anchored> reasons() {
		return anchored.values();
	}

	/**
	 * The {@code edits} directories of {@code rootPaths}, as paths: the VFS listener compares each event against them, and
	 * a lookup there can be a slow operation on the EDT. Resolve them once per batch of events, not per event.
	 */
	static List<String> editsPaths(List<String> rootPaths) {
		String reviewsDir = StructureDiffSettings.getInstance().reviewsDir();
		return rootPaths.stream().map(root -> root + "/" + reviewsDir + "/" + EDITS_DIR).toList();
	}

	/** Whether {@code file} is an edits file directly in one of {@code editsPaths}. */
	static boolean isEditsFile(VirtualFile file, List<String> editsPaths) {
		VirtualFile dir = file.getParent();
		return file.getName().endsWith(SUFFIX) && dir != null && dir.getName().equals(EDITS_DIR)
				&& editsPaths.stream().anyMatch(path -> FileUtil.pathsEqual(path, dir.getPath()));
	}

	/**
	 * Whether {@code dir} is or contains one of {@code editsPaths}: a directory created with its files (the mod creates
	 * {@code edits/} on its first write) comes as one event for the directory, none for the files in it.
	 */
	static boolean containsEditsDir(VirtualFile dir, List<String> editsPaths) {
		return dir.isDirectory() && editsPaths.stream().anyMatch(path -> FileUtil.isAncestor(dir.getPath(), path, false));
	}

	private static @Nullable VirtualFile editsDir(VirtualFile root) {
		VirtualFile reviews = ReviewsDirs.reviewsDir(root);
		VirtualFile dir = reviews == null ? null : reviews.findChild(EDITS_DIR);
		return dir != null && dir.isDirectory() ? dir : null;
	}

	/**
	 * As {@link #load()}, after refreshing the reviews directories from disk: the VFS may hold edits files as they were
	 * when the IDE last ran, and the agent may have written them since.
	 */
	public void refreshAndLoad() {
		if (!isEnabled()) {
			return;
		}
		// resolving the directories is a VFS lookup, a slow operation on the EDT, where this runs
		ReadAction.nonBlocking(() -> {
					List<VirtualFile> reviews = new ArrayList<>();
					for (VirtualFile root : ReviewsDirs.roots(project)) {
						VirtualFile dir = ReviewsDirs.reviewsDir(root);
						if (dir != null) {
							reviews.add(dir);
						}
					}
					return reviews;
				})
				.finishOnUiThread(ModalityState.defaultModalityState(), this::refreshFromDisk)
				.expireWith(this)
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	private void refreshFromDisk(List<VirtualFile> reviews) {
		if (reviews.isEmpty()) {
			// nothing to refresh from disk; refreshFiles rejects an empty list
			load();
			return;
		}
		LocalFileSystem.getInstance().refreshFiles(reviews, true, true,
				() -> ApplicationManager.getApplication().invokeLater(() -> {
					if (isEnabled()) {
						load();
					}
				}, project.getDisposed()));
	}

	/** Reads all edits files of all roots again in the background, then anchors and publishes. */
	public void load() {
		if (!isEnabled()) {
			return;
		}
		int current = loadGeneration.incrementAndGet();
		List<VirtualFile> roots = ReviewsDirs.roots(project);
		ReadAction.nonBlocking(() -> read(roots, current))
				.finishOnUiThread(ModalityState.defaultModalityState(), reads -> {
					if (!project.isDisposed() && isCurrentLoad(current)) {
						apply(reads);
					}
				})
				.coalesceBy(this)
				.expireWith(this)
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	private boolean isCurrentLoad(int current) {
		return isEnabled() && current == loadGeneration.get();
	}

	/** Called when the tool window shows another snapshot: the kind of change at each anchor comes from its files. */
	public void snapshotChanged(List<FileDiff> files) {
		if (project.isDisposed()) {
			return;
		}
		this.files = List.copyOf(files);
		// the previous snapshot's diffs are no longer needed; the gutter's kinds and the tree's marks share this one's
		// a new map, not a cleared one: a pool thread still diffing the previous snapshot puts its result in the old map,
		// which goes with it, instead of back into this one
		lineChanges = new ConcurrentHashMap<>();
		updateKinds();
	}

	/**
	 * Computes the kind of change at each anchor from the snapshot's files, off the EDT, then shows the anchors with it.
	 * Nothing is shown until the tool window pushed its files once; a file the snapshot doesn't hold has no kind.
	 */
	private void updateKinds() {
		List<Anchored> anchors = List.copyOf(anchored.values());
		List<FileDiff> shown = files;
		int current = kindsGeneration.incrementAndGet();
		if (shown.isEmpty()) {
			setKinds(Map.of());
			highlighter.sync(anchors, Map.of());
			return;
		}
		ReadAction.nonBlocking(() -> computeKinds(anchors, shown))
				.expireWith(this)
				// any modality: this only repaints icons, and a deferred finish (a popup open while the mode changed) left
				// the previous kinds showing, as seen in the sandbox
				.finishOnUiThread(ModalityState.any(), computed -> {
					if (!project.isDisposed() && current == kindsGeneration.get()) {
						setKinds(computed);
						highlighter.sync(anchored.values(), computed);
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	private void setKinds(Map<String, ChangeKind> computed) {
		if (computed.equals(kinds)) {
			return;
		}
		kinds = computed;
		project.getMessageBus().syncPublisher(EditReasonsListener.TOPIC).kindsChanged();
	}

	/**
	 * The kind of change the shown snapshot makes at {@code anchored}'s range, as its gutter pencil shows it; null when the
	 * snapshot shows none there (the pencil is dimmed) or doesn't hold the file (e.g. an ignored one), or no snapshot was
	 * shown yet. EDT.
	 */
	public @Nullable ChangeKind kind(Anchored anchored) {
		return kinds.get(anchored.key());
	}

	/** The kind of change at each anchor's first line, by root and reason identity; anchors the snapshot doesn't cover are left out. */
	private Map<String, ChangeKind> computeKinds(List<Anchored> anchors, List<FileDiff> files) {
		Map<String, FileDiff> byPath = new HashMap<>();
		files.forEach(file -> byPath.put(file.path(), file));
		Map<String, ChangeKind> result = new HashMap<>();
		for (Anchored anchored : anchors) {
			ProgressManager.checkCanceled();
			RangeMarker marker = anchored.marker();
			String path = anchored.path();
			if (marker == null || !marker.isValid() || path == null || !byPath.containsKey(path)) {
				continue;
			}
			MarkerLines lines = MarkerLines.of(marker.getDocument(), marker);
			ChangeKind kind = lineChanges(byPath.get(path)).kind(lines.first(), lines.last());
			if (kind != null) {
				result.put(anchored.key(), kind);
			}
		}
		return result;
	}

	/**
	 * Opens the diff of the file {@code at} is in, scrolled on its before side to the lines the change there deleted: the
	 * removed code a removed reason's pencil stands for. Nothing when the shown snapshot doesn't hold the file.
	 */
	void openBefore(RangeMarker at) {
		FileDiff diff = shownDiff(at);
		if (diff != null) {
			LineChanges.BeforeLines lines = beforeLines(diff, at);
			FileDiffs.open(project, diff, lines == null ? null : Pair.create(Side.LEFT, lines.first()));
		}
	}

	/** How many lines the change at {@code at}'s lines deleted, from the shown snapshot; 0 when it tells none. */
	int deletedLines(RangeMarker at) {
		FileDiff diff = shownDiff(at);
		LineChanges.BeforeLines lines = diff == null ? null : beforeLines(diff, at);
		return lines == null ? 0 : lines.last() - lines.first() + 1;
	}

	/** The shown snapshot's diff of the file {@code at} is in; null when it holds none or {@code at} became invalid. */
	private @Nullable FileDiff shownDiff(RangeMarker at) {
		VirtualFile file = at.isValid() ? FileDocumentManager.getInstance().getFile(at.getDocument()) : null;
		return file == null ? null : fileDiff(file.getPath());
	}

	/** The before-text lines the change at {@code at}'s lines deleted or replaced; see {@link LineChanges#beforeLines}. */
	private LineChanges.@Nullable BeforeLines beforeLines(FileDiff diff, RangeMarker at) {
		MarkerLines lines = MarkerLines.of(at.getDocument(), at);
		return lineChanges(diff).beforeLines(lines.first(), lines.last());
	}

	/** Marks the old lines of {@code diff}'s reasons on the before side of its diff, {@code before}. */
	public void markBefore(Editor before, FileDiff diff) {
		if (!isEnabled()) {
			return;
		}
		List<Anchored> ofFile = anchored.values().stream()
				.filter(anchored -> diff.path().equals(anchored.path()) && anchored.marker() != null && anchored.marker().isValid())
				.toList();
		if (!ofFile.isEmpty()) {
			EditReasonHighlighter.markBefore(project, before, ofFile, lineChanges(diff));
		}
	}

	/** The shown snapshot's file at {@code path}; null when it holds none. */
	private @Nullable FileDiff fileDiff(String path) {
		return files.stream().filter(file -> file.path().equals(path)).findFirst().orElse(null);
	}

	/**
	 * The line changes of {@code file}, whose kind the gutter's pencils and the tree's marks both show; diffed once per file
	 * of the shown snapshot, whichever pool thread asks first.
	 */
	LineChanges lineChanges(FileDiff file) {
		return lineChanges.computeIfAbsent(file, LineChanges::of);
	}

	/**
	 * The kind of the change that covers the anchor's range: added when it only adds lines, removed when it only deletes
	 * them (the diff names the line after a deletion), modified when it does both. Null when no change covers it.
	 */
	static @Nullable ChangeKind kind(List<Diff.Change> changes, int firstLine, int lastLine) {
		for (Diff.Change change : changes) {
			int start = change.line1;
			int end = change.line1 + Math.max(change.inserted, 1) - 1;
			if (firstLine <= end && start <= lastLine) {
				if (change.inserted > 0 && change.deleted > 0) {
					return ChangeKind.MODIFIED;
				}
				return change.inserted > 0 ? ChangeKind.ADDED : ChangeKind.REMOVED;
			}
		}
		return null;
	}

	/**
	 * A record with its file and document resolved and its lines placed, all in the read action: the lookups and the text
	 * search are slow operations on the EDT.
	 *
	 * @param lines null without a document; {@link #GONE} when the recorded text is no longer there
	 */
	private record Read(VirtualFile root, EditReason reason, @Nullable VirtualFile file, @Nullable Document document,
						@Nullable Lines lines) {
	}

	/** The lines, 0-based, an anchor is to cover in its document, and whether they may have drifted. */
	private record Lines(int start, int end, boolean approximate) {
	}

	/** For a record whose text is gone: it is dropped. */
	private static final Lines GONE = new Lines(-1, -1, false);

	private Read resolve(VirtualFile root, EditReason reason) {
		VirtualFile file = reason.file() == null ? null : root.findFileByRelativePath(reason.file());
		Document document = file == null || !file.isValid() ? null : FileDocumentManager.getInstance().getDocument(file);
		Lines lines = document == null || document.getLineCount() == 0 ? null : lines(key(root, reason), reason, document);
		return new Read(root, reason, file, document, lines);
	}

	/**
	 * Where a record goes in {@code document}. Its lines may have drifted: one from before the service started, or a live
	 * one whose document changed again before it arrived (the next call of a parallel batch, an import added above). When
	 * it carries its lines' text and the lines don't hold it, the text found nearest to the recorded lines places it
	 * exactly. None found (the code changed again or is gone) drops an older record ({@link #GONE}); a live one keeps its
	 * recorded lines, marked approximate. A miss is remembered with the document's stamp, so later loads, one per agent
	 * edit, don't search an unchanged document again.
	 */
	private Lines lines(String key, EditReason reason, Document document) {
		boolean approximate = reason.time().isBefore(started);
		if (reason.start() < 1) {
			// a change without lines (a whole-file write, a shell edit) stands for the file: its pencil on the first line
			return new Lines(0, 0, false);
		}
		int last = document.getLineCount() - 1;
		int startLine = Math.min(reason.start() - 1, last);
		int endLine = Math.min(Math.max(reason.end() - 1, startLine), last);
		if (reason.text() == null || reason.text().isBlank()) {
			return new Lines(startLine, endLine, approximate);
		}
		List<String> wanted = reason.text().lines().map(String::strip).toList();
		// a record's lines are those of the file right after its edit; a later edit (the next call of a parallel batch,
		// an import added above) may have moved them before the record arrived, so even a live one is checked
		if (!approximate && startLine + wanted.size() <= document.getLineCount() && matches(document, wanted, startLine)) {
			return new Lines(startLine, endLine, false);
		}
		long stamp = document.getModificationStamp();
		Long goneAt = gone.get(key);
		if (goneAt != null && goneAt == stamp) {
			return approximate ? GONE : new Lines(startLine, endLine, true);
		}
		int found = findLines(document, wanted, startLine);
		if (found < 0) {
			gone.put(key, stamp);
			// an older record whose text is gone is dropped; a live one may only be ahead of its document (a file the
			// agent wrote on disk, not yet reloaded): it keeps its lines, marked as they may be wrong
			return approximate ? GONE : new Lines(startLine, endLine, true);
		}
		gone.remove(key);
		boolean distinctive = String.join("", wanted).replaceAll("\\s", "").length() >= MIN_EXACT_TEXT;
		// a long change's text is only its first lines (the mod clips it): its extent is the record's
		int span = Math.max(wanted.size(), reason.end() - reason.start() + 1);
		return new Lines(found, found + Math.max(span, 1) - 1, !distinctive);
	}

	private static String key(VirtualFile root, EditReason reason) {
		return root.getPath() + "|" + reason.identity();
	}

	private List<Read> read(List<VirtualFile> roots, int current) {
		Instant oldest = Instant.now().minus(MAX_AGE);
		List<Read> result = new ArrayList<>();
		for (VirtualFile root : roots) {
			if (!isCurrentLoad(current)) {
				return List.of();
			}
			VirtualFile dir = editsDir(root);
			if (dir == null) {
				continue;
			}
			List<EditsFile> files;
			try {
				files = editsFiles(dir);
			}
			catch (IOException e) {
				LOG.warn("Cannot list edit reasons " + dir.getPath(), e);
				continue;
			}
			for (EditsFile file : files) {
				// a non-blocking read action yields to a write action only here, so typing doesn't wait for a week of records
				ProgressManager.checkCanceled();
				if (!isCurrentLoad(current)) {
					return List.of();
				}
				// a session file last written before the cut-off holds no record after it: skip parsing it
				if (!file.name().endsWith(SUFFIX) || file.stamp() < oldest.toEpochMilli()) {
					continue;
				}
				try {
					for (EditReason reason : EditReason.read(file.text().compute(),
							file.name().substring(0, file.name().length() - SUFFIX.length()))) {
						ProgressManager.checkCanceled();
						if (reason.time().isAfter(oldest)) {
							result.add(resolve(root, reason));
						}
					}
				}
				catch (IOException e) {
					LOG.warn("Cannot read edit reasons " + file.name(), e);
				}
			}
		}
		return result;
	}

	/** A session's edits file: its name, when it was last written and how to read it. */
	private record EditsFile(String name, long stamp, ThrowableComputable<String, IOException> text) {
	}

	/**
	 * The files in {@code dir}, read from disk where it is local: the agent's hooks write them from another process, and
	 * after an IDE restart the VFS kept its old copies even through the startup refresh (seen in the log: a deleted session
	 * file still read, another 9 KB short), so pending records showed although the agent had given them reasons since.
	 * Other file systems (the tests' in-memory one) go through the VFS.
	 */
	private static List<EditsFile> editsFiles(VirtualFile dir) throws IOException {
		List<EditsFile> files = new ArrayList<>();
		Path nioDir = dir.getFileSystem().getNioPath(dir);
		if (nioDir == null) {
			for (VirtualFile file : dir.getChildren()) {
				if (!file.isDirectory()) {
					files.add(new EditsFile(file.getName(), file.getTimeStamp(), () -> VfsUtilCore.loadText(file)));
				}
			}
			return files;
		}
		try (Stream<Path> paths = Files.list(nioDir)) {
			for (Path path : paths.filter(Files::isRegularFile).toList()) {
				files.add(new EditsFile(path.getFileName().toString(), Files.getLastModifiedTime(path).toMillis(),
						() -> Files.readString(path, StandardCharsets.UTF_8)));
			}
		}
		return files;
	}

	/**
	 * Keeps the anchors of changes seen before (they followed the edits since), anchors new ones, drops the rest. An anchor
	 * without a valid marker is placed again: its file may not have been in the VFS yet, or its range was deleted.
	 */
	private void apply(List<Read> reads) {
		Map<String, Anchored> previous = new LinkedHashMap<>(anchored);
		anchored.clear();
		for (Read read : reads) {
			String key = key(read.root(), read.reason());
			Anchored old = previous.remove(key);
			if (old != null && old.marker() != null && old.marker().isValid()) {
				anchored.put(key, new Anchored(read.reason(), old.root(), old.file(), old.marker(), old.approximate()));
				continue;
			}
			if (old != null) {
				release(old);
			}
			Anchored placed = anchor(read);
			if (placed != null) {
				anchored.put(key, placed);
			}
		}
		previous.values().forEach(EditReasonsService::release);
		// the drops of records no longer read need not be remembered
		gone.keySet().retainAll(reads.stream().map(read -> key(read.root(), read.reason())).toList());
		updateKinds();
		project.getMessageBus().syncPublisher(EditReasonsListener.TOPIC).reasonsChanged();
	}

	/** The anchor at the lines the read action placed; null for a record whose text is gone. */
	private @Nullable Anchored anchor(Read read) {
		EditReason reason = read.reason();
		Document document = read.document();
		Lines lines = read.lines();
		if (lines == GONE) {
			return null;
		}
		if (document == null || lines == null || document.getLineCount() == 0) {
			return new Anchored(reason, read.root(), read.file(), null, reason.time().isBefore(started));
		}
		// the lines fit the text this marks: a write action between the read action and this EDT part makes the platform run
		// the read action again (NonBlockingReadActionImpl compares its write-action counter); the clamp is only defensive
		int last = document.getLineCount() - 1;
		int startLine = Math.min(lines.start(), last);
		int endLine = Math.min(Math.max(lines.end(), startLine), last);
		RangeMarker marker = document.createRangeMarker(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine));
		return new Anchored(reason, read.root(), read.file(), marker, lines.approximate());
	}

	/** The first line of {@code wanted} in the document (compared without indentation), nearest to {@code near}; -1 when absent. */
	static int findLines(Document document, List<String> wanted, int near) {
		int lines = document.getLineCount();
		int count = Math.max(wanted.size(), 1);
		for (int distance = 0; distance < lines; distance++) {
			ProgressManager.checkCanceled();
			for (int start : new int[]{near - distance, near + distance}) {
				if (start >= 0 && start + count <= lines && matches(document, wanted, start)) {
					return start;
				}
			}
		}
		return -1;
	}

	private static boolean matches(Document document, List<String> wanted, int start) {
		CharSequence text = document.getImmutableCharSequence();
		for (int i = 0; i < wanted.size(); i++) {
			int line = start + i;
			String actual = text.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().strip();
			if (!actual.equals(wanted.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static void release(Anchored anchored) {
		if (anchored.marker() != null) {
			anchored.marker().dispose();
		}
	}

	@Override
	public void dispose() {
		highlighter.clear();
		anchored.values().forEach(EditReasonsService::release);
		anchored.clear();
	}
}
