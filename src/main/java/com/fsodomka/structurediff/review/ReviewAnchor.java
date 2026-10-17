package com.fsodomka.structurediff.review;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.text.StringUtil;

/** Places a finding in the current text of its file, which may have changed since the review. */
final class ReviewAnchor {
	/** How far, in lines, the reviewed code may have moved and still count as the same place. */
	private static final int MAX_MOVE_LINES = 50;

	enum Status {
		/** The reviewed code is where the review saw it, or the finding has no snippet to compare. */
		EXACT,
		/** The reviewed code occurs once in the file, near the reviewed line. */
		MOVED,
		/** The reviewed code is not near the reviewed line, or occurs more than once; the line is highlighted. */
		STALE,
		/** The reviewed code is no longer in the file; not highlighted. */
		GONE,
		/** The file does not exist or has no text; not highlighted. */
		MISSING_FILE,
		/** The finding has no line, and no snippet that occurs once; shown in the gutter of the first line. */
		FILE
	}

	record Anchor(Status status, int start, int end) {
	}

	private ReviewAnchor() {
	}

	static Anchor locate(Document document, Finding finding) {
		int lineCount = document.getLineCount();
		if (finding.line() < 1) {
			return locateInFile(document, finding);
		}
		if (lineCount == 0) {
			return new Anchor(Status.STALE, 0, 0);
		}
		boolean lineExists = finding.line() <= lineCount;
		int line = Math.min(finding.line(), lineCount) - 1;
		int atColumn = Math.min(document.getLineStartOffset(line) + Math.max(finding.column() - 1, 0), document.getLineEndOffset(line));
		String snippet = finding.snippet();
		if (snippet == null || snippet.isEmpty()) {
			return lineAnchor(document, line, lineExists ? Status.EXACT : Status.STALE);
		}
		CharSequence text = document.getImmutableCharSequence();
		// the review may count offsets in CRLF text, so the line and column are tried as well; only on the reviewed line:
		// the same text elsewhere, such as "}", may be other code that edits moved there
		int offset = finding.offset();
		int onLine = offset >= 0 && offset <= text.length() && document.getLineNumber(offset) == line ? offset : -1;
		for (int candidate : new int[]{onLine, atColumn}) {
			if (lineExists && candidate >= 0 && StringUtil.startsWith(text, candidate, snippet)) {
				return new Anchor(Status.EXACT, candidate, candidate + snippet.length());
			}
		}
		int found = StringUtil.indexOf(text, snippet);
		if (found < 0) {
			return new Anchor(Status.GONE, 0, 0);
		}
		// another occurrence, such as "}", is not the reviewed code: which one it is, if any, is unknown
		boolean unique = StringUtil.indexOf(text, snippet, found + 1) < 0;
		if (unique && Math.abs(document.getLineNumber(found) - line) <= MAX_MOVE_LINES) {
			return new Anchor(Status.MOVED, found, found + snippet.length());
		}
		return lineAnchor(document, line, Status.STALE);
	}

	/** A finding without a line: by its snippet if it occurs once, else the whole file. */
	private static Anchor locateInFile(Document document, Finding finding) {
		String snippet = finding.snippet();
		if (snippet == null || snippet.isEmpty()) {
			return new Anchor(Status.FILE, 0, 0);
		}
		CharSequence text = document.getImmutableCharSequence();
		int offset = finding.offset();
		int found = offset >= 0 && StringUtil.startsWith(text, offset, snippet) ? offset : StringUtil.indexOf(text, snippet);
		if (found < 0) {
			return new Anchor(Status.GONE, 0, 0);
		}
		if (found == offset || StringUtil.indexOf(text, snippet, found + 1) < 0) {
			return new Anchor(Status.EXACT, found, found + snippet.length());
		}
		return new Anchor(Status.FILE, 0, 0);
	}

	/** The line without its indentation. */
	private static Anchor lineAnchor(Document document, int line, Status status) {
		return new Anchor(status, indentEnd(document, line), document.getLineEndOffset(line));
	}

	/** Offset of the first non-blank character of {@code line}; its end when it is blank. */
	static int indentEnd(Document document, int line) {
		CharSequence text = document.getImmutableCharSequence();
		int start = document.getLineStartOffset(line);
		int end = document.getLineEndOffset(line);
		while (start < end && Character.isWhitespace(text.charAt(start))) {
			start++;
		}
		return start;
	}
}
