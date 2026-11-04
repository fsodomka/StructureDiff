package com.fsodomka.structurediff.review;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;

import java.util.ArrayList;
import java.util.List;

import static com.fsodomka.structurediff.PropertyChecks.forAll;

/**
 * Properties of {@link ReviewAnchor#locate}, checked with jetCheck. Filler lines never contain "SNIP", so a snippet occurs
 * only where a scenario puts it.
 */
public class ReviewAnchorProperties extends BasePlatformTestCase {
	private static final Generator<String> SNIPPET = Generator.sampledFrom("SNIP();", "int SNIP = 1;", "}SNIP", "SNIP");
	private static final Generator<String> FILLER_LINE = Generator.anyOf(
			Generator.sampledFrom("", "}", "{", "\tint a = 0;", "    return a;", "// note", "foo(bar);"),
			Generator.stringsOf(IntDistribution.uniform(0, 12), Generator.charsFrom("ab \t};")));
	private static final Generator<String> INDENT = Generator.sampledFrom("", "\t", "    ");

	private static Finding finding(int line, int column, int offset, @Nullable String snippet) {
		return new Finding("f", "RULE", ReviewSeverity.WARNING, 2, "message", null, "A.java", line, column, offset,
				snippet == null ? -1 : snippet.length(), snippet);
	}

	private static Document document(String text) {
		return EditorFactory.getInstance().createDocument(text);
	}

	/** Any text and finding, the snippet often occurring once, several times or not at all. */
	private record Arbitrary(String text, Finding finding) {
	}

	private static final Generator<Arbitrary> ARBITRARY = Generator.from(env -> {
		List<String> lines = env.generate(Generator.listsOf(IntDistribution.uniform(0, 12),
				Generator.anyOf(FILLER_LINE, Generator.zipWith(INDENT, SNIPPET, String::concat))));
		String text = String.join("\n", lines) + env.generate(Generator.sampledFrom("", "\n"));
		String snippet = switch (env.generate(Generator.integers(0, 3))) {
			case 0 -> null;
			case 1 -> "";
			case 2 -> {
				int start = env.generate(Generator.integers(0, text.length()));
				yield text.substring(start, env.generate(Generator.integers(start, text.length())));
			}
			default -> env.generate(SNIPPET);
		};
		int line = env.generate(Generator.integers(-1, lines.size() + 2));
		int column = env.generate(Generator.integers(-1, 20));
		int offset = env.generate(Generator.integers(-1, text.length() + 5));
		return new Arbitrary(text, finding(line, column, offset, snippet));
	});

	/**
	 * A snippet reviewed on one line, at an offset counted in LF or CRLF text, then {@code inserted} lines added above it
	 * and {@code below} lines added after it.
	 */
	private record Moved(List<String> lines, int snippetLine, int insertAt, List<String> inserted, List<String> below,
			String snippet, int column, boolean crlfOffset) {

		List<String> current() {
			List<String> current = new ArrayList<>(lines);
			current.addAll(snippetLine + 1, below);
			current.addAll(insertAt, inserted);
			return current;
		}

		int currentLine() {
			return snippetLine + inserted.size();
		}

		Finding finding() {
			int offset = offset(lines, snippetLine, column) + (crlfOffset ? snippetLine : 0);
			return ReviewAnchorProperties.finding(snippetLine + 1, column, offset, snippet);
		}
	}

	private static final Generator<Moved> MOVED = Generator.from(env -> {
		List<String> lines = new ArrayList<>(env.generate(Generator.listsOf(IntDistribution.uniform(0, 12), FILLER_LINE)));
		int snippetLine = env.generate(Generator.integers(0, lines.size()));
		String indent = env.generate(INDENT);
		String snippet = env.generate(SNIPPET);
		lines.add(snippetLine, indent + snippet + env.generate(Generator.sampledFrom("", " // x")));
		int insertAt = env.generate(Generator.integers(0, snippetLine));
		// within the move window, and past it
		int count = env.generate(Generator.frequency(3, Generator.integers(0, 3), 2, Generator.integers(4, 50), 1, Generator.integers(51, 70)));
		List<String> inserted = env.generate(Generator.listsOf(IntDistribution.uniform(count, count), FILLER_LINE));
		List<String> below = env.generate(Generator.listsOf(IntDistribution.uniform(0, 5), FILLER_LINE));
		return new Moved(lines, snippetLine, insertAt, inserted, below, snippet, indent.length() + 1, env.generate(Generator.booleans()));
	});

	/** Offset of {@code column} (1-based) of {@code line} (0-based) in the lines joined by LF. */
	private static int offset(List<String> lines, int line, int column) {
		int offset = 0;
		for (int i = 0; i < line; i++) {
			offset += lines.get(i).length() + 1;
		}
		return offset + column - 1;
	}

	public void testAnchorsStayInTheTextAndOnOneLine() {
		forAll(ARBITRARY, a -> {
			Document document = document(a.text);
			ReviewAnchor.Anchor anchor = ReviewAnchor.locate(document, a.finding);
			String snippet = a.finding.snippet();
			String message = a + " -> " + anchor;
			assertTrue(message, 0 <= anchor.start() && anchor.start() <= anchor.end() && anchor.end() <= a.text.length());
			boolean hasSnippet = snippet != null && !snippet.isEmpty();
			// a line anchor, unlike a snippet, which may span lines, stays on its line
			boolean lineAnchor = anchor.status() == ReviewAnchor.Status.STALE || anchor.status() == ReviewAnchor.Status.EXACT && !hasSnippet;
			assertFalse(message, lineAnchor && a.text.substring(anchor.start(), anchor.end()).contains("\n"));
			switch (anchor.status()) {
				case EXACT, MOVED -> {
					if (hasSnippet) {
						assertEquals(message, snippet, a.text.substring(anchor.start(), anchor.end()));
					}
				}
				case GONE -> assertTrue(message, hasSnippet && !a.text.contains(snippet) && anchor.start() == 0 && anchor.end() == 0);
				case FILE -> assertTrue(message, a.finding.line() < 1 && anchor.start() == 0 && anchor.end() == 0);
				default -> {
				}
			}
			if (anchor.status() == ReviewAnchor.Status.MOVED) {
				assertEquals(message, a.text.indexOf(snippet), a.text.lastIndexOf(snippet));
			}
		});
	}

	/** In place, by its offset in LF or CRLF text; moved by up to 50 lines; else stale on the reviewed line. */
	public void testSnippetIsFollowedWhileLinesAreInsertedNearIt() {
		forAll(MOVED, m -> {
			List<String> current = m.current();
			String text = String.join("\n", current);
			ReviewAnchor.Anchor anchor = ReviewAnchor.locate(document(text), m.finding());
			String message = m + " -> " + anchor;
			int moved = m.inserted.size();
			if (moved <= 50) {
				assertEquals(message, moved == 0 ? ReviewAnchor.Status.EXACT : ReviewAnchor.Status.MOVED, anchor.status());
				assertEquals(message, offset(current, m.currentLine(), m.column), anchor.start());
				assertEquals(message, m.snippet, text.substring(anchor.start(), anchor.end()));
			}
			else {
				assertEquals(message, ReviewAnchor.Status.STALE, anchor.status());
				assertEquals(message, m.snippetLine, document(text).getLineNumber(anchor.start()));
			}
		});
	}

	public void testSnippetRemovedIsGone() {
		forAll(MOVED, m -> {
			List<String> current = new ArrayList<>(m.current());
			current.set(m.currentLine(), "gone();");
			ReviewAnchor.Anchor anchor = ReviewAnchor.locate(document(String.join("\n", current)), m.finding());
			assertEquals(m + " -> " + anchor, ReviewAnchor.Status.GONE, anchor.status());
		});
	}

	/** Which of two copies is the reviewed code is unknown, so neither counts as it moved there. */
	public void testRepeatedSnippetIsNeverMoved() {
		record Repeated(Moved moved, int copyAt) {
		}
		forAll(Generator.zipWith(MOVED, Generator.integers(0, 1000), Repeated::new), r -> {
			List<String> current = new ArrayList<>(r.moved.current());
			current.add(r.copyAt % (current.size() + 1), r.moved.snippet);
			ReviewAnchor.Anchor anchor = ReviewAnchor.locate(document(String.join("\n", current)), r.moved.finding());
			assertFalse(r + " -> " + anchor, anchor.status() == ReviewAnchor.Status.MOVED);
		});
	}
}
