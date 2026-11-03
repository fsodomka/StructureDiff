package com.fsodomka.structurediff;

import com.fsodomka.structurediff.MemberLinks.Link;
import com.fsodomka.structurediff.MemberLinks.Links;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.IconManager;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * The callers and callees of the member selected in the Structure Diff tree, see {@link MemberLinks}: "Used by" and
 * "Uses", each with the internal links, in the member's own file, and the external ones, by module. Within a group the
 * members nest by their member path, so a class holds its linked members. Shown below the tree.
 */
final class UsagesPanel extends JPanel implements Disposable {
	/** How long the panel waits before showing that it is busy, so a quick search does not flicker. */
	private static final int BUSY_DELAY_MS = 250;

	private final Project project;
	private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
	private final SimpleColoredComponent header = new SimpleColoredComponent();
	private final Alarm busyAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);

	/** A section or group row: its icon, text and a grey detail. */
	record Heading(Icon icon, String text, String detail) {
	}

	/**
	 * A member row: its label and icon, and the {@code link} when the member holds references, null for an enclosing
	 * member that holds none. {@code file} is the file name shown after a top-level member whose name differs from it,
	 * else null.
	 */
	record Entry(String label, Icon icon, @Nullable Link link, @Nullable String file) {
	}

	/** @param jumpToSource opens the member whose links are shown, when its header is clicked */
	UsagesPanel(Project project, BooleanSupplier jumpToSource) {
		super(new BorderLayout());
		this.project = project;
		header.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e) && header.getCharSequence(false).length() > 0) {
					jumpToSource.getAsBoolean();
				}
			}
		});
		tree.setRootVisible(false);
		tree.setCellRenderer(new Renderer());
		Trees.installOpenHandler(tree, this::openSelected);
		header.setBorder(JBUI.Borders.empty(2, 8));
		add(header, BorderLayout.NORTH);
		add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER);
		clear();
	}

	/** Shows the links of the member labeled {@code title}. */
	void show(String title, Links links) {
		busyAlarm.cancelAllRequests();
		tree.setPaintBusy(false);
		setHeader(title);
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		if (!links.callers().isEmpty()) {
			root.add(section(AllIcons.Actions.StepOut, "Used by", links.callers(),
					links.callersCapped() ? MemberLinks.CAP + "+ references" : null));
		}
		// a capped search shows even without callees: those it did not get to are unknown, not absent
		if (!links.callees().isEmpty() || links.calleesCapped()) {
			root.add(section(AllIcons.Actions.TraceInto, "Uses", links.callees(),
					links.calleesCapped() ? links.callees().size() + "+ members, first " + MemberLinks.RESOLVE_CAP + " references" : null));
		}
		setRoot(root, "No usages", false);
	}

	/**
	 * Shows that a search for the member labeled {@code title} started: its header at once, so a click on it jumps to the
	 * member named, and the busy tree after {@link #BUSY_DELAY_MS}; the current links stay until then.
	 */
	void showBusy(String title) {
		busyAlarm.cancelAllRequests();
		setHeader(title);
		busyAlarm.addRequest(() -> {
			tree.setPaintBusy(true);
			setRoot(new DefaultMutableTreeNode(), "Searching…", true);
		}, BUSY_DELAY_MS);
	}

	void showMessage(String message) {
		busyAlarm.cancelAllRequests();
		tree.setPaintBusy(false);
		header.clear();
		header.setToolTipText(null);
		header.setCursor(null);
		setRoot(new DefaultMutableTreeNode(), message, false);
	}

	/** Names the member whose links are shown or searched; a click on it jumps to that member. */
	private void setHeader(String title) {
		header.clear();
		header.append(title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
		header.setToolTipText("Jump to Source");
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	void clear() {
		showMessage("Select a member to see what it uses and what uses it");
	}

	private void setRoot(DefaultMutableTreeNode root, String emptyText, boolean busy) {
		tree.getEmptyText().clear().appendLine(busy ? AnimatedIcon.Default.INSTANCE : null, emptyText,
				SimpleTextAttributes.REGULAR_ATTRIBUTES, null);
		((DefaultTreeModel) tree.getModel()).setRoot(root);
		Trees.expandAll(tree);
	}

	/**
	 * The {@code links} under {@code title}, internal ones, then external ones by module: a home folder for the selected
	 * member's own file, a globe for the other files. {@code cappedDetail} replaces the count when the search was capped.
	 */
	static DefaultMutableTreeNode section(Icon icon, String title, List<Link> links, @Nullable String cappedDetail) {
		DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Heading(icon, title,
				cappedDetail != null ? cappedDetail : links.size() + (links.size() == 1 ? " member" : " members")));
		List<Link> internal = links.stream().filter(Link::internal).toList();
		if (!internal.isEmpty()) {
			DefaultMutableTreeNode group = new DefaultMutableTreeNode(new Heading(AllIcons.Nodes.HomeFolder, "Internal", String.valueOf(internal.size())));
			addNested(group, internal);
			section.add(group);
		}
		List<Link> external = links.stream().filter(link -> !link.internal()).toList();
		if (!external.isEmpty()) {
			DefaultMutableTreeNode group = new DefaultMutableTreeNode(new Heading(AllIcons.Nodes.PpWeb, "External", String.valueOf(external.size())));
			Map<String, List<Link>> byModule = new TreeMap<>();
			external.forEach(link -> byModule.computeIfAbsent(link.module(), m -> new ArrayList<>()).add(link));
			byModule.forEach((module, moduleLinks) -> {
				DefaultMutableTreeNode parent = group;
				if (!module.isEmpty() && byModule.size() > 1) {
					parent = new DefaultMutableTreeNode(new Heading(AllIcons.Nodes.Module, module, String.valueOf(moduleLinks.size())));
					group.add(parent);
				}
				addNested(parent, moduleLinks);
			});
			section.add(group);
		}
		return section;
	}

	/**
	 * Adds {@code links} below {@code parent}, each at its member path: an enclosing member is one row, holding its linked
	 * members, whether it holds references itself or not. A link outside any member is a row of its file.
	 */
	private static void addNested(DefaultMutableTreeNode parent, List<Link> links) {
		Map<List<String>, DefaultMutableTreeNode> nodes = new HashMap<>();
		for (Link link : links) {
			String fileName = link.file().getName();
			if (link.keys().isEmpty()) {
				parent.add(new DefaultMutableTreeNode(new Entry(fileName, link.file().getFileType().getIcon(), link, null)));
				continue;
			}
			DefaultMutableTreeNode node = parent;
			for (int i = 0; i < link.keys().size(); i++) {
				List<String> key = new ArrayList<>(link.keys().subList(0, i + 1));
				key.addFirst(link.file().getPath());
				DefaultMutableTreeNode above = node;
				int depth = i;
				node = nodes.computeIfAbsent(key, k -> {
					String label = link.labels().get(depth);
					String file = depth == 0 && !label.equals(link.file().getNameWithoutExtension()) ? fileName : null;
					DefaultMutableTreeNode created = new DefaultMutableTreeNode(new Entry(label, link.icons().get(depth), null, file));
					above.add(created);
					return created;
				});
			}
			Entry entry = (Entry) node.getUserObject();
			node.setUserObject(new Entry(entry.label(), entry.icon(), link, entry.file()));
		}
	}

	/** Opens the selected link's file at its first reference. */
	private boolean openSelected() {
		if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Entry entry
				&& entry.link() instanceof Link link && link.file().isValid()) {
			new OpenFileDescriptor(project, link.file(), link.offset()).navigate(true);
			return true;
		}
		return false;
	}

	/** The root of the shown links: sections of {@link Heading}s and {@link Entry}s. */
	@TestOnly
	DefaultMutableTreeNode root() {
		return (DefaultMutableTreeNode) tree.getModel().getRoot();
	}

	@Override
	public void dispose() {
	}

	private static final class Renderer extends ColoredTreeCellRenderer {
		@Override
		public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
										  int row, boolean hasFocus) {
			Object item = ((DefaultMutableTreeNode) value).getUserObject();
			if (item instanceof Heading(Icon icon, String text, String detail)) {
				setIcon(icon);
				append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
				append("  " + detail, SimpleTextAttributes.GRAYED_ATTRIBUTES);
			}
			else if (item instanceof Entry entry) {
				Link link = entry.link();
				Icon icon = entry.icon();
				if (icon != null && link != null && link.test()) {
					icon = IconManager.getInstance().createLayered(icon, AllIcons.Nodes.JunitTestMark);
				}
				setIcon(icon);
				// an enclosing member that holds no references is context, not a result
				append(entry.label(), link == null ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
				if (entry.file() != null) {
					append("  " + entry.file(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
				}
				if (link != null && link.count() > 1) {
					append("  ×" + link.count(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
				}
				setToolTipText(link == null ? null : link.file().getPresentableUrl() + (link.line() >= 0 ? ":" + (link.line() + 1) : ""));
			}
		}
	}
}
