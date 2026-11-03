package com.fsodomka.structurediff;

import com.fsodomka.structurediff.ViewOptions.Option;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Set;

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
}
