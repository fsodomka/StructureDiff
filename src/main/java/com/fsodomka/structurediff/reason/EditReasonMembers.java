package com.fsodomka.structurediff.reason;

import com.fsodomka.structurediff.StatusColors;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberLocator;
import com.fsodomka.structurediff.review.FindingMembers;
import com.fsodomka.structurediff.review.ReviewAnchor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** Which member each anchored edit reason is in, for the Structure Diff tree and treemap; as {@link FindingMembers}. */
public final class EditReasonMembers {
	private static final Logger LOG = Logger.getInstance(EditReasonMembers.class);

	/**
	 * A reason on a member; an empty key path puts it on the file only.
	 *
	 * @param reason null while pending
	 * @param kind   the kind of change at the reason's lines in the snapshot, null when the snapshot doesn't tell it: the
	 *               tree marks its node grey then, as the gutter pencils the anchor
	 * @param skill  a skill's mark (e.g. a code walkthrough's), not an agent's edit reason
	 * @param anchored the placed reason, which a click on the node's mark opens in the editor; null in tests
	 */
	public record MemberReason(List<String> keyPath, @Nullable String reason, boolean pending, boolean approximate, Instant time,
							   @Nullable ChangeKind kind, boolean skill, EditReasonsService.@Nullable Anchored anchored) {

		/** An agent's edit reason. */
		public MemberReason(List<String> keyPath, @Nullable String reason, boolean pending, boolean approximate, Instant time,
							@Nullable ChangeKind kind) {
			this(keyPath, reason, pending, approximate, time, kind, false, null);
		}

		/**
		 * The reason a click on a node's mark shows: its first, the earliest in time (the list is newest first), among
		 * those on changed code, which the click can tint; else the earliest placed one. Null when none is placed.
		 */
		public static @Nullable MemberReason first(List<MemberReason> reasons) {
			List<MemberReason> placed = reasons.reversed().stream().filter(reason -> reason.anchored() != null).toList();
			return placed.stream().filter(reason -> reason.kind() != null).findFirst()
					.orElse(placed.isEmpty() ? null : placed.getFirst());
		}

		/**
		 * The color of a node's one mark for its {@code reasons}, as the gutter pencils them: grey when none is on changed
		 * code, purple when all are a skill's; null for the label's own color, so an agent's reason wins over a skill's.
		 */
		public static @Nullable Color markColor(List<MemberReason> reasons) {
			return reasons.stream().allMatch(reason -> reason.kind() == null) ? StatusColors.UNKNOWN
					: reasons.stream().allMatch(MemberReason::skill) ? StatusColors.SKILL
					: null;
		}
	}

	/** As {@link FindingMembers}'s: one file's key paths, reused while its text (by identity) and offsets are the same. */
	record CachedKeyPaths(CharSequence text, int[] offsets, List<List<String>> keyPaths) {
	}

	/** @param firstLine the anchor's lines in the document, 0-based; -1 when not placed */
	private record Target(EditReasonsService.Anchored anchored, String path, VirtualFile file, CharSequence text, int offset,
						  int firstLine, int lastLine) {
	}

	private EditReasonMembers() {
	}

	/**
	 * Takes the anchored reasons on the EDT and returns their mapping to members, to run in a read action on a pooled
	 * thread: by absolute file path, newest first. Reasons without a file are left out, and so are approximate ones
	 * (loaded after a restart, their lines may have drifted) outside every changed range of their file in {@code shown}:
	 * a reason on code that is not changed would explain the wrong code.
	 */
	public static Supplier<Map<String, List<MemberReason>>> prepare(Project project, List<FileDiff> shown) {
		Map<String, FileDiff> shownByPath = new HashMap<>();
		shown.forEach(file -> shownByPath.put(file.path(), file));
		List<Target> targets = new ArrayList<>();
		for (EditReasonsService.Anchored anchored : EditReasonsService.getInstance(project).reasons()) {
			String path = anchored.path();
			if (path == null) {
				continue;
			}
			RangeMarker marker = anchored.marker();
			boolean placed = marker != null && marker.isValid() && anchored.file() != null;
			// the first non-blank character of the first line, as for findings: the indentation belongs to the parent
			MarkerLines lines = placed ? MarkerLines.of(marker.getDocument(), marker) : new MarkerLines(-1, -1);
			int firstLine = lines.first();
			int lastLine = lines.last();
			targets.add(new Target(anchored, path, anchored.file(),
					placed ? marker.getDocument().getImmutableCharSequence() : null,
					placed ? ReviewAnchor.indentEnd(marker.getDocument(), firstLine) : -1, firstLine, lastLine));
		}
		Map<String, CachedKeyPaths> cache = EditReasonsService.getInstance(project).keyPathCache();
		return () -> compute(project, targets, shownByPath, cache);
	}

	private static Map<String, List<MemberReason>> compute(Project project, List<Target> targets, Map<String, FileDiff> shown,
														   Map<String, CachedKeyPaths> cache) {
		Map<String, List<Target>> byPath = new LinkedHashMap<>();
		EditReasonsService service = EditReasonsService.getInstance(project);
		Map<String, LineChanges> changes = new HashMap<>();
		for (Target target : targets) {
			FileDiff file = shown.get(target.path());
			// one diff per file for the gutter's pencils and the tree's marks; see EditReasonsService.lineChanges
			LineChanges lines = changes.computeIfAbsent(target.path(),
					p -> file == null ? LineChanges.NONE : service.lineChanges(file));
			if (target.anchored().approximate() && target.firstLine() >= 0 && file != null
					&& !lines.overlaps(target.firstLine(), target.lastLine())) {
				continue;
			}
			byPath.computeIfAbsent(target.path(), p -> new ArrayList<>()).add(target);
		}
		Map<String, List<MemberReason>> result = new LinkedHashMap<>();
		byPath.forEach((path, fileTargets) -> {
			List<List<String>> keyPaths = keyPaths(project, fileTargets, cache);
			LineChanges lines = changes.get(path);
			List<ChangeKind> kinds = fileTargets.stream().map(target -> lines.kind(target.firstLine(), target.lastLine())).toList();
			FileDiff file = shown.get(path);
			if (file != null) {
				beforeKeyPaths(project, file, fileTargets, kinds, lines).forEach(keyPaths::set);
			}
			List<MemberReason> found = new ArrayList<>();
			for (int i = 0; i < fileTargets.size(); i++) {
				EditReasonsService.Anchored anchored = fileTargets.get(i).anchored();
				found.add(new MemberReason(keyPaths.get(i), anchored.reason().reason(), anchored.reason().pending(),
						anchored.approximate(), anchored.reason().time(), kinds.get(i), anchored.reason().skill(), anchored));
			}
			found.sort(Comparator.comparing(MemberReason::time).reversed());
			result.put(path, List.copyOf(found));
		});
		cache.keySet().retainAll(byPath.keySet());
		return result;
	}

	/**
	 * Key paths in {@code file}'s before text of the targets whose code was only removed, by index: the lines a removal
	 * leaves are those after the gap, in whatever member follows it, so the reason belongs to the member that was deleted,
	 * which only the old text holds. Parses the old text once, and only when there are such targets.
	 */
	private static Map<Integer, List<String>> beforeKeyPaths(Project project, FileDiff file, List<Target> targets,
															  List<ChangeKind> kinds, LineChanges lines) {
		String before = file.beforeText();
		Target placed = targets.stream().filter(target -> target.file() != null).findFirst().orElse(null);
		if (before == null || placed == null) {
			return Map.of();
		}
		List<Integer> indices = new ArrayList<>();
		List<Integer> offsets = new ArrayList<>();
		for (int i = 0; i < targets.size(); i++) {
			LineChanges.BeforeLines old = kinds.get(i) == ChangeKind.REMOVED
					? lines.beforeLines(targets.get(i).firstLine(), targets.get(i).lastLine()) : null;
			int offset = old == null ? -1 : StringUtil.lineColToOffset(before, old.first(), 0);
			if (offset >= 0) {
				// the first non-blank character of the first deleted line, as for the current text
				while (offset < before.length() && StringUtil.isWhiteSpace(before.charAt(offset))) {
					offset++;
				}
				indices.add(i);
				offsets.add(offset);
			}
		}
		if (indices.isEmpty()) {
			return Map.of();
		}
		Map<Integer, List<String>> result = new HashMap<>();
		try {
			List<List<String>> found = MemberLocator.memberKeyPaths(project, placed.file().getName(), placed.file().getFileType(),
					before, offsets.stream().mapToInt(Integer::intValue).toArray());
			for (int i = 0; i < indices.size(); i++) {
				result.put(indices.get(i), found.get(i));
			}
		}
		catch (ProcessCanceledException e) {
			throw e;
		}
		catch (RuntimeException e) {
			LOG.warn("Cannot find the removed members of the edit reasons in " + file.path(), e);
		}
		return result;
	}

	/** Key paths of the targets of one file, parsing it once; empty ones where the offset is unknown or parsing fails. */
	private static List<List<String>> keyPaths(Project project, List<Target> targets, Map<String, CachedKeyPaths> cache) {
		List<List<String>> result = new ArrayList<>(Collections.nCopies(targets.size(), List.of()));
		Target placed = targets.stream().filter(target -> target.text() != null).findFirst().orElse(null);
		if (placed == null) {
			return result;
		}
		int[] indices = IntStream.range(0, targets.size()).filter(i -> targets.get(i).offset() >= 0).toArray();
		int[] offsets = Arrays.stream(indices).map(i -> targets.get(i).offset()).toArray();
		try {
			CachedKeyPaths cached = cache.get(placed.path());
			List<List<String>> found;
			if (cached != null && cached.text() == placed.text() && Arrays.equals(cached.offsets(), offsets)) {
				found = cached.keyPaths();
			}
			else {
				found = MemberLocator.memberKeyPaths(project, placed.file().getName(), placed.file().getFileType(), placed.text(), offsets);
				cache.put(placed.path(), new CachedKeyPaths(placed.text(), offsets, found));
			}
			for (int i = 0; i < indices.length; i++) {
				result.set(indices[i], found.get(i));
			}
		}
		catch (ProcessCanceledException e) {
			throw e;
		}
		catch (RuntimeException e) {
			LOG.warn("Cannot find the members of the edit reasons in " + placed.path(), e);
		}
		return result;
	}
}
