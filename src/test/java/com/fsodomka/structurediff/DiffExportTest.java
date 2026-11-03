package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DiffExportTest extends BasePlatformTestCase {
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

	public void testExportRoots() throws IOException {
		VirtualFile root1 = myFixture.getTempDirFixture().findOrCreateDir("r1");
		VirtualFile root2 = myFixture.getTempDirFixture().findOrCreateDir("r2");
		VirtualFile root3 = myFixture.getTempDirFixture().findOrCreateDir("r3");
		FileDiff a = file("/r1/a.xml");
		FileDiff b = file("/r2/b.xml");
		FileDiff c = file("/r3/c.xml");
		FileDiff unversioned = file("/elsewhere/d.xml");
		Map<String, VirtualFile> vcsRoots = Map.of(a.path(), root1, b.path(), root2, c.path(), root3);

		assertEquals(List.of(), DiffExport.exportRoots(List.of(), vcsRoots, List.of()));
		assertEquals(List.of(), DiffExport.exportRoots(List.of(unversioned), vcsRoots, List.of()));
		// a single root needs no selection
		assertEquals(List.of(root1), DiffExport.exportRoots(List.of(a, unversioned), vcsRoots, List.of()));

		// several roots: all of them when nothing with a root is selected
		List<FileDiff> all = List.of(a, b, c);
		assertEquals(List.of(root1, root2, root3), DiffExport.exportRoots(all, vcsRoots, List.of()));
		assertEquals(List.of(root1, root2, root3),
				DiffExport.exportRoots(all, vcsRoots, List.of(new DefaultMutableTreeNode(unversioned))));
		// the selected repositories, in tree order, and only those
		assertEquals(List.of(root2, root3), DiffExport.exportRoots(all, vcsRoots, List.of(
				new DefaultMutableTreeNode(new DiffTreeNodes.RepoNode(root2)),
				new DefaultMutableTreeNode(new DiffTreeNodes.RepoNode(root3)))));
		// a file or member selects its own repository
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(b);
		DefaultMutableTreeNode memberNode = add(fileNode, member("root"));
		assertEquals(List.of(root2), DiffExport.exportRoots(all, vcsRoots, List.of(memberNode)));
		// several files of one repository are still one diff
		assertEquals(List.of(root2), DiffExport.exportRoots(all, vcsRoots, List.of(
				fileNode, new DefaultMutableTreeNode(b))));
	}
}
