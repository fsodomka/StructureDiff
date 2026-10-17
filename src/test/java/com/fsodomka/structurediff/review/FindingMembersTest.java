package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.Map;

public class FindingMembersTest extends BasePlatformTestCase {
	private static final String TEXT = """
			<?xml version="1.0"?>
			<root>
			  <a>
			    <b>x</b>
			  </a>
			  <c/>
			</root>
			""";

	private static Finding finding(String fingerprint, String path, int line, int rank) {
		return new Finding(fingerprint, "RULE", ReviewSeverity.WARNING, rank, "message " + fingerprint + "\nmore", null,
				path, line, -1, -1, 0, null);
	}

	public void testMapsFindingsToTheInnermostMember() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		VirtualFile root = file.getParent();
		List<Placement> placements = List.of(
				new Placement(finding("nested", "a.xml", 4, 2), root, file, ReviewAnchor.Status.EXACT, null),
				new Placement(finding("sibling", "a.xml", 6, 0), root, file, ReviewAnchor.Status.EXACT, null),
				new Placement(finding("outside", "a.xml", 1, 3), root, file, ReviewAnchor.Status.EXACT, null),
				new Placement(finding("noLine", "a.xml", 99, 3), root, file, ReviewAnchor.Status.EXACT, null),
				// possibly fixed: left out, though its reviewed line is in a member
				new Placement(finding("gone", "a.xml", 4, 0), root, file, ReviewAnchor.Status.GONE, null),
				new Placement(finding("missing", "Missing.xml", 1, 1), root, null, ReviewAnchor.Status.MISSING_FILE, null));

		Map<String, List<MemberFinding>> result = ReadAction.compute(() -> FindingMembers.prepare(getProject(), placements).get());

		List<MemberFinding> found = result.get(file.getPath());
		// sorted by rank, most severe first
		assertEquals(List.of("CRITICAL · message sibling", "MEDIUM · message nested", "LOW · message outside", "LOW · message noLine"),
				found.stream().map(MemberFinding::title).toList());
		assertEquals(List.of(List.of("root", "c"), List.of("root", "a", "b"), List.of(), List.of()),
				found.stream().map(MemberFinding::keyPath).toList());
		assertEquals(ReviewSeverity.WARNING.icon, found.getFirst().icon());

		List<MemberFinding> missing = result.get(root.getPath() + "/Missing.xml");
		assertEquals(1, missing.size());
		assertEquals(List.of(), missing.getFirst().keyPath());
	}

	public void testReusesTheKeyPathsOfAnUnchangedText() {
		VirtualFile file = myFixture.addFileToProject("a.xml", TEXT).getVirtualFile();
		List<Placement> placements = List.of(
				new Placement(finding("nested", "a.xml", 4, 2), file.getParent(), file, ReviewAnchor.Status.EXACT, null));
		Map<String, FindingMembers.CachedKeyPaths> cache = ReviewFindingsService.getInstance(getProject()).keyPathCache();
		// held, so the document and with it its text instance is not collected between the calls
		com.intellij.openapi.editor.Document document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
		assertNotNull(document);

		ReadAction.compute(() -> FindingMembers.prepare(getProject(), placements).get());
		FindingMembers.CachedKeyPaths first = cache.get(file.getPath());
		ReadAction.compute(() -> FindingMembers.prepare(getProject(), placements).get());
		assertSame(first, cache.get(file.getPath()));

		// another finding file: the entries of files without findings are dropped
		VirtualFile other = myFixture.addFileToProject("b.xml", TEXT).getVirtualFile();
		ReadAction.compute(() -> FindingMembers.prepare(getProject(),
				List.of(new Placement(finding("other", "b.xml", 4, 2), other.getParent(), other, ReviewAnchor.Status.EXACT, null))).get());
		assertEquals(java.util.Set.of(other.getPath()), cache.keySet());
	}
}
