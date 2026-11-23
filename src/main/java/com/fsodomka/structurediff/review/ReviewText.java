package com.fsodomka.structurediff.review;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	private static final int MIN_LEAD = 30;
	private static final int MAX_LEAD = 160;
	private static final int LONG_LEAD = 70;
	private static final Pattern CLAUSE = Pattern.compile(",\\s+(?:and|but|so|which|while|because)\\s");

	/**
	 * The "why" of a finding title or edit reason for a tree row: its first line up to the first sentence end (". "), or to
	 * the first ": " or "; " that leaves at least {@value #MIN_LEAD} characters (so "Fix: ..." is not cut). A lead still longer
	 * than {@value #LONG_LEAD} stops at its first ", and" / ", but" / ", so" / ", which" / ", while" / ", because", and is
	 * shortened with an ellipsis past {@value #MAX_LEAD}. The rest is for the detail pane.
	 */
	/** Whether the '.' at {@code dot} ends an abbreviation ("e.g.", "i.e.", "etc.", "vs."), not a sentence. */
	private static boolean abbreviation(String line, int dot) {
		String before = line.substring(0, dot).toLowerCase();
		return before.endsWith("e.g") || before.endsWith("i.e") || before.endsWith(" etc") || before.endsWith(" vs");
	}

	static String lead(String text) {
		int newline = text.indexOf('\n');
		String line = (newline < 0 ? text : text.substring(0, newline)).strip();
		int cut = line.length();
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			boolean end = i + 1 == line.length() || line.charAt(i + 1) == ' ';
			if (end && (c == '.' && !abbreviation(line, i) || i >= MIN_LEAD && (c == ':' || c == ';'))) {
				cut = i;
				break;
			}
		}
		if (cut > LONG_LEAD) {
			Matcher clause = CLAUSE.matcher(line.substring(0, cut));
			if (clause.find(MIN_LEAD)) {
				cut = clause.start();
			}
		}
		return StringUtil.shortenTextWithEllipsis(line.substring(0, cut).strip(), MAX_LEAD, 0);
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
