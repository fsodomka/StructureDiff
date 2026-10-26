package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.RowIcon;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.Icon;
import javax.swing.tree.DefaultMutableTreeNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StructureDiffPanelTest extends BasePlatformTestCase {
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

	private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, Object userObject) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);
		parent.add(node);
		return node;
	}

	private static Map<List<String>, DefaultMutableTreeNode> nodes(DefaultMutableTreeNode root) {
		Map<List<String>, DefaultMutableTreeNode> nodes = new HashMap<>();
		StructureDiffPanel.collectNodes(root, new ArrayList<>(), nodes);
		return nodes;
	}

	private static List<String> titles(List<MemberFinding> findings) {
		return findings.stream().map(MemberFinding::title).toList();
	}

	public void testFindingCountsBySeverityInShownFiles() {
		Map<String, List<MemberFinding>> findings = Map.of(
				"/p/a.xml", List.of(finding(ReviewSeverity.ERROR, "e", "root"), finding("w1", "root"), finding("w2")),
				"/p/b.xml", List.of(finding(ReviewSeverity.WEAK_WARNING, "weak")),
				"/p/notShown.xml", List.of(finding(ReviewSeverity.ERROR, "x"), finding("y")));

		int[] counts = StructureDiffPanel.findingCounts(List.of(file("/p/a.xml"), file("/p/b.xml"), file("/p/c.xml")), findings);

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
				titles(StructureDiffPanel.findingsOf(fileNode, findings)));
		assertEquals(List.of("inHiddenC"), titles(StructureDiffPanel.findingsOf(rootNode, findings)));
		assertEquals(List.of("inHiddenChildOfA", "inA"), titles(StructureDiffPanel.findingsOf(aNode, findings)));
		assertEquals(List.of("inB"), titles(StructureDiffPanel.findingsOf(bNode, findings)));
	}

	private static MemberDiagnostic diagnostic(HighlightSeverity severity, String message, String... keyPath) {
		return new MemberDiagnostic(List.of(keyPath), message, severity, 0);
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
				StructureDiffPanel.placedOn(fileNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
		assertEquals(List.of("inHidden"),
				StructureDiffPanel.placedOn(rootNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
		assertEquals(List.of("inA"),
				StructureDiffPanel.placedOn(aNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
	}

	public void testTooltipHasASectionPerSource() {
		List<MemberDiagnostic> diags = new ArrayList<>();
		for (int i = 0; i < StructureDiffPanel.MAX_TOOLTIP_DIAGNOSTICS + 2; i++) {
			diags.add(diagnostic(HighlightSeverity.ERROR, "e" + i + " <x>"));
		}

		String html = StructureDiffPanel.withSignals("lines", List.of(finding("CRITICAL · f")), diags, true);

		assertTrue(html, html.startsWith("<html>lines<br><br><b>Review</b><br>CRITICAL · f<br><b>IDE</b><br>Error · e0 &lt;x&gt;"));
		assertTrue(html, html.contains("e4"));
		assertFalse(html, html.contains("e5"));
		assertTrue(html, html.contains("<br>and 2 more<br><i>Not analysed by the IDE"));
		assertEquals("plain", StructureDiffPanel.withSignals("plain", List.of(), List.of(), false));
		assertFalse(StructureDiffPanel.withSignals("t", List.of(), diags, false).contains("Review"));
	}

	public void testSignalIconsFollowTheNodeIconReviewFirst() {
		Icon review = finding(ReviewSeverity.ERROR, "f").icon();
		Icon ide = diagnostic(HighlightSeverity.WARNING, "w").icon();
		assertEquals(AllIcons.Nodes.WarningIntroduction, ide);

		Icon both = StructureDiffPanel.withSignalIcons(AllIcons.Nodes.Class,
				List.of(finding(ReviewSeverity.ERROR, "f")), List.of(diagnostic(HighlightSeverity.WARNING, "w")));
		assertEquals(List.of(AllIcons.Nodes.Class, review, ide), both instanceof RowIcon row ? row.getAllIcons() : both);
		assertSame(AllIcons.Nodes.Class, StructureDiffPanel.withSignalIcons(AllIcons.Nodes.Class, List.of(), List.of()));
	}

	public void testNoFindingsOutsideAFile() {
		DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode("repo");
		DefaultMutableTreeNode orphan = add(repoNode, member("root"));
		Map<String, List<MemberFinding>> findings = Map.of("/p/a.xml", List.of(finding("f", "root")));

		assertEquals(List.of(), StructureDiffPanel.findingsOf(repoNode, findings));
		assertEquals(List.of(), StructureDiffPanel.findingsOf(orphan, findings));
		assertEquals(List.of(), StructureDiffPanel.findingsOf(new DefaultMutableTreeNode(file("/p/a.xml")), Map.of()));
	}

	public void testExportRoots() throws IOException {
		VirtualFile root1 = myFixture.getTempDirFixture().findOrCreateDir("r1");
		VirtualFile root2 = myFixture.getTempDirFixture().findOrCreateDir("r2");
		VirtualFile root3 = myFixture.getTempDirFixture().findOrCreateDir("r3");
		FileDiff a = file("/r1/a.xml");
		FileDiff b = file("/r2/b.xml");
		FileDiff c = file("/r3/c.xml");
		FileDiff unversioned = file("/elsewhere/d.xml");
		Map<String, VirtualFile> vcsRoots = Map.of(a.path(), root1, b.path(), root2, c.path(), root3);

		assertEquals(List.of(), StructureDiffPanel.exportRoots(List.of(), vcsRoots, List.of()));
		assertEquals(List.of(), StructureDiffPanel.exportRoots(List.of(unversioned), vcsRoots, List.of()));
		// a single root needs no selection
		assertEquals(List.of(root1), StructureDiffPanel.exportRoots(List.of(a, unversioned), vcsRoots, List.of()));

		// several roots: all of them when nothing with a root is selected
		List<FileDiff> all = List.of(a, b, c);
		assertEquals(List.of(root1, root2, root3), StructureDiffPanel.exportRoots(all, vcsRoots, List.of()));
		assertEquals(List.of(root1, root2, root3),
				StructureDiffPanel.exportRoots(all, vcsRoots, List.of(new DefaultMutableTreeNode(unversioned))));
		// the selected repositories, in tree order, and only those
		assertEquals(List.of(root2, root3), StructureDiffPanel.exportRoots(all, vcsRoots, List.of(
				new DefaultMutableTreeNode(new StructureDiffPanel.RepoNode(root2)),
				new DefaultMutableTreeNode(new StructureDiffPanel.RepoNode(root3)))));
		// a file or member selects its own repository
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(b);
		DefaultMutableTreeNode memberNode = add(fileNode, member("root"));
		assertEquals(List.of(root2), StructureDiffPanel.exportRoots(all, vcsRoots, List.of(memberNode)));
		// several files of one repository are still one diff
		assertEquals(List.of(root2), StructureDiffPanel.exportRoots(all, vcsRoots, List.of(
				fileNode, new DefaultMutableTreeNode(b))));
	}

	/** Keys of files and members, for flashes and the selection, stay the same when grouping by repository starts or stops. */
	public void testKeysIgnoreTheRepoNode() throws IOException {
		VirtualFile repo = myFixture.getTempDirFixture().findOrCreateDir("repo");
		DefaultMutableTreeNode flat = new DefaultMutableTreeNode();
		DefaultMutableTreeNode flatMember = add(add(flat, file("A.java")), member("m"));
		DefaultMutableTreeNode grouped = new DefaultMutableTreeNode();
		DefaultMutableTreeNode repoNode = add(grouped, new StructureDiffPanel.RepoNode(repo));
		DefaultMutableTreeNode groupedMember = add(add(repoNode, file("A.java")), member("m"));

		Map<List<String>, DefaultMutableTreeNode> flatNodes = new java.util.HashMap<>();
		StructureDiffPanel.collectNodes(flat, new java.util.ArrayList<>(), flatNodes);
		Map<List<String>, DefaultMutableTreeNode> groupedNodes = new java.util.HashMap<>();
		StructureDiffPanel.collectNodes(grouped, new java.util.ArrayList<>(), groupedNodes);
		List<String> key = StructureDiffPanel.keyOf(flatMember);
		assertEquals(key, StructureDiffPanel.keyOf(groupedMember));
		assertSame(flatMember, flatNodes.get(key));
		assertSame(groupedMember, groupedNodes.get(key));
		assertSame(groupedMember, StructureDiffPanel.find(grouped, key));
		assertSame(flatMember, StructureDiffPanel.find(flat, key));
		assertSame(repoNode, StructureDiffPanel.find(grouped, StructureDiffPanel.keyOf(repoNode)));
	}

	/** A member modified again keeps its status; its new change id still makes it flash. */
	public void testModifiedMemberFlashesAgainWhenItsContentChanged() {
		assertTrue(StructureDiffPanel.changedSince(member("m", 1), member("m", 2)));
		assertFalse(StructureDiffPanel.changedSince(member("m", 2), member("m", 2)));
	}

	public void testFlashesWhenTheStatusChanges() {
		MemberDiff added = member("m", FileStatus.ADDED, 1);

		assertTrue(StructureDiffPanel.changedSince(member("m", 1), added));
		assertTrue(StructureDiffPanel.changedSince(added, member("m", 1)));
	}

	/** A file that stays modified is not a change: every member edit would also flash the file. */
	public void testModifiedFileDoesNotFlashWhenOnlyItsContentChanged() {
		FileDiff before = file("/p/a.java", FileStatus.MODIFIED, "void a() {}", "void a() { int x = 1; }");
		FileDiff after = file("/p/a.java", FileStatus.MODIFIED, "void a() {}", "void a() { int x = 2; }");

		assertFalse(StructureDiffPanel.changedSince(before, after));
		assertTrue(StructureDiffPanel.changedSince(file("/p/a.java", FileStatus.ADDED), file("/p/a.java")));
	}

	/** Each node flashes on its own: a running flash suppresses its node only, other changed nodes still flash. */
	public void testRunningFlashSuppressesItsNodeOnly() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldFile = add(oldRoot, file("/p/a.java"));
		DefaultMutableTreeNode oldA = add(oldFile, member("a", 1));
		DefaultMutableTreeNode oldB = add(oldFile, member("b", 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode newFile = add(newRoot, file("/p/a.java"));
		add(newFile, member("a", 2));
		add(newFile, member("b", 2));
		List<String> a = StructureDiffPanel.keyOf(oldA);
		List<String> b = StructureDiffPanel.keyOf(oldB);

		assertEquals(Set.of(b), StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0).keySet());
		assertEquals(Set.of(a, b), StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(), 0).keySet());
	}

	/** A status change updates a running flash instead of being skipped with the other content changes. */
	public void testStatusChangeFlashesWhileItsNodeIsRunning() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldA = add(add(oldRoot, file("/p/a.java")), member("a", FileStatus.ADDED, 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		add(add(newRoot, file("/p/a.java")), member("a", FileStatus.MODIFIED, 1));
		List<String> a = StructureDiffPanel.keyOf(oldA);

		Map<List<String>, StructureDiffPanel.Flash> flashes =
				StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0);

		assertEquals(Set.of(a), flashes.keySet());
		assertEquals(FileStatus.MODIFIED, flashes.get(a).status());
	}

	/** A removal flashes as a ghost even while the removed node's own flash runs. */
	public void testRemovalFlashesWhileItsNodeIsRunning() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldA = add(add(oldRoot, file("/p/a.java")), member("a", 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		add(newRoot, file("/p/a.java"));
		List<String> a = StructureDiffPanel.keyOf(oldA);

		Map<List<String>, StructureDiffPanel.Flash> flashes =
				StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0);

		assertEquals(Set.of(a), flashes.keySet());
		assertSame(oldA.getUserObject(), flashes.get(a).vanished());
	}

	public void testRelativePackagesDropTheSharedPrefix() {
		Map<String, String> relative = StructureDiffPanel.relativePackages(Map.of(
				"a", "com.acme.app", "b", "com.acme.app.history", "c", "META-INF", "d", ""), Map.of());
		assertEquals(Map.of("a", "", "b", "history", "c", "META-INF", "d", ""), relative);
	}

	public void testRelativePackagesOfUnrelatedPackagesKeepTheirNames() {
		assertEquals(Map.of("a", "com.acme", "b", "org.other"),
				StructureDiffPanel.relativePackages(Map.of("a", "com.acme", "b", "org.other"), Map.of()));
	}

	public void testRelativePackagesPerRootAndSeparator() throws IOException {
		VirtualFile one = myFixture.getTempDirFixture().findOrCreateDir("one");
		VirtualFile two = myFixture.getTempDirFixture().findOrCreateDir("two");
		Map<String, String> relative = StructureDiffPanel.relativePackages(
				Map.of("a", "com.acme.rest.model", "b", "com.acme.services", "c", "admin/basket", "d", "admin/converter",
						"e", "org.other.impl", "f", "org.other.model.notebook"),
				Map.of("a", one, "b", one, "c", one, "d", one, "e", two, "f", two));
		assertEquals(Map.of("a", "rest.model", "b", "services", "c", "basket", "d", "converter",
				"e", "impl", "f", "model.notebook"), relative);
	}

	public void testConventionalPackage() {
		assertEquals("com.acme.impl", StructureDiffPanel.conventionalPackage("src/main/java/com/acme/impl/A.java"));
		assertEquals("com.acme", StructureDiffPanel.conventionalPackage("lib/src/test/kotlin/com/acme/ATest.kt"));
		assertEquals("admin/basket", StructureDiffPanel.conventionalPackage("src/main/js/admin/basket/edit.tsx"));
		assertEquals("", StructureDiffPanel.conventionalPackage("src/main/js/index.ts"));
		assertNull(StructureDiffPanel.conventionalPackage("templates/marimo.html"));
		assertNull(StructureDiffPanel.conventionalPackage("README.md"));
	}
}
