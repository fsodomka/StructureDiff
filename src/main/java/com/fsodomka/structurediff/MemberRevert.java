package com.fsodomka.structurediff;

import com.fsodomka.structurediff.StructureDiffComputer.Located;
import com.fsodomka.structurediff.StructureDiffComputer.Step;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FileStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the edit of the current text that reverts one member to its before version. Ranges come from parsing the
 * before text, the text the diff was computed from and the current text, like {@link StructureDiffComputer#currentOffset}.
 */
final class MemberRevert {
	/** Replace {@code [start, end)} of the current text with {@code text}. */
	record Edit(int start, int end, String text) {
	}

	/** Either {@code edit} or the {@code error} why the member cannot be reverted. */
	record Result(@Nullable Edit edit, @Nullable String error) {
		static Result error(String error) {
			return new Result(null, error);
		}
	}

	private MemberRevert() {
	}

	/** Call inside a read action. {@code path} runs from the outermost member to the one to revert. */
	static Result compute(Project project, FileDiff file, String fileName, FileType fileType, CharSequence current,
						  List<MemberDiff> path) {
		MemberDiff member = path.getLast();
		List<Step> afterPath = path.stream().map(m -> new Step(m.key(), StructureDiffComputer.name(m.label()))).toList();
		List<Step> beforePath = path.stream().map(m -> new Step(m.key(), StructureDiffComputer.name(m.beforeLabel()))).toList();
		String before = StringUtil.notNullize(file.beforeText());
		String after = StringUtil.notNullize(file.afterText());
		Located now = StructureDiffComputer.locate(project, fileName, fileType, current, afterPath);
		if (member.status() == FileStatus.DELETED) {
			return restore(project, fileName, fileType, before, current, now, beforePath, afterPath);
		}
		Located shown = StructureDiffComputer.locate(project, fileName, fileType, after, afterPath);
		if (now == null || shown == null || !now.range().subSequence(current).toString().equals(shown.range().substring(after))) {
			return Result.error("The member changed since the last refresh. Refresh and try again.");
		}
		if (member.status() == FileStatus.ADDED) {
			return new Result(deleteLines(current, now.range()), null);
		}
		Located old = StructureDiffComputer.locate(project, fileName, fileType, before, beforePath);
		if (old == null) {
			return Result.error("The member was not found in the original version.");
		}
		return new Result(new Edit(now.range().getStartOffset(), now.range().getEndOffset(), old.range().substring(before)), null);
	}

	/** Inserts a removed member next to the surviving sibling that preceded it, else the one that followed it. */
	private static Result restore(Project project, String fileName, FileType fileType, String before, CharSequence current,
								  @Nullable Located now, List<Step> beforePath, List<Step> afterPath) {
		Located old = StructureDiffComputer.locate(project, fileName, fileType, before, beforePath);
		if (old == null) {
			return Result.error("The member was not found in the original version.");
		}
		// a same-named sibling (an overload) also matches by name: only identical text means it is back
		if (now != null && now.range().subSequence(current).toString().equals(old.range().substring(before))) {
			return Result.error("The member is already back in the file.");
		}
		List<Step> beforeParent = beforePath.subList(0, beforePath.size() - 1);
		List<Step> afterParent = afterPath.subList(0, afterPath.size() - 1);
		if (old.previous() != null) {
			Located anchor = StructureDiffComputer.locate(project, fileName, fileType, current, with(afterParent, old.previous()));
			Located oldAnchor = StructureDiffComputer.locate(project, fileName, fileType, before, with(beforeParent, old.previous()));
			if (anchor != null && oldAnchor != null) {
				int at = anchor.range().getEndOffset();
				return new Result(new Edit(at, at, before.substring(oldAnchor.range().getEndOffset(), old.range().getEndOffset())), null);
			}
		}
		if (old.next() != null) {
			Located anchor = StructureDiffComputer.locate(project, fileName, fileType, current, with(afterParent, old.next()));
			Located oldAnchor = StructureDiffComputer.locate(project, fileName, fileType, before, with(beforeParent, old.next()));
			if (anchor != null && oldAnchor != null) {
				int at = anchor.range().getStartOffset();
				return new Result(new Edit(at, at, before.substring(old.range().getStartOffset(), oldAnchor.range().getStartOffset())), null);
			}
		}
		return Result.error("No neighbor of the removed member is left to place it next to.");
	}

	private static List<Step> with(List<Step> parent, Step last) {
		List<Step> result = new ArrayList<>(parent);
		result.add(last);
		return result;
	}

	/** Deletes {@code range}, its lines when nothing else is on them, and one blank line above them. */
	private static Edit deleteLines(CharSequence text, TextRange range) {
		int start = range.getStartOffset();
		int end = range.getEndOffset();
		int lineStart = StringUtil.lastIndexOf(text, '\n', 0, start) + 1;
		int lineEnd = StringUtil.indexOf(text, '\n', end);
		if (lineEnd < 0) {
			lineEnd = text.length();
		}
		if (StringUtil.isEmptyOrSpaces(text.subSequence(lineStart, start).toString())
				&& StringUtil.isEmptyOrSpaces(text.subSequence(end, lineEnd).toString())) {
			start = lineStart;
			end = Math.min(lineEnd + 1, text.length());
			int previousLineStart = start == 0 ? -1 : StringUtil.lastIndexOf(text, '\n', 0, start - 1) + 1;
			if (previousLineStart >= 0 && StringUtil.isEmptyOrSpaces(text.subSequence(previousLineStart, start).toString())) {
				start = previousLineStart;
			}
		}
		return new Edit(start, end, "");
	}
}
