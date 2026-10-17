package com.fsodomka.structurediff;

import com.fsodomka.structurediff.StructureDiffContent.Mode;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.util.CheckedDisposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

import java.awt.Component;

public class StructureDiffContentTest extends BasePlatformTestCase {
	private static final String MODE_KEY = "structureDiff.mode";

	private Content content;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		content = ContentFactory.getInstance().createContent(null, "", false);
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			PropertiesComponent.getInstance().unsetValue(MODE_KEY);
		}
		finally {
			super.tearDown();
		}
	}

	private StructureDiffContent create() {
		StructureDiffContent structureDiff = new StructureDiffContent(getProject(), content);
		Disposer.register(getTestRootDisposable(), structureDiff);
		return structureDiff;
	}

	private static Component shown(StructureDiffContent structureDiff) {
		assertEquals(1, structureDiff.getComponentCount());
		return structureDiff.getComponent(0);
	}

	public void testSwitchesPanelsAndDisposesTheLastModePanel() {
		PropertiesComponent.getInstance().setValue(MODE_KEY, Mode.UNCOMMITTED.name());
		StructureDiffContent structureDiff = create();
		Component local = shown(structureDiff);
		CheckedDisposable localDisposed = Disposer.newCheckedDisposable(assertInstanceOf(local, StructureDiffPanel.class));

		structureDiff.setMode(Mode.BRANCH);
		StructureDiffPanel branch = assertInstanceOf(shown(structureDiff), StructureDiffPanel.class);
		assertNotSame(local, branch);
		CheckedDisposable branchDisposed = Disposer.newCheckedDisposable(branch);
		assertEquals(Mode.BRANCH.name(), PropertiesComponent.getInstance().getValue(MODE_KEY));

		structureDiff.setMode(Mode.GIT_LOG);
		StructureDiffPanel gitLog = assertInstanceOf(shown(structureDiff), StructureDiffPanel.class);
		assertNotSame(local, gitLog);
		CheckedDisposable gitLogDisposed = Disposer.newCheckedDisposable(gitLog);
		assertTrue(branchDisposed.isDisposed());
		// no commit is selected in the Git log
		assertNull(content.getDisplayName());
		assertEquals(Mode.GIT_LOG.name(), PropertiesComponent.getInstance().getValue(MODE_KEY));

		structureDiff.setMode(Mode.UNCOMMITTED);
		assertSame(local, shown(structureDiff));
		assertTrue(gitLogDisposed.isDisposed());
		assertFalse(localDisposed.isDisposed());
		assertNull(content.getDisplayName());
		assertEquals(Mode.UNCOMMITTED.name(), PropertiesComponent.getInstance().getValue(MODE_KEY));
	}

	public void testRestoresTheSavedMode() {
		PropertiesComponent.getInstance().setValue(MODE_KEY, Mode.GIT_LOG.name());
		StructureDiffContent structureDiff = create();
		Component restored = shown(structureDiff);

		structureDiff.setMode(Mode.UNCOMMITTED);
		assertNotSame(restored, shown(structureDiff));
	}

	public void testFallsBackToUncommittedForAnUnknownMode() {
		PropertiesComponent.getInstance().setValue(MODE_KEY, "REMOVED_MODE");
		StructureDiffContent structureDiff = create();
		Component shown = shown(structureDiff);

		// already Uncommitted: switching to it keeps the panel
		structureDiff.setMode(Mode.UNCOMMITTED);
		assertSame(shown, shown(structureDiff));
	}
}
