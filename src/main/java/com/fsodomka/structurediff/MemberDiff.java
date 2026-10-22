package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;

import javax.swing.Icon;
import java.util.List;

/**
 * A structure element (class, method, field, ...) that was added, modified or removed.
 * Offsets point into the before/after text; -1 when that side does not exist.
 * {@code key} is the label, with "#n" appended to the n-th element of a repeated label, so it is unique among siblings.
 * {@code beforeLabel} differs from {@code label} when the signature changed.
 * {@code linesAdded}/{@code linesRemoved} count the changed lines of the member, all its lines when added or removed.
 * {@code container} is false for code members (methods, functions, variables) whose nested changes can be hidden.
 * {@code changeId} identifies the member's content, including the content of its children; unlike the offsets it does not
 * change when edits elsewhere in the file move the member, and it changes when a modified member is modified again.
 */
public record MemberDiff(String key, String label, String beforeLabel, Icon icon, FileStatus status, int beforeOffset,
						 int afterOffset, int linesAdded, int linesRemoved, boolean container, long changeId,
						 List<MemberDiff> children) {

	MemberDiff withChildren(List<MemberDiff> newChildren) {
		return new MemberDiff(key, label, beforeLabel, icon, status, beforeOffset, afterOffset, linesAdded, linesRemoved, container,
				changeId, newChildren);
	}
}
