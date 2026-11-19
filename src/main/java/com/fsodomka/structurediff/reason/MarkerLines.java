package com.fsodomka.structurediff.reason;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The lines a marker covers in its document, 0-based and inclusive. The document is passed in because resolving a
 * marker's own needs read access from 2025.2, which callers on the EDT take once and reuse.
 */
public record MarkerLines(int first, int last) {
	public static MarkerLines of(Document document, RangeMarker marker) {
		return new MarkerLines(document.getLineNumber(marker.getStartOffset()), document.getLineNumber(marker.getEndOffset()));
	}

	/**
	 * {@code items} by first line, grouped into runs whose lines overlap or touch: the places the gutter draws as one
	 * bar and pencil. Each run keeps its items in that order, so its first is the one that starts it.
	 */
	public static <T> List<List<T>> runs(Collection<T> items, Function<T, MarkerLines> lines) {
		List<T> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.comparingInt(item -> lines.apply(item).first()));
		List<List<T>> runs = new ArrayList<>();
		int last = 0;
		for (T item : sorted) {
			MarkerLines itemLines = lines.apply(item);
			if (!runs.isEmpty() && itemLines.first() <= last + 1) {
				runs.getLast().add(item);
				last = Math.max(last, itemLines.last());
			}
			else {
				runs.add(new ArrayList<>(List.of(item)));
				last = itemLines.last();
			}
		}
		return runs;
	}

	/** The lines as a location suffix, 1-based: {@code :12} or {@code :12-15}. */
	public String suffix() {
		return first == last ? ":" + (first + 1) : ":" + (first + 1) + "-" + (last + 1);
	}
}
