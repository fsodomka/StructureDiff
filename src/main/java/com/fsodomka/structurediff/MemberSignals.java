package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.RowIcon;

import javax.swing.Icon;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The review findings and IDE diagnostics shown on the nodes of the Structure Diff tree and treemap. */
final class MemberSignals {
	private MemberSignals() {
	}

	/** Findings per {@link ReviewSeverity} ordinal in {@code shownFiles}, so the sum of the file nodes' counts. */
	static int[] findingCounts(List<FileDiff> shownFiles, Map<String, List<MemberFinding>> findings) {
		int[] counts = new int[ReviewSeverity.values().length];
		for (FileDiff file : shownFiles) {
			findings.getOrDefault(file.path(), List.of()).forEach(finding -> counts[finding.severity().ordinal()]++);
		}
		return counts;
	}

	/** Diagnostics listed in a tooltip; the rest are counted. */
	static final int MAX_TOOLTIP_DIAGNOSTICS = 5;

	/**
	 * {@code tooltip} (plain text or HTML) followed by a section per source, the review findings, then the IDE's
	 * diagnostics, and a line when the file is not analysed; for the tree and the treemap.
	 */
	static String withSignals(String tooltip, List<MemberFinding> found, List<MemberDiagnostic> diags, boolean notAnalysed) {
		if (found.isEmpty() && diags.isEmpty() && !notAnalysed) {
			return tooltip;
		}
		StringBuilder html = new StringBuilder("<html>");
		if (tooltip != null) {
			html.append(tooltip.startsWith("<html>")
					? StringUtil.trimEnd(StringUtil.trimStart(tooltip, "<html>"), "</html>")
					: StringUtil.escapeXmlEntities(tooltip)).append("<br>");
		}
		if (!found.isEmpty()) {
			html.append("<br><b>Review</b>");
			for (MemberFinding finding : found) {
				html.append("<br>").append(StringUtil.escapeXmlEntities(finding.title()));
			}
		}
		if (!diags.isEmpty()) {
			html.append("<br><b>IDE</b>");
			for (MemberDiagnostic diag : diags.subList(0, Math.min(diags.size(), MAX_TOOLTIP_DIAGNOSTICS))) {
				html.append("<br>").append(diag.level().text).append(" · ").append(StringUtil.escapeXmlEntities(diag.message()));
			}
			if (diags.size() > MAX_TOOLTIP_DIAGNOSTICS) {
				html.append("<br>and ").append(diags.size() - MAX_TOOLTIP_DIAGNOSTICS).append(" more");
			}
		}
		if (notAnalysed) {
			html.append("<br><i>Not analysed by the IDE: double-click to open it</i>");
		}
		return html.append("</html>").toString();
	}

	/** {@code icon} followed by the icon of the most severe finding, then that of the most severe diagnostic. */
	static Icon withSignalIcons(Icon icon, List<MemberFinding> found, List<MemberDiagnostic> diags) {
		List<Icon> icons = new ArrayList<>(3);
		if (icon != null) {
			icons.add(icon);
		}
		if (!found.isEmpty()) {
			icons.add(found.getFirst().icon());
		}
		if (!diags.isEmpty()) {
			icons.add(diags.getFirst().icon());
		}
		return icons.isEmpty() ? null : icons.size() == 1 ? icons.getFirst() : new RowIcon(icons.toArray(Icon[]::new));
	}

	static List<MemberFinding> findingsOf(DefaultMutableTreeNode node, Map<String, List<MemberFinding>> findings) {
		return placedOn(node, findings, MemberFinding::keyPath);
	}

	/** Items by absolute file path shown on a node, placed as {@link #findingsOf(DefaultMutableTreeNode, Map)} places findings. */
	static <T> List<T> placedOn(DefaultMutableTreeNode node, Map<String, List<T>> byPath, Function<T, List<String>> keyPathOf) {
		if (byPath.isEmpty()) {
			return List.of();
		}
		List<String> keyPath = new ArrayList<>();
		DefaultMutableTreeNode fileNode = node;
		while (fileNode != null && fileNode.getUserObject() instanceof MemberDiff member) {
			keyPath.addFirst(member.key());
			fileNode = (DefaultMutableTreeNode) fileNode.getParent();
		}
		if (fileNode == null || !(fileNode.getUserObject() instanceof FileDiff file)) {
			return List.of();
		}
		List<T> fileItems = byPath.getOrDefault(file.path(), List.of());
		int depth = keyPath.size();
		if (depth == 0) {
			return fileItems;
		}
		return fileItems.stream()
				.filter(item -> {
					List<String> itemPath = keyPathOf.apply(item);
					return itemPath.size() >= depth && itemPath.subList(0, depth).equals(keyPath)
							&& (itemPath.size() == depth || !hasMemberChild(node, itemPath.get(depth)));
				})
				.toList();
	}

	static boolean hasMemberChild(DefaultMutableTreeNode node, String key) {
		for (int i = 0; i < node.getChildCount(); i++) {
			if (((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject() instanceof MemberDiff member && member.key().equals(key)) {
				return true;
			}
		}
		return false;
	}
}
