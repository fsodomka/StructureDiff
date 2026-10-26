package com.fsodomka.structurediff.history;

import com.intellij.openapi.vcs.history.VcsFileRevision;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One commit that changed the tracked member, or the end of its history. {@code older} is null for the oldest revision.
 * The key paths are the member's keys in the newer and older revision, outermost first; equal when the keys did not change.
 */
public record HistoryRow(VcsFileRevision newer, @Nullable VcsFileRevision older, Kind kind, String detail,
						 List<String> newerKeyPath, List<String> olderKeyPath) {

	public enum Kind {
		ADDED("added"), MOVED("moved"), RENAMED("renamed"), SIGNATURE("signature"), MODIFIED("modified"),
		/** The member can no longer be tracked; {@code detail} says why. */
		END("");

		public final String text;

		Kind(String text) {
			this.text = text;
		}
	}
}
