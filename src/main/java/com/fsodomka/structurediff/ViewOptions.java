package com.fsodomka.structurediff;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeepPopupOnPerform;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The view toggles of a {@link StructureDiffPanel}, saved for all projects. Each panel keeps its own copy, as the
 * Uncommitted panel outlives mode switches; {@link #reload} picks up what another panel saved.
 */
final class ViewOptions {
	enum Option {
		NESTED("structureDiff.showNested", false),
		UNVERSIONED("structureDiff.showUnversioned", true),
		FLASH("structureDiff.flashChanges", true),
		TREEMAP("structureDiff.showTreemap", false),
		USAGES("structureDiff.showUsages", false);

		private final String key;
		private final boolean defaultValue;

		Option(String key, boolean defaultValue) {
			this.key = key;
			this.defaultValue = defaultValue;
		}

		private boolean saved() {
			return PropertiesComponent.getInstance().getBoolean(key, defaultValue);
		}
	}

	/** What colors the treemap's boxes. */
	enum Fill {
		STATUS("Status", "Color by change status"),
		FILE_TYPE("File Type", "Color by file type, the most changed first"),
		COVERAGE("Coverage", "Color by the share of lines the selected Run with Coverage executed: orange none, blue all");

		final String text;
		final String description;

		Fill(String text, String description) {
			this.text = text;
			this.description = description;
		}
	}

	private final Map<Option, Boolean> values = new EnumMap<>(Option.class);
	/**
	 * Shared by all panels for the IDE session, so it holds across mode switches; not saved, so each session starts
	 * with {@link Fill#STATUS}. Read and written on the EDT.
	 */
	private static Fill fill = Fill.STATUS;
	/** Every panel's reaction to a change of {@link #fill}, as each one may show a treemap at the time. EDT only. */
	private static final List<Runnable> fillListeners = new ArrayList<>();

	ViewOptions() {
		for (Option option : Option.values()) {
			values.put(option, option.saved());
		}
	}

	Fill fill() {
		return fill;
	}

	/** Sets the fill of all panels and runs the {@link #onFillChange} listeners when it changed. Call on the EDT. */
	void setFill(Fill value) {
		if (fill == value) {
			return;
		}
		fill = value;
		List.copyOf(fillListeners).forEach(Runnable::run);
	}

	/** Runs {@code changed} on the EDT whenever any panel changes the fill, until {@code parent} is disposed. */
	static void onFillChange(Runnable changed, Disposable parent) {
		fillListeners.add(changed);
		Disposer.register(parent, () -> fillListeners.remove(changed));
	}

	boolean get(Option option) {
		return values.get(option);
	}

	/** Sets and saves {@code option}. */
	void set(Option option, boolean value) {
		values.put(option, value);
		PropertiesComponent.getInstance().setValue(option.key, value, option.defaultValue);
	}

	/** Reads the saved values again; returns the options whose value changed. */
	Set<Option> reload() {
		Set<Option> changed = EnumSet.noneOf(Option.class);
		for (Option option : Option.values()) {
			boolean saved = option.saved();
			if (saved != values.put(option, saved)) {
				changed.add(option);
			}
		}
		return changed;
	}

	/**
	 * A toolbar toggle of {@code option}, which runs {@code changed} after it was set.
	 *
	 * @param available       whether the option applies; it shows off and disabled when not
	 * @param unavailableText the description while not {@code available}
	 */
	ToggleAction toggle(Option option, String text, @Nullable String description, Icon icon, BooleanSupplier available,
						@Nullable String unavailableText, Runnable changed) {
		return new ToggleAction(text, description, icon) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return available.getAsBoolean() && get(option);
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				set(option, state);
				changed.run();
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				super.update(e);
				if (!available.getAsBoolean()) {
					e.getPresentation().setEnabled(false);
					e.getPresentation().setDescription(unavailableText);
				}
			}

			/** The state is panel state, read on the EDT. */
			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		};
	}

	/**
	 * A toolbar dropdown of the treemap {@link Fill}s that are {@code available}, shown while {@code visible}; a choice
	 * reaches the panels through {@link #onFillChange}.
	 */
	ComboBoxAction fillChooser(BooleanSupplier visible, Predicate<Fill> available) {
		return new ComboBoxAction() {
			@Override
			protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
				DefaultActionGroup group = new DefaultActionGroup();
				for (Fill item : Fill.values()) {
					if (!available.test(item)) {
						continue;
					}
					group.add(new DumbAwareToggleAction(item.text, item.description, null) {
						{
							// a toggle keeps the popup open by default; one choice is all there is to make
							getTemplatePresentation().setKeepPopupOnPerform(KeepPopupOnPerform.Never);
						}

						@Override
						public boolean isSelected(@NotNull AnActionEvent e) {
							return fill == item;
						}

						@Override
						public void setSelected(@NotNull AnActionEvent e, boolean state) {
							setFill(item);
						}

						@Override
						public @NotNull ActionUpdateThread getActionUpdateThread() {
							return ActionUpdateThread.EDT;
						}
					});
				}
				return group;
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				e.getPresentation().setVisible(visible.getAsBoolean());
				e.getPresentation().setText(fill.text);
				e.getPresentation().setDescription("Treemap colors: " + fill.description);
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		};
	}

	/** A toggle of an option that always applies. */
	ToggleAction toggle(Option option, String text, @Nullable String description, Icon icon, Runnable changed) {
		return toggle(option, text, description, icon, () -> true, null, changed);
	}
}
