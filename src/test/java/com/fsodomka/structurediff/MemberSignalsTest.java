package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.RowIcon;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MemberSignalsTest extends BasePlatformTestCase {
	private static MemberDiff member(String key, FileStatus status, long changeId) {
		return new MemberDiff(key, key, key, key, null, status, 0, 0, 1, 1, true, changeId, List.of());
	}

	private static MemberDiff member(String key, long changeId) {
		return member(key, FileStatus.MODIFIED, changeId);
	}

	private static MemberDiff member(String key) {
		return member(key, 0);
	}

	private static FileDiff file(String path) {
		return file(path, FileStatus.MODIFIED);
	}

	private static FileDiff file(String path, FileStatus status) {
		return file(path, status, "", "");
	}

	private static FileDiff file(String path, FileStatus status, String beforeText, String afterText) {
		return new FileDiff(null, path, null, status, beforeText, afterText, 1, 1, List.of());
	}

	private static MemberFinding finding(String title, String... keyPath) {
		return finding(ReviewSeverity.WARNING, title, keyPath);
	}

	private static MemberFinding finding(ReviewSeverity severity, String title, String... keyPath) {
		return new MemberFinding(List.of(keyPath), title, severity, 0, null);
	}

	private static MemberDiagnostic diagnostic(HighlightSeverity severity, String message, String... keyPath) {
		return new MemberDiagnostic(List.of(keyPath), message, severity, 0);
	}

	private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, Object userObject) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);
		parent.add(node);
		return node;
	}

	private static List<String> titles(List<MemberFinding> findings) {
		return findings.stream().map(MemberFinding::title).toList();
	}

	public void testFindingCountsBySeverityInShownFiles() {
		Map<String, List<MemberFinding>> findings = Map.of(
				"/p/a.xml", List.of(finding(ReviewSeverity.ERROR, "e", "root"), finding("w1", "root"), finding("w2")),
				"/p/b.xml", List.of(finding(ReviewSeverity.WEAK_WARNING, "weak")),
				"/p/notShown.xml", List.of(finding(ReviewSeverity.ERROR, "x"), finding("y")));

		int[] counts = MemberSignals.findingCounts(List.of(file("/p/a.xml"), file("/p/b.xml"), file("/p/c.xml")), findings);

		assertEquals(1, counts[ReviewSeverity.ERROR.ordinal()]);
		assertEquals(2, counts[ReviewSeverity.WARNING.ordinal()]);
		assertEquals(1, counts[ReviewSeverity.WEAK_WARNING.ordinal()]);
		// notShown.xml's findings are not counted
		assertEquals(ReviewSeverity.values().length, counts.length);
	}

	public void testFindingsGoToTheInnermostShownMember() {
		// a.xml > root > a > b; c is not shown
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file("/p/a.xml"));
		DefaultMutableTreeNode rootNode = add(fileNode, member("root"));
		DefaultMutableTreeNode aNode = add(rootNode, member("a"));
		DefaultMutableTreeNode bNode = add(aNode, member("b"));
		Map<String, List<MemberFinding>> findings = Map.of(
				"/p/a.xml", List.of(
						finding("inB", "root", "a", "b"),
						finding("inHiddenChildOfA", "root", "a", "hidden"),
						finding("inA", "root", "a"),
						finding("inHiddenC", "root", "c"),
						finding("outsideMembers")),
				"/p/other.xml", List.of(finding("otherFile", "root", "a")));

		assertEquals(List.of("inB", "inHiddenChildOfA", "inA", "inHiddenC", "outsideMembers"),
				titles(MemberSignals.findingsOf(fileNode, findings)));
		assertEquals(List.of("inHiddenC"), titles(MemberSignals.findingsOf(rootNode, findings)));
		assertEquals(List.of("inHiddenChildOfA", "inA"), titles(MemberSignals.findingsOf(aNode, findings)));
		assertEquals(List.of("inB"), titles(MemberSignals.findingsOf(bNode, findings)));
	}

	public void testDiagnosticsArePlacedAsFindings() {
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file("/p/a.xml"));
		DefaultMutableTreeNode rootNode = add(fileNode, member("root"));
		DefaultMutableTreeNode aNode = add(rootNode, member("a"));
		Map<String, List<MemberDiagnostic>> diags = Map.of("/p/a.xml", List.of(
				diagnostic(HighlightSeverity.ERROR, "inA", "root", "a"),
				diagnostic(HighlightSeverity.WARNING, "inHidden", "root", "hidden"),
				diagnostic(HighlightSeverity.WARNING, "outside")));

		assertEquals(List.of("inA", "inHidden", "outside"),
				MemberSignals.placedOn(fileNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
		assertEquals(List.of("inHidden"),
				MemberSignals.placedOn(rootNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
		assertEquals(List.of("inA"),
				MemberSignals.placedOn(aNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
	}

	public void testTooltipHasASectionPerSource() {
		List<MemberDiagnostic> diags = new ArrayList<>();
		for (int i = 0; i < MemberSignals.MAX_TOOLTIP_DIAGNOSTICS + 2; i++) {
			diags.add(diagnostic(HighlightSeverity.ERROR, "e" + i + " <x>"));
		}

		String html = MemberSignals.withSignals("lines", List.of(finding("CRITICAL · f")), diags, true);

		assertTrue(html, html.startsWith("<html>lines<br><br><b>Review</b><br>CRITICAL · f<br><b>IDE</b><br>Error · e0 &lt;x&gt;"));
		assertTrue(html, html.contains("e4"));
		assertFalse(html, html.contains("e5"));
		assertTrue(html, html.contains("<br>and 2 more<br><i>Not analysed by the IDE"));
		assertEquals("plain", MemberSignals.withSignals("plain", List.of(), List.of(), false));
		assertFalse(MemberSignals.withSignals("t", List.of(), diags, false).contains("Review"));
	}

	public void testSignalIconsFollowTheNodeIconReviewFirst() {
		Icon review = finding(ReviewSeverity.ERROR, "f").icon();
		Icon ide = diagnostic(HighlightSeverity.WARNING, "w").icon();
		assertEquals(AllIcons.Nodes.WarningIntroduction, ide);

		Icon both = MemberSignals.withSignalIcons(AllIcons.Nodes.Class,
				List.of(finding(ReviewSeverity.ERROR, "f")), List.of(diagnostic(HighlightSeverity.WARNING, "w")));
		assertEquals(List.of(AllIcons.Nodes.Class, review, ide), both instanceof RowIcon row ? row.getAllIcons() : both);
		assertSame(AllIcons.Nodes.Class, MemberSignals.withSignalIcons(AllIcons.Nodes.Class, List.of(), List.of()));
	}

	public void testNoFindingsOutsideAFile() {
		DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode("repo");
		DefaultMutableTreeNode orphan = add(repoNode, member("root"));
		Map<String, List<MemberFinding>> findings = Map.of("/p/a.xml", List.of(finding("f", "root")));

		assertEquals(List.of(), MemberSignals.findingsOf(repoNode, findings));
		assertEquals(List.of(), MemberSignals.findingsOf(orphan, findings));
		assertEquals(List.of(), MemberSignals.findingsOf(new DefaultMutableTreeNode(file("/p/a.xml")), Map.of()));
	}
}
