package com.fsodomka.structurediff;

import junit.framework.TestCase;

public class ChurnLinesTest extends TestCase {
	private static int[] advance(int[] counters, String before, String after, boolean count) {
		return ChurnLines.advance(counters, ChurnLines.lines(before), ChurnLines.lines(after), count);
	}

	public void testAFileCreatedByACommitCountsEveryLineOnce() {
		assertArrayEquals(new int[]{1, 1, 1}, advance(new int[0], "", "a\nb\nc", true));
	}

	public void testAChangedLineInheritsItsCounterPlusOneAndOthersKeepTheirs() {
		int[] counters = advance(new int[0], "", "a\nb\nc", true);
		counters = advance(counters, "a\nb\nc", "a\nB\nc", true);
		counters = advance(counters, "a\nB\nc", "a\nBB\nc", true);
		assertArrayEquals("the middle line changed twice", new int[]{1, 3, 1}, counters);
	}

	public void testLinesShiftWithTheirCountersWhenOthersAreInsertedOrDeleted() {
		int[] counters = {2, 1, 1};
		assertArrayEquals("an inserted line is new", new int[]{1, 2, 1, 1}, advance(counters, "a\nb\nc", "new\na\nb\nc", true));
		assertArrayEquals("a deleted line drops out", new int[]{1, 1}, advance(counters, "a\nb\nc", "b\nc", true));
	}

	public void testOfAReplacedBlockOnlyThePairedLinesInheritTheCounter() {
		int[] counters = {3};
		// one line became three: the first inherits 3 + 1, the two extra lines are new
		assertArrayEquals(new int[]{4, 1, 1}, advance(counters, "a", "x\ny\nz", true));
	}

	public void testWhitespaceOnlyChangesAreIgnored() {
		int[] counters = {2, 2};
		assertArrayEquals(new int[]{2, 2}, advance(counters, "a\nb", "  a\nb  ", true));
	}

	public void testWithoutCountTheCountersOnlyFollowTheLines() {
		int[] counters = {2, 2, 2};
		assertArrayEquals(new int[]{2, 0, 2}, advance(counters, "a\nb\nc", "a\nB\nc", false));
	}

	public void testChurnIsTheRewritesPerLineOfTheMember() {
		int[] counters = {1, 1, 3, 1, 1};
		// lines 1..3: counters 1, 3, 1: an old member counts them all, 5 over 3 lines
		assertEquals(5 / 3.0, ChurnLines.churn(counters, 1, 3, false), 1e-9);
		// a new member's first change is its creation: 0 + 2 + 0 over 3 lines
		assertEquals(2 / 3.0, ChurnLines.churn(counters, 1, 3, true), 1e-9);
	}

	public void testAMemberOutsideTheCountersHasNoChurn() {
		assertEquals(0.0, ChurnLines.churn(new int[]{1}, 5, 7, false), 0);
		assertEquals(0.0, ChurnLines.churn(new int[]{1}, 3, 2, false), 0);
	}

	private static void assertArrayEquals(int[] expected, int[] actual) {
		assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
	}

	private static void assertArrayEquals(String message, int[] expected, int[] actual) {
		assertEquals(message, java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
	}

	public void testLineStartsBreakOnLoneCarriageReturnsAsLinesDoes() {
		assertArrayEquals(new int[]{0, 2, 4}, ChurnLines.lineStarts("a\rb\nc"));
		assertArrayEquals(new int[]{0, 3}, ChurnLines.lineStarts("a\r\nb"));
	}

	public void testParseLogFollowsARenameAndSkipsCommitsWithoutAStatus() {
		java.util.List<ChurnLines.Step> steps = ChurnLines.parseLog(java.util.List.of(
				"c1", "", "A	old.txt",
				"c2 c1", "", "R095	old.txt	new.txt",
				"m3 c2 x9",
				"c4 m3", "", "M	new.txt",
				"c5 c4", "", "D	new.txt"));
		assertEquals(java.util.List.of(new ChurnLines.Step("c1", null, null, "old.txt"),
				new ChurnLines.Step("c2", "c1", "old.txt", "new.txt"),
				new ChurnLines.Step("c4", "m3", "new.txt", "new.txt"),
				new ChurnLines.Step("c5", "c4", "new.txt", null)), steps);
	}
}
