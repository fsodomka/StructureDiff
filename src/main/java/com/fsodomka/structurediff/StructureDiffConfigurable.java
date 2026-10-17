package com.fsodomka.structurediff;

import com.fsodomka.structurediff.review.ReviewFindingsService;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.Objects;

/** Tools | Structure Diff: the reviews directory and the review skill command. */
final class StructureDiffConfigurable implements SearchableConfigurable {
	private JBTextField reviewsDir;
	private JBTextField skillCommand;
	private JBCheckBox copyCommand;

	@Override
	public @NotNull String getId() {
		return "com.fsodomka.structurediff.settings";
	}

	@Override
	public String getDisplayName() {
		return "Structure Diff";
	}

	@Override
	public JComponent createComponent() {
		reviewsDir = new JBTextField();
		skillCommand = new JBTextField();
		copyCommand = new JBCheckBox("Copy the review command to the clipboard on Export Diff for Review");
		JPanel panel = FormBuilder.createFormBuilder()
				.addLabeledComponent("Reviews directory:", reviewsDir)
				.addTooltip("Relative to each repository root. Exported diffs are saved there, and its *.sarif and *.sarif.json files are shown.")
				.addLabeledComponent("Review skill command:", skillCommand)
				.addTooltip("Copied with the exported diff's path appended, e.g. " + StructureDiffSettings.DEFAULT_SKILL_COMMAND)
				.addComponent(copyCommand)
				.addComponentFillVertically(new JPanel(), 0)
				.getPanel();
		reset();
		return panel;
	}

	@Override
	public boolean isModified() {
		if (reviewsDir == null) {
			return false;
		}
		StructureDiffSettings settings = StructureDiffSettings.getInstance();
		StructureDiffSettings.State state = settings.getState();
		// an invalid directory counts as modified, so apply reports it
		return !Objects.equals(StructureDiffSettings.normalizeReviewsDir(reviewsDir.getText()), settings.reviewsDir())
				|| !Objects.equals(skillCommand.getText().strip(), state.skillCommand)
				|| copyCommand.isSelected() != state.copyCommandToClipboard;
	}

	@Override
	public void apply() throws ConfigurationException {
		if (reviewsDir == null) {
			return;
		}
		String dir = StructureDiffSettings.normalizeReviewsDir(reviewsDir.getText());
		if (dir == null) {
			throw new ConfigurationException("The reviews directory must be a relative path within the repository root.");
		}
		if (skillCommand.getText().isBlank() && copyCommand.isSelected()) {
			throw new ConfigurationException("Enter the review skill command, or turn off copying it.");
		}
		StructureDiffSettings settings = StructureDiffSettings.getInstance();
		boolean dirChanged = !dir.equals(settings.reviewsDir());
		StructureDiffSettings.State state = settings.getState();
		state.reviewsDir = dir;
		state.skillCommand = skillCommand.getText().strip();
		state.copyCommandToClipboard = copyCommand.isSelected();
		reviewsDir.setText(dir);
		if (dirChanged) {
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				ReviewFindingsService.getInstance(project).reviewsDirChanged();
			}
		}
	}

	@Override
	public void reset() {
		StructureDiffSettings.State state = StructureDiffSettings.getInstance().getState();
		reviewsDir.setText(state.reviewsDir);
		skillCommand.setText(state.skillCommand);
		copyCommand.setSelected(state.copyCommandToClipboard);
	}

	@Override
	public void disposeUIResources() {
		reviewsDir = null;
		skillCommand = null;
		copyCommand = null;
	}
}
