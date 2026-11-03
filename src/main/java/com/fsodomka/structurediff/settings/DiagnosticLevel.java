package com.fsodomka.structurediff.settings;

import com.intellij.lang.annotation.HighlightSeverity;

/** The severities of IDE diagnostics shown in the Structure Diff, each turned on in the settings. */
public enum DiagnosticLevel {
	ERROR("Error"), WARNING("Warning"), WEAK_WARNING("Weak warning");

	public final String text;

	DiagnosticLevel(String text) {
		this.text = text;
	}

	/** The level of a severity of weak warning or above. */
	public static DiagnosticLevel of(HighlightSeverity severity) {
		return severity.compareTo(HighlightSeverity.ERROR) >= 0 ? ERROR
				: severity.compareTo(HighlightSeverity.WARNING) >= 0 ? WARNING : WEAK_WARNING;
	}
}
