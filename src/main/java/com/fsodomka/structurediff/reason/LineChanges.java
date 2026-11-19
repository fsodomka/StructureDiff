package com.fsodomka.structurediff.reason;

import com.fsodomka.structurediff.diff.FileDiff;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The line changes from a file's before to its after text: the kind of change at a reason's lines, which the editor
 * gutter pencils and the tree marks with, and whether those lines can still be changed at all.
 *
 * @param changes null when the file is too big to tell
 */
record LineChanges(@Nullable List<Diff.Change> changes) {
	/** For a path the shown snapshot doesn't hold: no change is known to cover its lines. */
	static final LineChanges NONE = new LineChanges(List.of());

	static LineChanges of(FileDiff file) {
		String before = file.beforeText() == null ? "" : file.beforeText();
		String after = file.afterText() == null ? "" : file.afterText();
		List<Diff.Change> changes = new ArrayList<>();
		// this runs in non-blocking read actions, which yield to a write action only at a check; the diff itself has none
		ProgressManager.checkCanceled();
		try {
			for (Diff.Change change = Diff.buildChanges(before, after); change != null; change = change.link) {
				changes.add(change);
			}
		}
		catch (FilesTooBigForDiffException e) {
			// too big to tell: no kind
			return new LineChanges(null);
		}
		return new LineChanges(changes);
	}

	/** As {@link EditReasonsService#kind}: null when no change covers the lines, or the file is too big to tell. */
	@Nullable
	ChangeKind kind(int firstLine, int lastLine) {
		return changes == null ? null : EditReasonsService.kind(changes, firstLine, lastLine);
	}

	/**
	 * The before-text lines a change covering the after-text lines deleted or replaced, first to last, 0-based; null when
	 * no change covers them, the change only adds lines, or the file is too big to tell.
	 */
	@Nullable
	BeforeLines beforeLines(int firstLine, int lastLine) {
		if (changes == null) {
			return null;
		}
		for (Diff.Change change : changes) {
			int end = change.line1 + Math.max(change.inserted, 1) - 1;
			if (firstLine <= end && change.line1 <= lastLine) {
				return change.deleted > 0 ? new BeforeLines(change.line0, change.line0 + change.deleted - 1) : null;
			}
		}
		return null;
	}

	/** Lines of a file's before-text, 0-based and inclusive. */
	record BeforeLines(int first, int last) {
	}

	/** True when a change covers the lines; true when the file is too big to tell, so its reasons are kept. */
	boolean overlaps(int firstLine, int lastLine) {
		return changes == null || kind(firstLine, lastLine) != null;
	}
}
