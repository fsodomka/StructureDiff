package com.fsodomka.structurediff;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;

import java.util.List;

/**
 * One changed file with its member-level changes. {@code members} is null when the file has no structure view.
 * {@code linesAdded}/{@code linesRemoved} count the changed lines of the whole file; 0 for binary files.
 */
public record FileDiff(Change change, String path, FileType fileType, FileStatus status,
					   String beforeText, String afterText, int linesAdded, int linesRemoved, List<MemberDiff> members) {

	FileDiff withMembers(List<MemberDiff> newMembers) {
		return new FileDiff(change, path, fileType, status, beforeText, afterText, linesAdded, linesRemoved, newMembers);
	}
}
