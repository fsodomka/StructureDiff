package com.fsodomka.structurediff.coverage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IdeCoverageLinesTest {
	@Test
	public void topLevelStripsNestedClassesLambdasAndCompanions() {
		assertEquals("a.Foo", IdeCoverageLines.topLevel("a.Foo"));
		assertEquals("a.Foo", IdeCoverageLines.topLevel("a.Foo$Inner"));
		assertEquals("a.Foo", IdeCoverageLines.topLevel("a.Foo$1"));
		assertEquals("a.Foo", IdeCoverageLines.topLevel("a.Foo$Companion"));
		assertEquals("a.Foo", IdeCoverageLines.topLevel("a.Foo$Inner$lambda$2"));
		assertEquals("a.FooKt", IdeCoverageLines.topLevel("a.FooKt$main$1"));
	}

	@Test
	public void shortNameDropsTheCoverageResultsSuffix() {
		assertEquals("StructureDiff [test]", IdeCoverageLines.shortName("StructureDiff [test] Coverage Results"));
		assertEquals("Other", IdeCoverageLines.shortName("Other"));
	}
}
