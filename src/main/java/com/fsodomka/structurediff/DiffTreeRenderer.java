package com.fsodomka.structurediff;

import com.fsodomka.structurediff.ChangeFlashes.Flash;
import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.DiffTreeNodes.Ghost;
import com.fsodomka.structurediff.DiffTreeNodes.RepoNode;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.reason.EditReasonMembers.MemberReason;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.IconManager;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the nodes of the {@link StructureDiffPanel}'s tree: repository roots, changed files and their members, with
 * their status colors, signal icons, flashes and the highlight of the members related to the selection.
 */
final class DiffTreeRenderer extends ColoredTreeCellRenderer {
	private final Project project;
	private final MemberSignalState signals;
	private final ChangeFlashes flashes;
	private final UsagesController usages;
	/** VCS root of each file path; file paths are shown relative to it. */
	private Map<String, VirtualFile> vcsRoots = Map.of();
	/** Paths of the files in test sources, which get the test icon and their members' icons the test marker. */
	private Set<String> testPaths = Set.of();
	/** Package of each file in a source root, shown instead of its directory. */
	private Map<String, String> packages = Map.of();

	DiffTreeRenderer(Project project, MemberSignalState signals, ChangeFlashes flashes, UsagesController usages) {
		this.project = project;
		this.signals = signals;
		this.flashes = flashes;
		this.usages = usages;
	}

	/** Takes the file locations of a finished refresh; EDT only. */
	void update(Snapshot snapshot) {
		vcsRoots = snapshot.roots();
		testPaths = snapshot.tests();
		packages = snapshot.packages();
	}

	@Override
	public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
									  boolean leaf, int row, boolean hasFocus) {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
		reasonMark = null;
		Object item = node.getUserObject();
		boolean ghost = item instanceof Ghost;
		if (item instanceof Ghost(Object ghostItem)) {
			item = ghostItem;
		}
		Flash flash = flashes.get(node);
		// a removed node that vanished came back (removal undone): it flashes as added, not as gone
		boolean restored = ghost && flash != null && flash.status() == FileStatus.DELETED;
		Color background = flash == null ? null : flashBackground(tree, flash, ghost, restored);
		Relation relation = usages.relationOf(node);
		if (background == null && !selected && relation != null) {
			background = relation.background;
		}
		if (item instanceof RepoNode(VirtualFile root)) {
			setIcon(AllIcons.Nodes.Module);
			append(root.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
			append("  " + root.getPresentableUrl(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
			setToolTipText(null);
		}
		else if (item instanceof FileDiff file) {
			VirtualFile vcsRoot = vcsRoots.get(file.path());
			String basePath = vcsRoot != null ? vcsRoot.getPath() : StringUtil.notNullize(project.getBasePath());
			String path = DiffLabels.relativePath(file.path(), basePath);
			int slash = path.lastIndexOf('/');
			List<MemberFinding> found = ghost ? List.of() : signals.findingsOf(node);
			List<MemberDiagnostic> diags = ghost ? List.of() : signals.diagnosticsOf(node);
			List<MemberReason> reasons = ghost ? List.of() : signals.reasonsOf(node);
			// a test file gets the test icon instead of its type's: the marker overlay its members get is easy to miss
			Icon icon = testPaths.contains(file.path()) ? AllIcons.Nodes.Test : file.fileType().getIcon();
			setIcon(MemberSignals.withSignalIcons(icon, found, diags));
			append(path.substring(slash + 1), attributes(restored ? FileStatus.NOT_CHANGED : file.status(), ghost && !restored, background));
			if (file.members() != null && !ghost) {
				int[] counts = new int[3];
				DiffTreeNodes.count(file.members(), counts);
				DiffLabels.appendCounts(this, counts, SimpleTextAttributes.STYLE_SMALLER);
			}
			if (!ghost) {
				DiffLabels.appendLines(this, file.linesAdded(), file.linesRemoved(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
			}
			if (!found.isEmpty()) {
				append("  ⚠" + found.size(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
			}
			// the package, as the source root and the package all changes share say little; the tooltip has the full path
			String location = packages.getOrDefault(file.path(), slash > 0 ? path.substring(0, slash) : "");
			if (!location.isEmpty()) {
				append("  " + location, SimpleTextAttributes.GRAYED_ATTRIBUTES);
			}
			Change change = file.change();
			String beforePath = change.getBeforeRevision() == null ? null : change.getBeforeRevision().getFile().getPath();
			String moved = null;
			if ((change.isMoved() || change.isRenamed()) && beforePath != null) {
				// a rename shows the old name, a move the old path relative to the root
				String shown = change.isMoved() ? DiffLabels.relativePath(beforePath, basePath) : StringUtil.getShortName(beforePath, '/');
				append("  ← " + shown, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
				moved = (change.isMoved() ? "Moved from " : "Renamed from ") + FileUtil.toSystemDependentName(beforePath);
			}
			// last, after the package and a move's origin: the mark is about the change, not part of the file's name
			appendReasonMark(reasons, file.status());
			String lines = file.linesAdded() + file.linesRemoved() == 0 ? null : DiffLabels.linesText(file.linesAdded(), file.linesRemoved());
			StringBuilder tip = new StringBuilder("<html>").append(StringUtil.escapeXmlEntities(FileUtil.toSystemDependentName(path)));
			if (moved != null) {
				tip.append("<br>").append(StringUtil.escapeXmlEntities(moved));
			}
			if (lines != null) {
				tip.append("<br>").append(lines);
			}
			setToolTipText(MemberSignals.withSignals(tip.append("</html>").toString(), found, diags, reasons));
		}
		else if (item instanceof MemberDiff member) {
			List<MemberFinding> found = ghost ? List.of() : signals.findingsOf(node);
			List<MemberDiagnostic> diags = ghost ? List.of() : signals.diagnosticsOf(node);
			List<MemberReason> reasons = ghost ? List.of() : signals.reasonsOf(node);
			Icon icon = member.icon();
			if (((DefaultMutableTreeNode) node.getParent()).getUserObject() instanceof FileDiff parent) {
				icon = withTestMark(icon, parent.path());
			}
			setIcon(MemberSignals.withSignalIcons(icon, found, diags));
			String shortLabel = DiffLabels.shorten(member.label());
			append(shortLabel, attributes(restored ? FileStatus.NOT_CHANGED : member.status(), ghost && !restored, background));
			if (relation != null) {
				append("  " + relation.arrow, SimpleTextAttributes.GRAYED_ATTRIBUTES);
			}
			appendReasonMark(reasons, member.status());
			setToolTipText(MemberSignals.withSignals(DiffLabels.tooltip(member), found, diags, reasons));
		}
	}

	/**
	 * A mark that the node has edit reasons, so they are found without hovering; the tooltip lists them. In the label's
	 * status color, as in the treemap: grey, like the other counts, is too faint for a mark that invites hovering. Grey
	 * when no reason of the node is on changed code, as the gutter pencils such an anchor, so the mark never colors what
	 * the snapshot does not explain.
	 */
	private void appendReasonMark(List<MemberReason> reasons, FileStatus status) {
		if (!reasons.isEmpty()) {
			// a node's reasons can differ in kind and its mark is one: the status color while one of them shows changed code
			// in the color of the node's text, the muted red for a deleted member, so the mark reads as part of its name
			java.awt.Color mark = MemberReason.markColor(reasons);
			java.awt.Color color = mark != null ? mark : StatusColors.ofText(status);
			SimpleTextAttributes attributes = new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color);
			append("  ", attributes);
			// a fragment can hold no icon: an em space keeps the pencil's place, paintComponent draws it there; the space
			// after it keeps the count off the pencil, as the em space is about as wide as the icon in the smaller font
			reasonMark = StatusColors.pencilMark(color);
			append(reasons.size() > 1 ? "  " : " ", attributes, REASON_MARK);
			if (reasons.size() > 1) {
				append(String.valueOf(reasons.size()), attributes);
			}
		}
	}

	/** The tag of the fragment the reason mark's pencil is drawn over. */
	static final Object REASON_MARK = new Object();
	/** The pencil of the node being painted, null when it has no reasons. */
	private Icon reasonMark;

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (reasonMark == null) {
			return;
		}
		// the fragment's start: the component tells which fragment is at an x, not where one starts
		for (int x = 0; x < getWidth(); x++) {
			int index = findFragmentAt(x);
			if (index >= 0 && getFragmentTag(index) == REASON_MARK) {
				reasonMark.paintIcon(this, g, x, (getHeight() - reasonMark.getIconHeight()) / 2);
				return;
			}
		}
	}

	/** {@code icon} with the test marker when {@code path} is in test sources, as the Project view shows test classes. */
	private Icon withTestMark(Icon icon, String path) {
		if (icon == null || !testPaths.contains(path)) {
			return icon;
		}
		return IconManager.getInstance().createLayered(icon, AllIcons.Nodes.JunitTestMark);
	}

	/** The status color (removed for a ghost, added for a restored node) blended into the tree background, fading with the flash. */
	private static Color flashBackground(JTree tree, Flash flash, boolean ghost, boolean restored) {
		double strength = flash.strength(System.currentTimeMillis());
		if (strength <= 0) {
			return null;
		}
		Color color = StatusColors.of(restored ? FileStatus.ADDED : ghost ? FileStatus.DELETED : flash.status());
		return color == null ? null : ColorUtil.mix(tree.getBackground(), color, 0.4 * strength);
	}

	/** A ghost is struck out in the deleted color: the node is gone after the flash, as deleted code is. */
	private static SimpleTextAttributes attributes(FileStatus status, boolean ghost, Color background) {
		int style = ghost || status == FileStatus.DELETED ? SimpleTextAttributes.STYLE_STRIKEOUT : SimpleTextAttributes.STYLE_PLAIN;
		if (background != null) {
			// the fragment background is painted only for opaque attributes; tree cell renderers are not opaque
			style |= SimpleTextAttributes.STYLE_OPAQUE;
		}
		Color foreground = StatusColors.ofText(ghost ? FileStatus.DELETED : status);
		return new SimpleTextAttributes(background, foreground, null, style);
	}
}
