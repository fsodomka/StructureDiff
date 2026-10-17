package com.fsodomka.structurediff.review;

import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/** Where a finding is shown: {@code highlighter} is null when the finding is not highlighted (see {@link ReviewAnchor.Status}). */
record Placement(Finding finding, VirtualFile root, @Nullable VirtualFile file, ReviewAnchor.Status status,
				 @Nullable RangeHighlighter highlighter) {

	/** Current offset of the highlight, or -1; also for a file-level finding. */
	int offset() {
		return highlighter != null && highlighter.isValid() && status != ReviewAnchor.Status.FILE ? highlighter.getStartOffset() : -1;
	}

	/** Current 1-based line of the highlight, else the reviewed line. */
	int line() {
		if (highlighter == null || !highlighter.isValid() || status == ReviewAnchor.Status.FILE) {
			return finding.line();
		}
		return highlighter.getDocument().getLineNumber(highlighter.getStartOffset()) + 1;
	}
}
