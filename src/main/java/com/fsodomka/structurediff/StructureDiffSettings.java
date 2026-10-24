package com.fsodomka.structurediff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/** Where review files are exchanged with the review skill, how it is invoked, and which diagnostics are shown; the same for all projects. */
@Service(Service.Level.APP)
@State(name = "StructureDiffSettings", storages = @Storage("structureDiff.xml"))
public final class StructureDiffSettings implements PersistentStateComponent<StructureDiffSettings.State> {
	public static final String DEFAULT_REVIEWS_DIR = "reviews";
	public static final String DEFAULT_SKILL_COMMAND = "/adversarial-review";

	public static final class State {
		/** Relative to each repository root, with {@code /} separators; exported diffs go there and its SARIF files are watched. */
		public String reviewsDir = DEFAULT_REVIEWS_DIR;
		/** Copied to the clipboard with the exported diff's path appended. */
		public String skillCommand = DEFAULT_SKILL_COMMAND;
		public boolean copyCommandToClipboard = true;
		/** Which of the IDE's diagnostics are shown on the changed members of open files. */
		public boolean showErrors = true;
		public boolean showWarnings = false;
		public boolean showWeakWarnings = false;
	}

	/** Application-level notification that the diagnostic levels shown changed. */
	public interface DiagnosticLevelsListener {
		Topic<DiagnosticLevelsListener> TOPIC = Topic.create("Structure Diff diagnostic levels", DiagnosticLevelsListener.class);

		void levelsChanged();
	}

	private State state = new State();

	public static StructureDiffSettings getInstance() {
		return ApplicationManager.getApplication().getService(StructureDiffSettings.class);
	}

	/** The reviews directory relative to a repository root; the default when the stored one is not valid. */
	public String reviewsDir() {
		String dir = normalizeReviewsDir(state.reviewsDir);
		return dir == null ? DEFAULT_REVIEWS_DIR : dir;
	}

	public String skillCommand() {
		return state.skillCommand.strip();
	}

	public boolean copyCommandToClipboard() {
		return state.copyCommandToClipboard;
	}

	/** The diagnostic levels turned on; empty when none are shown. */
	public Set<DiagnosticMembers.Level> diagnosticLevels() {
		Set<DiagnosticMembers.Level> levels = EnumSet.noneOf(DiagnosticMembers.Level.class);
		if (state.showErrors) {
			levels.add(DiagnosticMembers.Level.ERROR);
		}
		if (state.showWarnings) {
			levels.add(DiagnosticMembers.Level.WARNING);
		}
		if (state.showWeakWarnings) {
			levels.add(DiagnosticMembers.Level.WEAK_WARNING);
		}
		return levels;
	}

	/** {@code dir} with {@code /} separators and no trailing one, or null unless it is a non-empty relative path within the root. */
	static @Nullable String normalizeReviewsDir(@Nullable String dir) {
		if (dir == null) {
			return null;
		}
		String normalized = dir.strip().replace('\\', '/').replaceAll("/+", "/").replaceAll("^\\./|/$", "");
		if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains(":")) {
			return null;
		}
		try {
			Path path = Path.of(normalized);
			if (path.isAbsolute()) {
				return null;
			}
			for (Path part : path) {
				// not into Git's own directory
				if (part.toString().equals("..") || part.toString().equalsIgnoreCase(".git")) {
					return null;
				}
			}
		}
		catch (InvalidPathException e) {
			return null;
		}
		// drops interior "." parts
		return Path.of(normalized).normalize().toString().replace('\\', '/');
	}

	@Override
	public @NotNull State getState() {
		return state;
	}

	@Override
	public void loadState(@NotNull State state) {
		this.state = state;
	}
}
