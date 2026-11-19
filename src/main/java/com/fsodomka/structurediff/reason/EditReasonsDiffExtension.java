package com.fsodomka.structurediff.reason;

import com.fsodomka.structurediff.FileDiffs;
import com.fsodomka.structurediff.diff.FileDiff;
import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffExtension;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Puts the reasons for removed and replaced code on the before side of this plugin's diffs, where that code still is.
 * The after side is the working file's own document, which shows its pencils already.
 */
final class EditReasonsDiffExtension extends DiffExtension {
	@Override
	public void onViewerCreated(@NotNull FrameDiffTool.DiffViewer viewer, @NotNull DiffContext context,
								@NotNull DiffRequest request) {
		FileDiff file = request.getUserData(FileDiffs.OPENED_FILE_DIFF);
		Project project = context.getProject();
		// side by side only: the unified viewer shows both sides in one document, which has no before lines to mark
		if (file == null || project == null || !(viewer instanceof TwosideTextDiffViewer twoside)) {
			return;
		}
		EditReasonsService.getInstance(project).markBefore(twoside.getEditor(Side.LEFT), file);
	}
}
