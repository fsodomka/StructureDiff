package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.StructureDiffComputer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** The shown review findings by file and by the members that contain them, for the Structure Diff tree. */
public final class FindingMembers {
	private static final Logger LOG = Logger.getInstance(FindingMembers.class);
	private static final List<String> RANK_NAMES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

	/**
	 * One finding. {@code keyPath} are the keys of the members enclosing it, outermost first, as Structure Diff keys
	 * them; empty when it is in no member or its file has no structure. {@code title} starts with the severity.
	 * {@code finding} selects it in the Review Findings tool window.
	 */
	public record MemberFinding(List<String> keyPath, String title, ReviewSeverity severity, int rank, Finding finding) {
		/** The icon of the severity, as in the editor gutter. */
		public Icon icon() {
			return severity.icon;
		}
	}

	/** A finding with what the background computation needs from the EDT; {@code text} is null when there is no document. */
	private record Target(Finding finding, String path, @Nullable VirtualFile file, @Nullable CharSequence text, int offset) {
	}

	/**
	 * Key paths found in one file text for the given offsets. The text is the document's immutable text, compared by
	 * identity: an unchanged document returns the same instance, so an unchanged file is not parsed again.
	 */
	record CachedKeyPaths(CharSequence text, int[] offsets, List<List<String>> keyPaths) {
	}

	private FindingMembers() {
	}

	/**
	 * Takes the placed findings on the EDT and returns their mapping to members, to run in a read action on a pooled
	 * thread. The mapping is by absolute file path, each file's findings sorted by rank, most severe first.
	 */
	public static Supplier<Map<String, List<MemberFinding>>> prepare(Project project) {
		return prepare(project, ReviewFindingsService.getInstance(project).placements());
	}

	static Supplier<Map<String, List<MemberFinding>>> prepare(Project project, Collection<Placement> placements) {
		Map<String, CachedKeyPaths> cache = ReviewFindingsService.getInstance(project).keyPathCache();
		List<Target> targets = new ArrayList<>(placements.size());
		for (Placement placement : placements) {
			// possibly fixed: its reviewed line now belongs to other code, and the tool window greys it
			if (placement.status() == ReviewAnchor.Status.GONE) {
				continue;
			}
			VirtualFile file = placement.file();
			String path = file != null ? file.getPath() : placement.root().getPath() + "/" + placement.finding().path();
			Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
			int offset = document == null ? -1 : offset(placement, document);
			targets.add(new Target(placement.finding(), path, file, document == null ? null : document.getImmutableCharSequence(), offset));
		}
		return () -> compute(project, targets, cache);
	}

	/** The highlight start, else the first non-blank character of the reviewed line; -1 when the line is not in the document. */
	private static int offset(Placement placement, Document document) {
		int offset = placement.offset();
		if (offset >= 0) {
			return offset;
		}
		int line = placement.line() - 1;
		return line < 0 || line >= document.getLineCount() ? -1 : ReviewAnchor.indentEnd(document, line);
	}

	private static Map<String, List<MemberFinding>> compute(Project project, List<Target> targets, Map<String, CachedKeyPaths> cache) {
		Map<String, List<Target>> byPath = new LinkedHashMap<>();
		for (Target target : targets) {
			byPath.computeIfAbsent(target.path(), p -> new ArrayList<>()).add(target);
		}
		Map<String, List<MemberFinding>> result = new LinkedHashMap<>();
		byPath.forEach((path, fileTargets) -> {
			List<List<String>> keyPaths = keyPaths(project, fileTargets, cache);
			List<MemberFinding> found = new ArrayList<>(fileTargets.size());
			for (int i = 0; i < fileTargets.size(); i++) {
				Finding finding = fileTargets.get(i).finding();
				found.add(new MemberFinding(keyPaths.get(i), title(finding), finding.severity(), finding.rank(), finding));
			}
			found.sort(Comparator.comparingInt(MemberFinding::rank));
			result.put(path, List.copyOf(found));
		});
		cache.keySet().retainAll(byPath.keySet());
		return result;
	}

	/** Key paths of the targets of one file; empty ones where the file or the offset is unknown, or parsing fails. */
	private static List<List<String>> keyPaths(Project project, List<Target> targets, Map<String, CachedKeyPaths> cache) {
		List<List<String>> result = new ArrayList<>(Collections.nCopies(targets.size(), List.of()));
		Target first = targets.getFirst();
		if (first.file() == null || first.text() == null) {
			return result;
		}
		int[] indices = IntStream.range(0, targets.size()).filter(i -> targets.get(i).offset() >= 0).toArray();
		int[] offsets = Arrays.stream(indices).map(i -> targets.get(i).offset()).toArray();
		try {
			CachedKeyPaths cached = cache.get(first.path());
			List<List<String>> found;
			if (cached != null && cached.text() == first.text() && Arrays.equals(cached.offsets(), offsets)) {
				found = cached.keyPaths();
			}
			else {
				found = StructureDiffComputer.memberKeyPaths(project, first.file().getName(), first.file().getFileType(),
						first.text(), offsets);
				cache.put(first.path(), new CachedKeyPaths(first.text(), offsets, found));
			}
			for (int i = 0; i < indices.length; i++) {
				result.set(indices[i], found.get(i));
			}
		}
		catch (ProcessCanceledException e) {
			throw e;
		}
		catch (RuntimeException e) {
			LOG.warn("Cannot find the members of the findings in " + first.path(), e);
		}
		return result;
	}

	private static String title(Finding finding) {
		return RANK_NAMES.get(Math.clamp(finding.rank(), 0, RANK_NAMES.size() - 1)) + " · " + finding.title();
	}
}
