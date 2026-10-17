package com.fsodomka.structurediff.review;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class ReviewAnchorTest extends BasePlatformTestCase {
	private static final String TEXT = "class A {\n\tint ttl = 5;\n\tint other;\n}\n";

	private static Finding finding(int line, int column, int offset, String snippet) {
		return new Finding("f", "RULE", ReviewSeverity.WARNING, 2, "message", null, "A.java", line, column, offset,
				snippet == null ? -1 : snippet.length(), snippet);
	}

	private static ReviewAnchor.Anchor locate(String text, Finding finding) {
		Document document = EditorFactory.getInstance().createDocument(text);
		return ReviewAnchor.locate(document, finding);
	}

	public void testExactByOffset() {
		ReviewAnchor.Anchor anchor = locate(TEXT, finding(2, 2, 11, "int ttl = 5;"));
		assertEquals(ReviewAnchor.Status.EXACT, anchor.status());
		assertEquals("int ttl = 5;", TEXT.substring(anchor.start(), anchor.end()));
	}

	public void testExactByLineAndColumnWhenOffsetCountsCrlf() {
		ReviewAnchor.Anchor anchor = locate(TEXT, finding(2, 2, 12, "int ttl = 5;"));
		assertEquals(ReviewAnchor.Status.EXACT, anchor.status());
		assertEquals(11, anchor.start());
	}

	public void testSnippetAtOffsetOnAnotherLineIsNotExact() {
		// "}" now sits at the reviewed offset 24, but on line 3; it occurs once, so it counts as moved, not exact
		ReviewAnchor.Anchor anchor = locate(TEXT.replace("\tint other;\n", ""), finding(2, 2, 24, "}"));
		assertEquals(ReviewAnchor.Status.MOVED, anchor.status());
	}

	public void testSnippetIsNotExactWhenTheReviewedLineIsGone() {
		// line 5 no longer exists; the "}" at the offset, on the last line, is found by its text instead
		ReviewAnchor.Anchor anchor = locate("a\n}", finding(5, 1, 2, "}"));
		assertEquals(ReviewAnchor.Status.MOVED, anchor.status());
	}

	public void testMovedByInsertedLines() {
		String moved = "// one\n// two\n" + TEXT;
		ReviewAnchor.Anchor anchor = locate(moved, finding(2, 2, 11, "int ttl = 5;"));
		assertEquals(ReviewAnchor.Status.MOVED, anchor.status());
		assertEquals("int ttl = 5;", moved.substring(anchor.start(), anchor.end()));
	}

	public void testStaleWhenSnippetNotUnique() {
		// the reviewed "}" of line 4 was removed; the brace of another block must not count as moved
		String text = "class A {\n\tvoid f() {\n\t\tg();\n\t}\n\tvoid h() {\n\t}\n}\n";
		String edited = text.replace("\t\tg();\n\t}\n", "\t\tg();\n");
		ReviewAnchor.Anchor anchor = locate(edited, finding(4, 2, 30, "}"));
		assertEquals(ReviewAnchor.Status.STALE, anchor.status());
	}

	public void testGoneWhenSnippetRemoved() {
		ReviewAnchor.Anchor anchor = locate(TEXT.replace("5", "10"), finding(2, 2, 11, "int ttl = 5;"));
		assertEquals(ReviewAnchor.Status.GONE, anchor.status());
	}

	public void testStaleWhenSnippetFarAway() {
		String far = "\n".repeat(100) + "int ttl = 5;\n";
		ReviewAnchor.Anchor anchor = locate("x\n" + far, finding(1, 1, 0, "int ttl = 5;"));
		assertEquals(ReviewAnchor.Status.STALE, anchor.status());
		assertEquals(0, anchor.start());
		assertEquals(1, anchor.end());
	}

	public void testLineWithoutSnippetSkipsIndentation() {
		ReviewAnchor.Anchor anchor = locate(TEXT, finding(3, -1, -1, null));
		assertEquals(ReviewAnchor.Status.EXACT, anchor.status());
		assertEquals("int other;", TEXT.substring(anchor.start(), anchor.end()));
	}

	public void testLineBeyondEndIsStale() {
		assertEquals(ReviewAnchor.Status.STALE, locate(TEXT, finding(99, -1, -1, null)).status());
	}

	public void testNoLineNoSnippetIsFileLevel() {
		ReviewAnchor.Anchor anchor = locate(TEXT, finding(-1, -1, -1, null));
		assertEquals(ReviewAnchor.Status.FILE, anchor.status());
		assertEquals(0, anchor.end());
	}

	public void testNoLineFindsUniqueSnippet() {
		ReviewAnchor.Anchor anchor = locate(TEXT, finding(-1, -1, -1, "int ttl = 5;"));
		assertEquals(ReviewAnchor.Status.EXACT, anchor.status());
		assertEquals(11, anchor.start());
	}

	public void testNoLineAmbiguousSnippetIsFileLevel() {
		assertEquals(ReviewAnchor.Status.FILE, locate(TEXT, finding(-1, -1, -1, "int")).status());
	}

	public void testNoLineRemovedSnippetIsGone() {
		assertEquals(ReviewAnchor.Status.GONE, locate(TEXT, finding(-1, -1, -1, "removed")).status());
	}
}
