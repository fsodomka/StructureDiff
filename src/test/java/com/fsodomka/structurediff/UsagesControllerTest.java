package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UsagesControllerTest extends BasePlatformTestCase {
	private static MemberDiff member(String key, FileStatus status, long changeId) {
		return new MemberDiff(key, key, key, key, null, status, 0, 0, 1, 1, true, changeId, List.of());
	}

	private static MemberDiff member(String key, long changeId) {
		return member(key, FileStatus.MODIFIED, changeId);
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

	private static MemberLinks.Link link(VirtualFile file, String... keys) {
		return new MemberLinks.Link(file, 0, 0, List.of(keys), List.of(keys), Collections.nCopies(keys.length, null), "", 1, false, false);
	}

	public void testContentStampChangesWithTheAfterTexts() {
		int stamp = UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b")));
		// the before text and the status do not change what the working copy uses
		assertEquals(stamp, UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.ADDED, "", "b"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b2"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/B.java", FileStatus.MODIFIED, "a", "b"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b"), file("/p/B.java"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.DELETED, "a", null))));
	}

	public void testRelatedNodesMatchMembersAndFallBackToCodeMembersOnly() {
		VirtualFile file = myFixture.addFileToProject("A.java", "").getVirtualFile();
		MemberDiff method = new MemberDiff("m()", "m()", "m()", "m()", null, FileStatus.MODIFIED, 0, 0, 1, 1, false, 0, List.of());
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(new FileDiff(null, file.getPath(), null, FileStatus.MODIFIED,
				"", "", 1, 1, List.of()));
		DefaultMutableTreeNode classNode = new DefaultMutableTreeNode(member("C", 1));
		DefaultMutableTreeNode methodNode = new DefaultMutableTreeNode(method);
		root.add(fileNode);
		fileNode.add(classNode);
		classNode.add(methodNode);
		VirtualFile other = myFixture.addFileToProject("B.java", "").getVirtualFile();

		assertEquals(Map.of(methodNode, Relation.CALLER), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "m()")), false, List.of(), false)));
		assertEquals(Map.of(classNode, Relation.CALLEE), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(), false, List.of(link(file, "C")), false)));
		// a hidden nested member of a code member highlights the code member
		assertEquals(Map.of(methodNode, Relation.CALLER), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "m()", "Runnable")), false, List.of(), false)));
		// an unchanged member of a class is not related to the class; a file outside the tree matches nothing
		assertEquals(Map.of(), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "unchanged()"), link(other, "B")), false, List.of(link(file)), false)));
	}

	public void testRelatedNodesKeepTheDirection() {
		VirtualFile file = myFixture.addFileToProject("A.java", "").getVirtualFile();
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file(file.getPath()));
		DefaultMutableTreeNode caller = new DefaultMutableTreeNode(member("caller()", 1));
		DefaultMutableTreeNode callee = new DefaultMutableTreeNode(member("callee()", 2));
		DefaultMutableTreeNode both = new DefaultMutableTreeNode(member("both()", 3));
		root.add(fileNode);
		fileNode.add(caller);
		fileNode.add(callee);
		fileNode.add(both);

		assertEquals(Map.of(caller, Relation.CALLER, callee, Relation.CALLEE, both, Relation.BOTH),
				UsagesController.relatedNodes(root, new MemberLinks.Links(
						List.of(link(file, "caller()"), link(file, "both()")), false,
						List.of(link(file, "callee()"), link(file, "both()")), false)));
		// two caller links to one member stay a caller
		assertEquals(Map.of(caller, Relation.CALLER), UsagesController.relatedNodes(root, new MemberLinks.Links(
				List.of(link(file, "caller()"), link(file, "caller()")), false, List.of(), false)));
	}

	public void testInFocusKeepsTheSelectionAndRelatedMembersAndWhatHoldsThem() {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		DefaultMutableTreeNode fileA = new DefaultMutableTreeNode(file("/p/A.java"));
		DefaultMutableTreeNode classA = new DefaultMutableTreeNode(member("A", 1));
		DefaultMutableTreeNode selected = new DefaultMutableTreeNode(member("selected()", 2));
		DefaultMutableTreeNode sibling = new DefaultMutableTreeNode(member("sibling()", 3));
		DefaultMutableTreeNode fileB = new DefaultMutableTreeNode(file("/p/B.java"));
		DefaultMutableTreeNode classB = new DefaultMutableTreeNode(member("B", 4));
		DefaultMutableTreeNode related = new DefaultMutableTreeNode(member("related()", 5));
		DefaultMutableTreeNode nested = new DefaultMutableTreeNode(member("nested", 6));
		DefaultMutableTreeNode fileC = new DefaultMutableTreeNode(file("/p/C.java"));
		DefaultMutableTreeNode unrelated = new DefaultMutableTreeNode(member("unrelated()", 7));
		root.add(fileA);
		fileA.add(classA);
		classA.add(selected);
		classA.add(sibling);
		root.add(fileB);
		fileB.add(classB);
		classB.add(related);
		related.add(nested);
		root.add(fileC);
		fileC.add(unrelated);
		TreePath[] selection = {new TreePath(selected.getPath())};

		// not faded: nothing fades, so a selection without usages does not grey out the map
		assertTrue(UsagesController.focus(Set.of(), false, selection).test(unrelated));
		Set<DefaultMutableTreeNode> relatedSet = Set.of(related);
		for (DefaultMutableTreeNode node : List.of(selected, related, classA, fileA, classB, fileB, root)) {
			assertTrue(node.toString(), UsagesController.focus(relatedSet, true, selection).test(node));
		}
		for (DefaultMutableTreeNode node : List.of(sibling, nested, unrelated, fileC)) {
			assertFalse(node.toString(), UsagesController.focus(relatedSet, true, selection).test(node));
		}
		// a selected class keeps its members; no selection keeps only the related members and what holds them
		assertTrue(UsagesController.focus(relatedSet, true, new TreePath[]{new TreePath(classA.getPath())}).test(sibling));
		assertFalse(UsagesController.focus(relatedSet, true, null).test(sibling));
		// faded while the search for a new selection runs: only the selection and what holds it stay
		assertTrue(UsagesController.focus(Set.of(), true, selection).test(selected));
		assertTrue(UsagesController.focus(Set.of(), true, selection).test(fileA));
		assertFalse(UsagesController.focus(Set.of(), true, selection).test(sibling));
		assertFalse(UsagesController.focus(Set.of(), true, selection).test(related));
	}

	/** Drives {@link UsagesController#update}: a search keeps the map as it was before the selection changed. */
	public void testUpdateKeepsTheMapFadedOrBrightWhileASearchRuns() {
		PsiFile psi = myFixture.addFileToProject("p/Money.java", """
				package p;

				class Money {
					int a() { return b(); }

					int b() { return 0; }

					int c() { return 0; }
				}
				""");
		VirtualFile vf = psi.getVirtualFile();
		String text = psi.getText();
		// the fixture's file is not on the local file system, where the revision would look it up
		Change change = new Change(null, new CurrentContentRevision(VcsUtil.getFilePath(vf)) {
			@Override
			public VirtualFile getVirtualFile() {
				return vf;
			}
		});
		FileDiff fileDiff = new FileDiff(change, vf.getPath(), null, FileStatus.MODIFIED, "", text, 1, 1, List.of());
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileDiff);
		DefaultMutableTreeNode classNode = new DefaultMutableTreeNode(
				new MemberDiff("Money", "Money", "Money", "Money", null, FileStatus.MODIFIED, 0, text.indexOf("class"), 1, 1, true, 1, List.of()));
		DefaultMutableTreeNode a = new DefaultMutableTreeNode(codeMember("a(): int", text.indexOf("int a"), 2));
		DefaultMutableTreeNode b = new DefaultMutableTreeNode(codeMember("b(): int", text.indexOf("int b"), 3));
		DefaultMutableTreeNode c = new DefaultMutableTreeNode(codeMember("c(): int", text.indexOf("int c"), 4));
		root.add(fileNode);
		fileNode.add(classNode);
		classNode.add(a);
		classNode.add(b);
		classNode.add(c);
		JTree tree = new JTree(new DefaultTreeModel(root));
		UsagesController usages = new UsagesController(getProject(), new UsagesPanel(getProject(), () -> false), tree, () -> {},
				getTestRootDisposable());
		List<FileDiff> files = List.of(fileDiff);

		select(tree, usages, a, files);
		waitForSearch();
		assertEquals(Relation.CALLEE, usages.relationOf(b));
		assertFalse(usages.focus().test(c));
		// c relates nothing; while its search runs the map stays faded, as it was for a
		select(tree, usages, c, files);
		assertFalse(usages.focus().test(a));
		waitForSearch();
		assertTrue(usages.focus().test(a));
		// b relates a; while its search runs the map stays bright, as it was for c
		select(tree, usages, b, files);
		assertTrue(usages.focus().test(c));
		waitForSearch();
		assertEquals(Relation.CALLER, usages.relationOf(a));
		assertFalse(usages.focus().test(c));
	}

	private static MemberDiff codeMember(String key, int afterOffset, long changeId) {
		return new MemberDiff(key, key, key, key, null, FileStatus.MODIFIED, 0, afterOffset, 1, 1, false, changeId, List.of());
	}

	private static void select(JTree tree, UsagesController usages, DefaultMutableTreeNode node, List<FileDiff> files) {
		tree.setSelectionPath(new TreePath(node.getPath()));
		usages.update(node, files);
	}

	private static void waitForSearch() {
		NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
	}

	public void testASearchKeepsTheMapFadedOrNot() {
		// related members fade the rest, searching or not
		assertTrue(UsagesController.faded(true, false, false));
		assertTrue(UsagesController.faded(true, true, false));
		// a search keeps the state it started in: faded after a member with related ones, bright after one without, so
		// stepping through members that relate none does not flash the map
		assertTrue(UsagesController.faded(false, true, true));
		assertFalse(UsagesController.faded(false, true, false));
		// its result relates nothing: bright again
		assertFalse(UsagesController.faded(false, false, true));
	}
}
