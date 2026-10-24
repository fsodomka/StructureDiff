package com.fsodomka.structurediff;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The IDE's errors and warnings on the changed files by the members that contain them, for the Structure Diff tree.
 * Only files with an open editor are analysed by the daemon, so only those have diagnostics.
 */
final class DiagnosticMembers {
	private static final Logger LOG = Logger.getInstance(DiagnosticMembers.class);

	/** The severities shown, each turned on in the settings. */
	enum Level {
		ERROR("Error"), WARNING("Warning"), WEAK_WARNING("Weak warning");

		final String text;

		Level(String text) {
			this.text = text;
		}

		/** The level of a severity of weak warning or above. */
		static Level of(HighlightSeverity severity) {
			return severity.compareTo(HighlightSeverity.ERROR) >= 0 ? ERROR
					: severity.compareTo(HighlightSeverity.WARNING) >= 0 ? WARNING : WEAK_WARNING;
		}
	}

	/** One diagnostic; {@code keyPath} as in {@code MemberFinding}, empty when it is in no member. */
	record MemberDiagnostic(List<String> keyPath, String message, HighlightSeverity severity, int offset) {
		/** The Problems view's outlined marks, colored in both themes and unlike a review finding's filled icon. */
		Icon icon() {
			return level() == Level.ERROR ? AllIcons.Nodes.ErrorIntroduction : AllIcons.Nodes.WarningIntroduction;
		}

		Level level() {
			return Level.of(severity);
		}
	}

	/** Diagnostics by absolute path, most severe first; {@code analysed} the paths of the files with an open editor. */
	record Diagnostics(Map<String, List<MemberDiagnostic>> byPath, Set<String> analysed) {
		static final Diagnostics NONE = new Diagnostics(Map.of(), Set.of());
	}

	/** Key paths found in one document text, compared by identity, for the given offsets. */
	record CachedKeyPaths(CharSequence text, int[] offsets, List<List<String>> keyPaths) {
	}

	private record Target(String path, VirtualFile file, Document document, CharSequence text) {
	}

	private DiagnosticMembers() {
	}

	/**
	 * Takes the open documents of {@code files} on the EDT and returns their diagnostics, to run in a read action on a
	 * pooled thread. {@code cache} is kept by the caller across calls and only touched by the returned supplier.
	 */
	static Supplier<Diagnostics> prepare(Project project, List<FileDiff> files, Set<Level> shown,
										 Map<String, CachedKeyPaths> cache) {
		Map<String, VirtualFile> open = new HashMap<>();
		for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
			open.put(file.getPath(), file);
		}
		List<Target> targets = new ArrayList<>();
		for (FileDiff fileDiff : files) {
			VirtualFile file = fileDiff.status() == FileStatus.DELETED ? null : open.get(fileDiff.path());
			Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
			if (document != null) {
				targets.add(new Target(fileDiff.path(), file, document, document.getImmutableCharSequence()));
			}
		}
		return () -> compute(project, targets, Set.copyOf(shown), cache);
	}

	private static Diagnostics compute(Project project, List<Target> targets, Set<Level> shown,
									   Map<String, CachedKeyPaths> cache) {
		Map<String, List<MemberDiagnostic>> byPath = new HashMap<>();
		Set<String> analysed = new HashSet<>();
		for (Target target : targets) {
			analysed.add(target.path());
			if (target.document().getImmutableCharSequence() != target.text()) {
				// edited since prepare, so the highlights may not match the text; the daemon's next pass computes again
				continue;
			}
			List<HighlightInfo> infos = new ArrayList<>();
			DaemonCodeAnalyzerEx.processHighlights(target.document(), project, HighlightSeverity.WEAK_WARNING, 0, target.text().length(), info -> {
				if (info.getDescription() != null && info.getStartOffset() <= target.text().length()
						&& shown.contains(Level.of(info.getSeverity()))) {
					infos.add(info);
				}
				return true;
			});
			if (infos.isEmpty()) {
				continue;
			}
			int[] offsets = infos.stream().mapToInt(HighlightInfo::getStartOffset).toArray();
			List<List<String>> keyPaths = keyPaths(project, target, offsets, cache);
			List<MemberDiagnostic> found = new ArrayList<>(infos.size());
			for (int i = 0; i < infos.size(); i++) {
				HighlightInfo info = infos.get(i);
				found.add(new MemberDiagnostic(keyPaths.get(i), info.getDescription(), info.getSeverity(), offsets[i]));
			}
			found.sort(Comparator.comparing(MemberDiagnostic::severity, Comparator.reverseOrder())
					.thenComparingInt(MemberDiagnostic::offset));
			byPath.put(target.path(), List.copyOf(found));
		}
		cache.keySet().retainAll(byPath.keySet());
		return new Diagnostics(Map.copyOf(byPath), Set.copyOf(analysed));
	}

	/** Key paths for {@code offsets} in the target's text; empty ones when parsing fails. */
	private static List<List<String>> keyPaths(Project project, Target target, int[] offsets, Map<String, CachedKeyPaths> cache) {
		CachedKeyPaths cached = cache.get(target.path());
		if (cached != null && cached.text() == target.text() && Arrays.equals(cached.offsets(), offsets)) {
			return cached.keyPaths();
		}
		try {
			List<List<String>> found = StructureDiffComputer.memberKeyPaths(project, target.file().getName(),
					target.file().getFileType(), target.text(), offsets);
			cache.put(target.path(), new CachedKeyPaths(target.text(), offsets, found));
			return found;
		}
		catch (ProcessCanceledException e) {
			throw e;
		}
		catch (RuntimeException e) {
			LOG.warn("Cannot find the members of the diagnostics in " + target.path(), e);
			return Collections.nCopies(offsets.length, List.of());
		}
	}
}
