package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiffTreeNodesTest extends BasePlatformTestCase {
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

	private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, Object userObject) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);
		parent.add(node);
		return node;
	}

	public void testGroupByRoot() throws IOException {
		VirtualFile projectRoot = myFixture.getTempDirFixture().findOrCreateDir("p");
		VirtualFile other = myFixture.getTempDirFixture().findOrCreateDir("o");
		assertFalse(DiffTreeNodes.groupByRoot(List.of(), projectRoot));
		assertFalse(DiffTreeNodes.groupByRoot(List.of(projectRoot, projectRoot), projectRoot));
		// another repository shows its node even when it is the only one with changes
		assertTrue(DiffTreeNodes.groupByRoot(List.of(other), projectRoot));
		assertTrue(DiffTreeNodes.groupByRoot(List.of(projectRoot, other), projectRoot));
		// a project in no repository groups only files from several
		assertFalse(DiffTreeNodes.groupByRoot(List.of(other, other), null));
		assertTrue(DiffTreeNodes.groupByRoot(List.of(projectRoot, other), null));
	}

	/** Keys of files and members, for flashes and the selection, stay the same when grouping by repository starts or stops. */
	public void testKeysIgnoreTheRepoNode() throws IOException {
		VirtualFile repo = myFixture.getTempDirFixture().findOrCreateDir("repo");
		DefaultMutableTreeNode flat = new DefaultMutableTreeNode();
		DefaultMutableTreeNode flatMember = add(add(flat, file("A.java")), member("m"));
		DefaultMutableTreeNode grouped = new DefaultMutableTreeNode();
		DefaultMutableTreeNode repoNode = add(grouped, new DiffTreeNodes.RepoNode(repo));
		DefaultMutableTreeNode groupedMember = add(add(repoNode, file("A.java")), member("m"));

		Map<List<String>, DefaultMutableTreeNode> flatNodes = new HashMap<>();
		DiffTreeNodes.collectNodes(flat, new ArrayList<>(), flatNodes);
		Map<List<String>, DefaultMutableTreeNode> groupedNodes = new HashMap<>();
		DiffTreeNodes.collectNodes(grouped, new ArrayList<>(), groupedNodes);
		List<String> key = DiffTreeNodes.keyOf(flatMember);
		assertEquals(key, DiffTreeNodes.keyOf(groupedMember));
		assertSame(flatMember, flatNodes.get(key));
		assertSame(groupedMember, groupedNodes.get(key));
		assertSame(groupedMember, DiffTreeNodes.find(grouped, key));
		assertSame(flatMember, DiffTreeNodes.find(flat, key));
		assertSame(repoNode, DiffTreeNodes.find(grouped, DiffTreeNodes.keyOf(repoNode)));
	}
}
