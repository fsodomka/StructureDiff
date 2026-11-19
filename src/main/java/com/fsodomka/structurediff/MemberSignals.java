package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.reason.EditReason;
import com.fsodomka.structurediff.reason.EditReasonMembers.MemberReason;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.RowIcon;

import javax.swing.Icon;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
	 * diagnostics; for the tree and the treemap.
	 */
	static String withSignals(String tooltip, List<MemberFinding> found, List<MemberDiagnostic> diags) {
		return withSignals(tooltip, found, diags, List.of());
	}

	/** Reasons listed in a tooltip; the rest are counted. */
	static final int MAX_TOOLTIP_REASONS = 5;
	/** Characters of one reason shown in a tooltip; a longer one ends in an ellipsis. */
	static final int MAX_TOOLTIP_REASON_LENGTH = 200;
	/** Characters per tooltip line of a reason: tooltips only keep to the screen, so a long line would span it. */
	static final int TOOLTIP_REASON_LINE = 60;

	/** As {@link #withSignals(String, List, List)}, with a "Why" section of the agent's edit reasons first. */
	static String withSignals(String tooltip, List<MemberFinding> found, List<MemberDiagnostic> diags, List<MemberReason> reasons) {
		if (found.isEmpty() && diags.isEmpty() && reasons.isEmpty()) {
			return tooltip;
		}
		StringBuilder html = new StringBuilder("<html>");
		if (tooltip != null) {
			html.append(tooltip.startsWith("<html>")
					? StringUtil.trimEnd(StringUtil.trimStart(tooltip, "<html>"), "</html>")
					: StringUtil.escapeXmlEntities(tooltip)).append("<br>");
		}
		// a blank line before each section: the one after the tooltip ends its last line, later ones the section before
		String gap = "<br>";
		if (!reasons.isEmpty()) {
			html.append(gap).append("<b>Why</b>");
			gap = "<br><br>";
			for (MemberReason reason : reasons.subList(0, Math.min(reasons.size(), MAX_TOOLTIP_REASONS))) {
				html.append("<br>").append(EditReason.when(reason.time())).append(" · ")
						.append(reason.pending() ? "<i>reason pending</i>" : wrapped(
								StringUtil.shortenTextWithEllipsis(reason.reason(), MAX_TOOLTIP_REASON_LENGTH, 0, true)));
				if (reason.approximate()) {
					html.append(" <i>(approximate)</i>");
				}
			}
			if (reasons.size() > MAX_TOOLTIP_REASONS) {
				html.append("<br>and ").append(reasons.size() - MAX_TOOLTIP_REASONS).append(" more");
			}
		}
		if (!found.isEmpty()) {
			html.append(gap).append("<b>Review</b>");
			gap = "<br><br>";
			for (MemberFinding finding : found) {
				html.append("<br>").append(StringUtil.escapeXmlEntities(finding.title()));
			}
		}
		if (!diags.isEmpty()) {
			html.append(gap).append("<b>IDE</b>");
			for (MemberDiagnostic diag : diags.subList(0, Math.min(diags.size(), MAX_TOOLTIP_DIAGNOSTICS))) {
				html.append("<br>").append(diag.level().text).append(" · ").append(StringUtil.escapeXmlEntities(diag.message()));
			}
			if (diags.size() > MAX_TOOLTIP_DIAGNOSTICS) {
				html.append("<br>and ").append(diags.size() - MAX_TOOLTIP_DIAGNOSTICS).append(" more");
			}
		}
		return html.append("</html>").toString();
	}

	/** {@code text} escaped, broken into lines of at most {@link #TOOLTIP_REASON_LINE} characters between words (a longer word alone). */
	static String wrapped(String text) {
		StringBuilder html = new StringBuilder();
		int lineLength = 0;
		for (String word : text.split("\\s+")) {
			if (lineLength > 0 && lineLength + 1 + word.length() > TOOLTIP_REASON_LINE) {
				html.append("<br>");
				lineLength = 0;
			}
			else if (lineLength > 0) {
				html.append(' ');
				lineLength++;
			}
			html.append(StringUtil.escapeXmlEntities(word));
			lineLength += word.length();
		}
		return html.toString();
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

	/**
	 * Each reason once, the newest of its text; pending ones once in all. One reason often covers several changes (one
	 * entry for a whole file, or one tool call with several ranges), and the mod writes a record per change.
	 */
	static List<MemberReason> distinct(List<MemberReason> reasons) {
		Map<String, MemberReason> byText = new LinkedHashMap<>();
		for (MemberReason reason : reasons) {
			byText.putIfAbsent(reason.pending() ? "" : reason.reason(), reason);
		}
		return byText.size() == reasons.size() ? reasons : List.copyOf(byText.values());
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
