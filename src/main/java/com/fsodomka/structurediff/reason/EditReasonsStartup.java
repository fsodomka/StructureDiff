package com.fsodomka.structurediff.reason;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/** Loads the recent edit reasons, fresh from disk, once the VCS roots are known. */
public final class EditReasonsStartup implements ProjectActivity {
	@Override
	public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
		ProjectLevelVcsManager.getInstance(project).runAfterInitialization(() -> ApplicationManager.getApplication().invokeLater(
				() -> EditReasonsService.getInstance(project).refreshAndLoad(), project.getDisposed()));
		return Unit.INSTANCE;
	}
}
