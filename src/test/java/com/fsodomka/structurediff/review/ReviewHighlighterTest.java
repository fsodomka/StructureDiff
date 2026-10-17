package com.fsodomka.structurediff.review;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReviewHighlighterTest extends BasePlatformTestCase {
	private static final String TEXT = "class A {\n\tint ttl = 5;\n}\n";

	private static Finding finding(String fingerprint, String path, String snippet) {
		return new Finding(fingerprint, "RULE", ReviewSeverity.WARNING, 2, "message", "fix", path, 2, 2, 11,
				snippet.length(), snippet);
	}

	private RangeHighlighter[] highlighters(Document document) {
		return DocumentMarkupModel.forDocument(document, getProject(), true).getAllHighlighters();
	}

	public void testPlacesRemovesAndReplaces() {
		VirtualFile file = myFixture.addFileToProject("A.txt", TEXT).getVirtualFile();
		VirtualFile root = file.getParent();
		Document document = FileDocumentManager.getInstance().getDocument(file);
		ReviewHighlighter highlighter = new ReviewHighlighter(getProject());

		Finding found = finding("1", "A.txt", "int ttl = 5;");
		Finding gone = finding("2", "A.txt", "removed code");
		Finding missing = finding("3", "Missing.txt", "int ttl = 5;");
		highlighter.place(root, List.of(found, gone, missing));

		assertEquals(3, highlighter.placements().size());
		RangeHighlighter[] all = highlighters(document);
		assertEquals(1, all.length);
		assertEquals("int ttl = 5;", document.getText().substring(all[0].getStartOffset(), all[0].getEndOffset()));
		assertNotNull(all[0].getGutterIconRenderer());
		List<ReviewAnchor.Status> statuses = highlighter.placements().stream().map(Placement::status).toList();
		assertEquals(List.of(ReviewAnchor.Status.EXACT, ReviewAnchor.Status.GONE, ReviewAnchor.Status.MISSING_FILE), statuses);

		// a reload from disk replaces the whole text; the finding is placed again by its snippet
		WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("// header\n" + TEXT));
		assertTrue(highlighter.replaceIn(file));
		Placement placement = highlighter.placements().stream().filter(p -> p.finding().equals(found)).findFirst().orElseThrow();
		assertEquals(ReviewAnchor.Status.MOVED, placement.status());
		assertEquals(3, placement.line());
		assertEquals(1, highlighters(document).length);

		highlighter.remove(found);
		assertEquals(0, highlighters(document).length);
		highlighter.removeAll();
		assertTrue(highlighter.placements().isEmpty());
	}

	public void testFileLevelFindingHasGutterIconOnly() {
		VirtualFile file = myFixture.addFileToProject("A.txt", TEXT).getVirtualFile();
		Document document = FileDocumentManager.getInstance().getDocument(file);
		ReviewHighlighter highlighter = new ReviewHighlighter(getProject());
		highlighter.place(file.getParent(), List.of(new Finding("1", "RULE", ReviewSeverity.WARNING, 2, "message", null,
				"A.txt", -1, -1, -1, -1, null)));

		Placement placement = highlighter.placements().iterator().next();
		assertEquals(ReviewAnchor.Status.FILE, placement.status());
		assertEquals(-1, placement.line());
		assertEquals(-1, placement.offset());
		RangeHighlighter[] all = highlighters(document);
		assertEquals(1, all.length);
		assertEquals(0, all[0].getEndOffset());
		assertNotNull(all[0].getGutterIconRenderer());
		highlighter.removeAll();
	}

	public void testResolvesOnlyLocalUrls() throws IOException {
		VirtualFile root = myFixture.addFileToProject("A.txt", TEXT).getVirtualFile().getParent();
		Path local = Files.writeString(Files.createTempFile("review", ".txt"), TEXT);
		try {
			VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(local);
			assertNotNull(file);
			assertEquals(file, ReviewHighlighter.resolve(root, file.getUrl()));
			assertNull(ReviewHighlighter.resolve(root, "http://example.com/A.txt"));
			assertNull(ReviewHighlighter.resolve(root, "https://example.com/A.txt"));
			assertNull(ReviewHighlighter.resolve(root, root.getUrl() + "/A.txt"));
		} finally {
			Files.delete(local);
		}
	}
}
