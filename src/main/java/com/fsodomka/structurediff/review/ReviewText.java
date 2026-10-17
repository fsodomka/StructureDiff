package com.fsodomka.structurediff.review;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.Nullable;

/** Tooltip, copy and status texts of a finding. */
final class ReviewText {
	private ReviewText() {
	}

	static String html(Finding finding, ReviewAnchor.Status status) {
		StringBuilder html = new StringBuilder("<b>").append(escape(finding.ruleId())).append("</b> &middot; review finding<br>")
				.append(escape(finding.message()));
		if (finding.fix() != null) {
			html.append("<br><br><b>Fix:</b> ").append(escape(finding.fix()));
		}
		String note = statusNote(status);
		if (note != null) {
			html.append("<br><br><i>").append(escape(note)).append("</i>");
		}
		return XmlStringUtil.wrapInHtml(html);
	}

	/** The details pane of the tool window: the finding with its current location. */
	static String details(Placement placement) {
		Finding finding = placement.finding();
		StringBuilder html = new StringBuilder("<b>").append(escape(finding.ruleId())).append("</b><br>")
				.append(escape(finding.path()));
		if (placement.line() > 0) {
			html.append(':').append(placement.line());
		}
		String note = statusNote(placement.status());
		if (note != null) {
			html.append("<br><i>").append(escape(note)).append("</i>");
		}
		html.append("<br><br>").append(escape(finding.message()));
		if (finding.fix() != null) {
			html.append("<br><br><b>Fix:</b> ").append(escape(finding.fix()));
		}
		return XmlStringUtil.wrapInHtml(html);
	}

	static String plain(Finding finding) {
		StringBuilder text = new StringBuilder("[").append(finding.ruleId()).append("] ").append(finding.path());
		if (finding.line() > 0) {
			text.append(':').append(finding.line());
		}
		text.append('\n').append(finding.message());
		if (finding.fix() != null) {
			text.append("\n\nFix: ").append(finding.fix());
		}
		return text.toString();
	}

	/** Short form of {@link #statusNote} for the tool window. */
	static @Nullable String statusLabel(ReviewAnchor.Status status) {
		return switch (status) {
			case EXACT -> null;
			case MOVED -> "moved";
			case STALE -> "stale location";
			case GONE -> "possibly fixed";
			case MISSING_FILE -> "file not found";
			case FILE -> "file-level";
		};
	}

	static @Nullable String statusNote(ReviewAnchor.Status status) {
		return switch (status) {
			case EXACT -> null;
			case MOVED -> "The reviewed code has moved since the review.";
			case STALE -> "The reviewed code is not found unambiguously near this line any more; the location may be stale.";
			case GONE -> "The reviewed code is no longer in the file; possibly fixed.";
			case MISSING_FILE -> "The file was not found.";
			case FILE -> "The finding is about the whole file.";
		};
	}

	private static String escape(String text) {
		return StringUtil.escapeXmlEntities(text).replace("\n", "<br>");
	}
}
