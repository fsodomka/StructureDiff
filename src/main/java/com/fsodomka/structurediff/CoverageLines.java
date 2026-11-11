package com.fsodomka.structurediff;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/**
 * The lines the IDE's selected coverage run executed. A project service registered only with the bundled Coverage
 * plugin, so {@code project.getService(CoverageLines.class)} is null without it.
 */
public interface CoverageLines {
	/** The coverage run the IDE shows; {@code timestamp} as {@link System#currentTimeMillis}. */
	record Run(String name, long timestamp) {
	}

	/**
	 * The coverable lines of a file, 0-based and ascending, with whether the run executed each; {@code lines} and
	 * {@code covered} have the same length.
	 */
	record Hits(int[] lines, boolean[] covered) {
	}

	/** A loaded run, indexed by class. */
	interface Data {
		/** The lines of {@code file}; null when the run has none for it. Call in a read action. */
		@Nullable Hits hits(VirtualFile file);
	}

	/** The selected run, null when there is none. Call on the EDT. */
	@Nullable Run run();

	/**
	 * Loads the selected run; null when there is none. Reading a large run takes seconds and cannot be cancelled, so
	 * call it on a pooled thread outside a read action, which would hold up every write action meanwhile. The last
	 * run loaded is kept.
	 */
	@Nullable Data load();

	/** Runs {@code changed} on the EDT after another run was selected or its data was loaded. */
	void onChange(Runnable changed, Disposable parent);
}
