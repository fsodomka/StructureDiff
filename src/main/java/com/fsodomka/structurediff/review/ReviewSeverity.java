package com.fsodomka.structurediff.review;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Severity of a finding, highlighted like the inspection severity of the same name. */
public enum ReviewSeverity {
	ERROR(CodeInsightColors.ERRORS_ATTRIBUTES, AllIcons.General.Error),
	WARNING(CodeInsightColors.WARNINGS_ATTRIBUTES, AllIcons.General.Warning),
	WEAK_WARNING(CodeInsightColors.WEAK_WARNING_ATTRIBUTES, AllIcons.General.Information);

	/** Qodana severities, most severe first; the index is the rank. */
	private static final List<String> QODANA_SEVERITIES = List.of("critical", "high", "moderate", "low", "info");

	final TextAttributesKey attributes;
	public final Icon icon;

	ReviewSeverity(TextAttributesKey attributes, Icon icon) {
		this.attributes = attributes;
		this.icon = icon;
	}

	/** Lower case name with its plural, for counts: "1 error", "2 weak warnings". */
	public String countText(int count) {
		String name = name().toLowerCase(Locale.ROOT).replace('_', ' ');
		return count + " " + name + (count == 1 ? "" : "s");
	}

	/** The count of each of {@code severities}, most severe first: "1 error · 2 weak warnings"; empty when there are none. */
	static String countsText(Collection<ReviewSeverity> severities) {
		int[] counts = new int[values().length];
		severities.forEach(severity -> counts[severity.ordinal()]++);
		return Arrays.stream(values()).filter(severity -> counts[severity.ordinal()] > 0)
				.map(severity -> severity.countText(counts[severity.ordinal()])).collect(Collectors.joining(" · "));
	}

	/** From {@code properties.ideaSeverity} when present, else from the SARIF {@code level}. */
	static ReviewSeverity of(@Nullable String ideaSeverity, @Nullable String level) {
		ReviewSeverity explicit = ideaSeverity == null ? null : switch (ideaSeverity) {
			case "ERROR" -> ERROR;
			case "WARNING" -> WARNING;
			case "WEAK WARNING" -> WEAK_WARNING;
			default -> null;
		};
		if (explicit != null) {
			return explicit;
		}
		return switch (level == null ? "" : level) {
			case "error" -> ERROR;
			case "note", "none" -> WEAK_WARNING;
			default -> WARNING;
		};
	}

	/**
	 * Sort rank, 0 the most severe: from {@code properties.qodanaSeverity} when present, which tells the skill's CRITICAL
	 * from HIGH (both {@link #ERROR}), else from {@code severity} as High, Moderate or Low.
	 */
	static int rank(@Nullable String qodanaSeverity, ReviewSeverity severity) {
		int rank = qodanaSeverity == null ? -1 : QODANA_SEVERITIES.indexOf(qodanaSeverity.toLowerCase(Locale.ROOT));
		return rank >= 0 ? rank : severity.ordinal() + 1;
	}
}
