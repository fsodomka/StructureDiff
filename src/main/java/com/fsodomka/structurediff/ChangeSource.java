package com.fsodomka.structurediff;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;

import java.util.Collection;
import java.util.List;

/** Where {@link StructureDiffPanel} gets its changes from. */
interface ChangeSource {
	/** Changes to show; called on a pooled thread. */
	Collection<Change> changes();

	/** Unversioned files to show as added; called on a pooled thread. */
	default List<FilePath> unversioned() {
		return List.of();
	}

	/** Whether {@link #unversioned()} can return files. */
	default boolean hasUnversioned() {
		return false;
	}

	/** Calls {@code onChange} whenever the changes may have changed, until {@code parent} is disposed. */
	void subscribe(Runnable onChange, Disposable parent);

	/**
	 * Whether the difference to the previous refresh is an edit worth flashing; false while the IDE is still loading,
	 * when files and members appear because of the loading, not because they changed.
	 */
	boolean highlightChanges();

	/**
	 * Calls {@code onReplace}, besides {@code onChange}, on changes that replace what is shown, so the refresh shows a busy
	 * spinner, not just the first load; until {@code parent} is disposed.
	 */
	default void subscribeReplacing(Runnable onReplace, Disposable parent) {
	}
}
