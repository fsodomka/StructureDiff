package com.fsodomka.structurediff;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

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

	private final Map<Option, Boolean> values = new EnumMap<>(Option.class);

	ViewOptions() {
		for (Option option : Option.values()) {
			values.put(option, option.saved());
		}
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

	/** A toggle of an option that always applies. */
	ToggleAction toggle(Option option, String text, @Nullable String description, Icon icon, Runnable changed) {
		return toggle(option, text, description, icon, () -> true, null, changed);
	}
}
