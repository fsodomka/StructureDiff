package com.fsodomka.structurediff;

import com.intellij.ui.JBColor;

/**
 * How a changed member is related to the selected one by the shown usages, and the background it is highlighted with.
 * Caller is soft amber, like the editor's search result highlight; callee teal and both lavender, at the same
 * lightness, so none is taken for the blue selection and all sit well under the green, blue and grey status text.
 */
enum Relation {
	/** Uses the selected member. */
	CALLER(new JBColor(0xFFEFC2, 0x6B5C1F), "↑"),
	/** Used by the selected member. */
	CALLEE(new JBColor(0xC8ECE4, 0x1F5A52), "↓"),
	/** Both uses and is used by the selected member. */
	BOTH(new JBColor(0xE6D8F5, 0x4B3A66), "↕");

	final JBColor background;
	/** Shown after the tree row's label, so the direction does not rest on color alone. */
	final String arrow;

	Relation(JBColor background, String arrow) {
		this.background = background;
		this.arrow = arrow;
	}

	/** This relation and {@code other} of the same member combined; {@code other} may be null. */
	Relation merge(Relation other) {
		return other == null || other == this ? this : BOTH;
	}
}
