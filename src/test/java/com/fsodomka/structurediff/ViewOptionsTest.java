package com.fsodomka.structurediff;

import com.fsodomka.structurediff.ViewOptions.Option;
import com.fsodomka.structurediff.source.ChangeSource;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ViewOptionsTest extends BasePlatformTestCase {
	@Override
	protected void tearDown() throws Exception {
		try {
			for (Option option : Option.values()) {
				new ViewOptions().set(option, option == Option.UNVERSIONED || option == Option.FLASH);
			}
		}
		finally {
			super.tearDown();
		}
	}

	public void testReloadPicksUpWhatAnotherPanelSaved() {
		ViewOptions mine = new ViewOptions();
		ViewOptions other = new ViewOptions();
		assertFalse(mine.get(Option.TREEMAP));
		assertTrue(mine.get(Option.FLASH));

		other.set(Option.TREEMAP, true);
		other.set(Option.FLASH, false);
		assertFalse("not before reloading", mine.get(Option.TREEMAP));
		assertEquals(Set.of(Option.TREEMAP, Option.FLASH), mine.reload());
		assertTrue(mine.get(Option.TREEMAP));
		assertFalse(mine.get(Option.FLASH));
		assertEquals(Set.of(), mine.reload());
	}

	public void testToggleSetsSavesAndRunsTheCallback() {
		ViewOptions options = new ViewOptions();
		AtomicInteger changed = new AtomicInteger();
		ToggleAction toggle = options.toggle(Option.NESTED, "Nested", null, AllIcons.Actions.ShowAsTree, changed::incrementAndGet);
		AnActionEvent event = TestActionEvent.createTestEvent(toggle);
		assertFalse(toggle.isSelected(event));

		toggle.setSelected(event, true);
		assertTrue(toggle.isSelected(event));
		assertEquals(1, changed.get());
		assertTrue("saved for other panels", new ViewOptions().get(Option.NESTED));
	}

	public void testUnavailableToggleShowsOffAndDisabledWithItsReason() {
		ViewOptions options = new ViewOptions();
		AtomicBoolean available = new AtomicBoolean(false);
		ToggleAction toggle = options.toggle(Option.FLASH, "Flash", "Flash changes", AllIcons.Actions.Highlighting,
				available::get, "Commits have no member changes", () -> {});
		assertTrue("on, but not applicable", options.get(Option.FLASH));

		AnActionEvent event = TestActionEvent.createTestEvent(toggle);
		toggle.update(event);
		assertFalse(toggle.isSelected(event));
		assertFalse(event.getPresentation().isEnabled());
		assertEquals("Commits have no member changes", event.getPresentation().getDescription());

		available.set(true);
		AnActionEvent applicable = TestActionEvent.createTestEvent(toggle);
		toggle.update(applicable);
		assertTrue(toggle.isSelected(applicable));
		assertTrue(applicable.getPresentation().isEnabled());
		assertEquals("Flash changes", applicable.getPresentation().getDescription());
	}

	public void testPanelSwitchesToTheTreemapAnotherPanelTurnedOn() {
		StructureDiffPanel panel = new StructureDiffPanel(getProject(), new NoChanges());
		Disposer.register(getTestRootDisposable(), panel);
		assertFalse(panel.getContent() instanceof TreemapPanel);

		new ViewOptions().set(Option.TREEMAP, true);
		panel.loadSettings();
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertInstanceOf(panel.getContent(), TreemapPanel.class);

		new ViewOptions().set(Option.TREEMAP, false);
		panel.loadSettings();
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertFalse(panel.getContent() instanceof TreemapPanel);
	}

	private static final class NoChanges implements ChangeSource {
		@Override
		public Collection<Change> changes() {
			return List.of();
		}

		@Override
		public void subscribe(Runnable onChange, Disposable parent) {
		}

		@Override
		public boolean highlightChanges() {
			return false;
		}
	}
}
