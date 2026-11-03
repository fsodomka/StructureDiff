package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiffTreeNodes.Ghost;
import com.fsodomka.structurediff.DiffTreeNodes.RepoNode;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.tree.DefaultMutableTreeNode;

/** Texts of the changes tree and its summary: shortened member labels, member and line counts, tooltips. */
final class DiffLabels {
	private DiffLabels() {
	}

	/**
	 * Changed lines as one grey, unsigned number with a unit, so only the colored member counts use +/~/−;
	 * the added/removed split is in the tooltip.
	 */
	static void appendLines(SimpleColoredComponent component, int added, int removed, SimpleTextAttributes attributes) {
		int lines = added + removed;
		if (lines > 0) {
			component.append("   · " + lines + (lines == 1 ? " line" : " lines"), attributes);
			component.setToolTipText(linesText(added, removed));
		}
	}

	static String linesText(int added, int removed) {
		// a side with no lines is left out, as in "10 lines added" rather than "10 lines added, 0 removed"
		if (removed == 0) {
			return added + (added == 1 ? " line" : " lines") + " added";
		}
		if (added == 0) {
			return removed + (removed == 1 ? " line" : " lines") + " removed";
		}
		return added + " lines added, " + removed + " removed";
	}

	/** Text the speed search matches: the name shown by the tree renderer. */
	static String speedSearchText(DefaultMutableTreeNode node) {
		Object item = node.getUserObject();
		if (item instanceof Ghost(Object ghostItem)) {
			item = ghostItem;
		}
		return switch (item) {
			case RepoNode(VirtualFile root) -> root.getName();
			case FileDiff file -> StringUtil.getShortName(file.path(), '/');
			case MemberDiff member -> shorten(member.label());
			case null, default -> "";
		};
	}

	/** The added/modified/removed {@link DiffTreeNodes#count}s, each in its status color, where not 0. */
	static void appendCounts(SimpleColoredComponent component, int[] counts, int style) {
		appendCount(component, "+", counts[0], FileStatus.ADDED, style);
		appendCount(component, "~", counts[1], FileStatus.MODIFIED, style);
		appendCount(component, "−", counts[2], FileStatus.DELETED, style);
	}

	private static void appendCount(SimpleColoredComponent component, String prefix, int count, FileStatus status, int style) {
		if (count > 0) {
			component.append(" " + prefix + count, new SimpleTextAttributes(style, status.getColor()));
		}
	}

	/** {@code path} relative to {@code basePath} when it is below it, else {@code path}. */
	static String relativePath(String path, String basePath) {
		return path.startsWith(basePath + "/") ? path.substring(basePath.length() + 1) : path;
	}

	/** Line counts, plus the old and new signature when it changed. */
	static String tooltip(MemberDiff member) {
		StringBuilder html = new StringBuilder("<html>");
		if (!member.beforeLabel().equals(member.label())) {
			html.append("<b>Before:</b> ").append(StringUtil.escapeXmlEntities(member.beforeLabel()))
					.append("<br><b>After:</b> ").append(StringUtil.escapeXmlEntities(member.label())).append("<br>");
		}
		else if (!shorten(member.label()).equals(member.label())) {
			html.append(StringUtil.escapeXmlEntities(member.label())).append("<br>");
		}
		if (member.status() == FileStatus.MODIFIED) {
			html.append(linesText(member.linesAdded(), member.linesRemoved()));
		}
		else {
			html.append(member.linesAdded() + member.linesRemoved()).append(" lines");
		}
		return html.append("</html>").toString();
	}

	/** {@code foo(List<Map<A, B>>, int): Pair<X, Y>} -> {@code foo(List, int): Pair}; long parameter lists cut after the first. */
	static String shorten(String label) {
		String result = stripGenerics(label);
		int open = result.indexOf('(');
		int close = result.lastIndexOf(')');
		if (open > 0 && close > open && close - open > 60) {
			int comma = result.indexOf(',', open);
			if (comma > 0 && comma < close) {
				result = result.substring(0, comma) + ", …" + result.substring(close);
			}
		}
		return result;
	}

	private static String stripGenerics(String label) {
		StringBuilder sb = new StringBuilder(label.length());
		int depth = 0;
		for (int i = 0; i < label.length(); i++) {
			char c = label.charAt(i);
			if (c == '<') {
				depth++;
			}
			else if (c == '>' && depth > 0) {
				depth--;
			}
			else if (depth == 0) {
				sb.append(c);
			}
		}
		return depth == 0 ? sb.toString() : label;
	}
}
