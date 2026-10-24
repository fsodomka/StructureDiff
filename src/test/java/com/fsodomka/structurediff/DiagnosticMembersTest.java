package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.Diagnostics;
import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class DiagnosticMembersTest extends BasePlatformTestCase {
	private static final String BROKEN = """
			class A {
			    void fine() {
			    }

			    void broken() {
			        unknownCall();
			    }
			}
			""";

	private static FileDiff file(String path, FileStatus status) {
		return new FileDiff(null, path, null, status, "", "", 1, 1, List.of());
	}

	private Diagnostics compute(List<FileDiff> files) {
		return ReadAction.compute(() -> DiagnosticMembers.prepare(getProject(), files, EnumSet.allOf(DiagnosticMembers.Level.class), new HashMap<>()).get());
	}

	public void testMapsTheDaemonsErrorsToTheirMember() {
		VirtualFile file = myFixture.configureByText("A.java", BROKEN).getVirtualFile();
		myFixture.doHighlighting();

		Diagnostics result = compute(List.of(file(file.getPath(), FileStatus.MODIFIED)));

		assertEquals(Set.of(file.getPath()), result.analysed());
		List<MemberDiagnostic> found = result.byPath().get(file.getPath());
		assertNotNull(found);
		MemberDiagnostic first = found.getFirst();
		assertEquals(HighlightSeverity.ERROR, first.severity());
		assertTrue(first.message(), first.message().contains("unknownCall"));
		assertEquals(BROKEN.indexOf("unknownCall"), first.offset());
		// class A > broken()
		assertEquals(2, first.keyPath().size());
		assertTrue(first.keyPath().toString(), first.keyPath().get(1).contains("broken"));
		assertEquals(AllIcons.Nodes.ErrorIntroduction, first.icon());
	}

	public void testOnlyTheLevelsTurnedOnAreShown() {
		VirtualFile file = myFixture.configureByText("A.java", BROKEN).getVirtualFile();
		myFixture.doHighlighting();

		Diagnostics result = ReadAction.compute(() -> DiagnosticMembers.prepare(getProject(),
				List.of(file(file.getPath(), FileStatus.MODIFIED)), EnumSet.of(DiagnosticMembers.Level.WARNING), new HashMap<>()).get());

		// analysed, but its error is not shown
		assertEquals(Set.of(file.getPath()), result.analysed());
		assertNull(result.byPath().get(file.getPath()));
	}

	public void testFilesWithoutAnEditorAreNotAnalysed() {
		VirtualFile closed = myFixture.addFileToProject("B.java", BROKEN).getVirtualFile();
		VirtualFile open = myFixture.configureByText("A.java", "class A {}").getVirtualFile();
		myFixture.doHighlighting();

		Diagnostics result = compute(List.of(file(closed.getPath(), FileStatus.MODIFIED), file(open.getPath(), FileStatus.DELETED)));

		// the deleted one is open, yet has no after side to analyse
		assertEquals(Set.of(), result.analysed());
		assertTrue(result.byPath().isEmpty());
	}
}
