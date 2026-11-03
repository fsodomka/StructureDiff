package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiffTreeNodes.Ghost;
import com.intellij.openapi.vcs.FileStatus;
import junit.framework.TestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

public class DiffLabelsTest extends TestCase {
	private static MemberDiff member(String label, String beforeLabel, FileStatus status, int added, int removed) {
		return new MemberDiff(label, label, label, beforeLabel, null, status, 0, 0, added, removed, true, 0, List.of());
	}

	public void testShortenStripsGenericsAndCutsLongParameterLists() {
		assertEquals("foo(List, int): Pair", DiffLabels.shorten("foo(List<Map<A, B>>, int): Pair<X, Y>"));
		String longParameters = "bar(String first, String second, String third, String fourth, String fifth)";
		assertEquals("bar(String first, …)", DiffLabels.shorten(longParameters));
		// unbalanced brackets are kept as they are
		assertEquals("a < b", DiffLabels.shorten("a < b"));
	}

	public void testLinesTextLeavesOutAnEmptySide() {
		assertEquals("1 line added", DiffLabels.linesText(1, 0));
		assertEquals("3 lines removed", DiffLabels.linesText(0, 3));
		assertEquals("2 lines added, 1 removed", DiffLabels.linesText(2, 1));
	}

	public void testRelativePathOnlyBelowTheBase() {
		assertEquals("src/A.java", DiffLabels.relativePath("/repo/src/A.java", "/repo"));
		assertEquals("/repository/A.java", DiffLabels.relativePath("/repository/A.java", "/repo"));
	}

	public void testTooltipShowsTheChangedSignature() {
		assertEquals("<html><b>Before:</b> f(int)<br><b>After:</b> f(long)<br>1 line added</html>",
				DiffLabels.tooltip(member("f(long)", "f(int)", FileStatus.MODIFIED, 1, 0)));
		assertEquals("<html>5 lines</html>", DiffLabels.tooltip(member("g()", "g()", FileStatus.ADDED, 5, 0)));
	}

	public void testSpeedSearchTextMatchesTheShownLabel() {
		assertEquals("foo(List)", DiffLabels.speedSearchText(new DefaultMutableTreeNode(
				member("foo(List<String>)", "foo(List<String>)", FileStatus.ADDED, 1, 0))));
		assertEquals("foo()", DiffLabels.speedSearchText(new DefaultMutableTreeNode(
				new Ghost(member("foo()", "foo()", FileStatus.DELETED, 0, 1)))));
		assertEquals("", DiffLabels.speedSearchText(new DefaultMutableTreeNode()));
	}
}
