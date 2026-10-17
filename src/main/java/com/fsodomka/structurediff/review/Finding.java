package com.fsodomka.structurediff.review;

import org.jetbrains.annotations.Nullable;

/**
 * One result of a review SARIF file. Lines and columns are 1-based, offsets 0-based; -1 where the SARIF has none.
 * {@code path} is relative to the reviewed repository root, or a URL. {@code rank} orders findings, 0 is the most severe
 * (see {@link ReviewSeverity#rank}).
 */
record Finding(String fingerprint, String ruleId, ReviewSeverity severity, int rank, String message, @Nullable String fix,
			   String path, int line, int column, int offset, int length, @Nullable String snippet) {

	/** First line of the message. */
	String title() {
		int end = message.indexOf('\n');
		return end < 0 ? message : message.substring(0, end);
	}
}
