package com.fsodomka.structurediff.review;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/** Shows the newest review of each repository root once the VCS roots are known. */
public final class ReviewFindingsStartup implements ProjectActivity {
	@Override
	public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
		ProjectLevelVcsManager.getInstance(project).runAfterInitialization(() -> ApplicationManager.getApplication().invokeLater(
				() -> ReviewFindingsService.getInstance(project).loadNewest(), project.getDisposed()));
		return Unit.INSTANCE;
	}
}
