package com.fsodomka.structurediff;

import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How often each line of a file was changed, tracked through the file's texts: see {@code docs/specs/2026-11-20-treemap-churn-heat-design.md}.
 * A counter per line of the current text; whitespace-only differences are ignored by comparing the lines trimmed.
 */
final class ChurnLines {
	private ChurnLines() {
	}

	/** The lines of {@code text} trimmed; none for an empty text. */
	static String[] lines(String text) {
		return text.isEmpty() ? new String[0] : text.lines().map(String::strip).toArray(String[]::new);
	}

	/**
	 * The counters of the lines of {@code after}, given those of {@code before}'s. An unchanged line keeps its counter. Of
	 * a replaced block the first min(deleted, inserted) lines pair up and inherit the counter plus one when
	 * {@code count}; the extra inserted lines are new lines, 1 when {@code count}. Deleted lines drop out. Without
	 * {@code count} the counters only follow the lines to the new text, and changed and new lines start at 0. When the
	 * texts are too big to diff, every line of {@code after} counts as new.
	 */
	static int[] advance(int[] counters, String[] before, String[] after, boolean count) {
		int[] next = new int[after.length];
		Diff.Change change;
		try {
			change = Diff.buildChanges(before, after);
		}
		catch (FilesTooBigForDiffException e) {
			Arrays.fill(next, count ? 1 : 0);
			return next;
		}
		int old = 0;
		int now = 0;
		while (true) {
			int stopOld = change == null ? before.length : change.line0;
			int stopNow = change == null ? after.length : change.line1;
			while (old < stopOld && now < stopNow) {
				next[now++] = counters[old++];
			}
			if (change == null) {
				return next;
			}
			for (int k = 0; k < change.inserted; k++) {
				next[now + k] = !count ? 0 : k < change.deleted ? counters[old + k] + 1 : 1;
			}
			old += change.deleted;
			now += change.inserted;
			change = change.link;
		}
	}

	/**
	 * The member's churn over its lines {@code first}..{@code last} (0-based, inclusive): the sum of the counters, less
	 * one per line for a new member whose first change is its creation, per line of the member.
	 */
	static double churn(int[] counters, int first, int last, boolean isNew) {
		int size = last - first + 1;
		if (size <= 0) {
			return 0;
		}
		long sum = 0;
		for (int line = Math.max(first, 0); line <= last && line < counters.length; line++) {
			sum += Math.max(0, counters[line] - (isNew ? 1 : 0));
		}
		return sum / (double) size;
	}

	/**
	 * One commit of a file's history: its parent (null for a root commit), the file's path in the parent (null when the
	 * commit created it) and its path in the commit (null when the commit deleted it), relative to the repository root.
	 */
	record Step(String commit, String parent, String pathBefore, String pathAfter) {
	}

	/**
	 * The steps in {@code git log --follow --name-status --format=%x01%H %P} output. A commit without a status line, as a
	 * merge shows none, is left out: its changes are in the commits it merged.
	 */
	static List<Step> parseLog(List<String> output) {
		List<Step> steps = new ArrayList<>();
		String commit = null;
		String parent = null;
		for (String raw : output) {
			String line = raw.strip();
			if (line.startsWith("")) {
				String[] ids = line.substring(1).split(" ");
				commit = ids[0];
				parent = ids.length > 1 ? ids[1] : null;
			}
			else if (!line.isEmpty() && commit != null) {
				String[] parts = line.split("	");
				String status = parts[0];
				String last = parts[parts.length - 1];
				steps.add(new Step(commit, parent, status.startsWith("A") ? null : parts[1], status.startsWith("D") ? null : last));
				commit = null;
			}
		}
		return steps;
	}

	/** The offsets at which each line of {@code text} starts. */
	static int[] lineStarts(String text) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			// as String.lines() breaks them: \n, \r\n, and a lone \r
			if (c == '\n' || c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
				starts.add(i + 1);
			}
		}
		return starts.stream().mapToInt(Integer::intValue).toArray();
	}

	/** The 0-based line of {@code offset}. */
	static int lineOf(int[] lineStarts, int offset) {
		int index = Arrays.binarySearch(lineStarts, offset);
		return index >= 0 ? index : -index - 2;
	}
}
