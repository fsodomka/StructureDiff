package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.reason.EditReasonMembers.MemberReason;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.*;

/**
 * The nodes of a {@link JTree} as nested rectangles sized by their changed lines and colored by their status. Shares the
 * tree's model and selection: a click selects the node in the tree, so the tree's actions apply to it. A click on a
 * container's header zooms into it; a breadcrumb leads back.
 */
final class TreemapPanel extends JComponent {
	/** The Coverage fill's ends: orange and blue, unlike the status red and green, and told apart without hue vision. */
	private static final Color UNCOVERED = new JBColor(new Color(0xE0_6C_00), new Color(0xF0_8C_30));
	private static final Color COVERED = new JBColor(new Color(0x1F_6F_D6), new Color(0x4C_95_F0));
	/** The Churn fill's hot end: orange, as a member changed again and again; not the status red of deleted. New code is the status green. */
	private static final Color HOT = new JBColor(new Color(0xE0_6C_00), new Color(0xF0_8C_30));
	/** A churn of this many changes per line, and above, is fully hot. */
	private static final double HOT_CHURN = 2.0;
	/** The {@link ViewOptions.Fill#FILE_TYPE} hues, most changed type first; clear of the status green and red. */
	private static final List<Color> TYPE_HUES = List.of(
			new JBColor(new Color(0x3A_6E_C9), new Color(0x6A_9B_E8)),
			new JBColor(new Color(0xE0_7B_10), new Color(0xF0_A0_4B)),
			new JBColor(new Color(0x8A_4F_D1), new Color(0xB0_8A_EE)),
			new JBColor(new Color(0x0F_95_A3), new Color(0x4C_C4_D1)),
			new JBColor(new Color(0xD1_4F_A8), new Color(0xE8_8A_CC)),
			new JBColor(new Color(0xA8_9A_10), new Color(0xD4_C6_4A)),
			new JBColor(new Color(0x8C_5A_3C), new Color(0xC0_8E_6E)),
			new JBColor(new Color(0x4F_6B_7A), new Color(0x8F_AB_BA)));
	/**
	 * The test icon's outline and glyph on a transparent disc, colorized per label color; white, as colorizing scales
	 * the color's brightness by the pixel's.
	 */
	private static final Icon TEST_OUTLINE = IconLoader.getIcon("/icons/testOutline.svg", TreemapPanel.class);

	private final JTree tree;
	private final Function<DefaultMutableTreeNode, String> label;
	/** Identifies a node among its siblings, stable across refreshes; labels are not unique, as overloads show. */
	private final Function<DefaultMutableTreeNode, String> key;
	private final Function<DefaultMutableTreeNode, List<MemberFinding>> findings;
	private final Function<DefaultMutableTreeNode, List<MemberReason>> reasons;
	private final Consumer<DefaultMutableTreeNode> onReason;
	private final Function<DefaultMutableTreeNode, List<MemberDiagnostic>> diagnostics;
	private final Predicate<DefaultMutableTreeNode> testFile;
	private final Function<DefaultMutableTreeNode, ChangeFlashes.Flash> flash;
	private final Function<DefaultMutableTreeNode, Relation> related;
	private final Supplier<Predicate<DefaultMutableTreeNode>> focus;
	/** {@link #focus} for the running paint. */
	private Predicate<DefaultMutableTreeNode> inFocus = node -> true;
	private final BooleanSupplier loading;
	private final Supplier<String> emptyText;
	private final Supplier<ViewOptions.Fill> fill;
	private final Function<DefaultMutableTreeNode, CoverageState.Counts> coverage;
	private final Supplier<String> coverageLegend;
	private final Function<DefaultMutableTreeNode, ChurnState.Churn> churn;
	private final Supplier<String> churnLegend;
	/** A message over the treemap, which takes no input meanwhile when it says so; null when none. */
	private final Supplier<Overlay> overlay;

	/** A message centered over the treemap, with a spinner when {@code busy}; dims it and takes its input when {@code blocksInput}. */
	record Overlay(String text, boolean busy, boolean blocksInput) {
	}
	/** The hues of this paint's file types, the most changed first; the ones past {@link #TYPE_HUES} are gray. */
	private final Map<String, Color> typeColors = new LinkedHashMap<>();
	/** Painted rectangles, parents before their children; the last one containing a point is the deepest node there. */
	private final List<Hit> hits = new ArrayList<>();
	/** Where a click zooms in: container headers, and whole containers too small to show their children. */
	private final List<Hit> zoomHits = new ArrayList<>();
	/** The painted severity icons; a click opens the node's findings. */
	private final List<Hit> iconHits = new ArrayList<>();
	/** The painted diagnostic icons; a click opens the node's most severe diagnostic. */
	private final List<Hit> diagnosticHits = new ArrayList<>();
	/** The pencils of the boxes' reason marks, whose click shows the first reason. */
	private final List<Hit> reasonHits = new ArrayList<>();
	/** The breadcrumb segments; a null node is the root. */
	private final List<Hit> crumbHits = new ArrayList<>();
	/** Weights of the nodes painted, computed once per paint. */
	private final Map<DefaultMutableTreeNode, Double> weights = new IdentityHashMap<>();
	/**
	 * The test icon in each label color painted, as colorizing renders it anew. Keyed by the ARGB value, not the theme
	 * color: the treemap is out of the component tree while the tree is shown, so a theme switch then skips
	 * {@link #updateUI} and a theme color key would find the old theme's icon.
	 */
	private final Map<Integer, Icon> testIcons = new HashMap<>();
	/** The size {@link #testIcons} are scaled to, which follows the UI scale. */
	private int testIconSize;
	/**
	 * Keys of the zoomed node's path below the root, empty when not zoomed. Keys, not nodes, because every refresh
	 * rebuilds the tree model.
	 */
	private List<String> zoom = List.of();

	private record Hit(Rectangle2D rect, DefaultMutableTreeNode node) {
	}

	/**
	 * @param key      identifies a node among its siblings across refreshes
	 * @param onOpen   double-click, like the tree's
	 * @param onDiff   middle click, like the tree's
	 * @param findings the review findings shown on a node, most severe first
	 * @param onFindings click on a severity icon, like the tree's
	 * @param reasons  the agent's edit reasons shown on a node, newest first
	 * @param diagnostics the IDE's diagnostics shown on a node, most severe first
	 * @param onDiagnostic click on a diagnostic icon, like the tree's
	 * @param testFile  whether a node is a file in test sources, marked with the tree's test icon in the label's color
	 * @param flash     the node's running flash, or null; the caller repaints while flashes run
	 * @param related   how a node is related to the selected one by its usages, or null; highlighted like the tree's
	 *                  row; the caller repaints when it changes
	 * @param focus     for each paint, whether a node stays at full strength while the usages are shown; a box
	 *                  without its children shown is faded when not
	 * @param loading   whether a refresh is in progress; the caller repaints when it changes
	 * @param emptyText shown when there is nothing to show, like the tree's
	 */
	TreemapPanel(JTree tree, Function<DefaultMutableTreeNode, String> label, Function<DefaultMutableTreeNode, String> key,
			Runnable onOpen, Runnable onDiff,
			Function<DefaultMutableTreeNode, List<MemberFinding>> findings, Consumer<List<MemberFinding>> onFindings,
			Function<DefaultMutableTreeNode, List<MemberReason>> reasons, Consumer<DefaultMutableTreeNode> onReason,
			Function<DefaultMutableTreeNode, List<MemberDiagnostic>> diagnostics,
			Consumer<DefaultMutableTreeNode> onDiagnostic, Predicate<DefaultMutableTreeNode> testFile,
			Function<DefaultMutableTreeNode, ChangeFlashes.Flash> flash, Function<DefaultMutableTreeNode, Relation> related,
			Supplier<Predicate<DefaultMutableTreeNode>> focus, BooleanSupplier loading, Supplier<String> emptyText,
			Supplier<ViewOptions.Fill> fill, Function<DefaultMutableTreeNode, CoverageState.Counts> coverage,
			Supplier<String> coverageLegend, Function<DefaultMutableTreeNode, ChurnState.Churn> churn, Supplier<String> churnLegend,
			Supplier<Overlay> overlay) {
		this.tree = tree;
		this.churn = churn;
		this.churnLegend = churnLegend;
		this.related = related;
		this.focus = focus;
		this.testFile = testFile;
		this.flash = flash;
		this.label = label;
		this.key = key;
		this.findings = findings;
		this.reasons = reasons;
		this.onReason = onReason;
		this.diagnostics = diagnostics;
		this.loading = loading;
		this.emptyText = emptyText;
		this.fill = fill;
		this.coverage = coverage;
		this.coverageLegend = coverageLegend;
		this.overlay = overlay;
		setOpaque(true);
		setToolTipText("");
		setFocusable(true);
		AbstractAction zoomOut = new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
				if (root == null || zoom.isEmpty()) {
					return;
				}
				// from where the zoom is shown, which a refresh may have moved up
				DefaultMutableTreeNode left = TreemapLayout.resolve(root, zoom, key, TreemapLayout::weight);
				List<String> shown = pathKeys(left);
				zoomTo(shown.isEmpty() ? shown : shown.subList(0, shown.size() - 1));
				// the container just left stays selected, so the arrows go on from it
				if (left != root) {
					tree.setSelectionPath(new TreePath(left.getPath()));
				}
			}
		};
		getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "zoomOut");
		getActionMap().put("zoomOut", zoomOut);
		getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter");
		getActionMap().put("enter", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				Hit hit = selectedHit();
				DefaultMutableTreeNode selected = hit == null ? null : hit.node();
				if (selected == null) {
					return;
				}
				if (selected.isLeaf()) {
					onOpen.run();
					return;
				}
				zoomTo(pathKeys(selected));
				// the largest child, which the layout puts first in the top left
				DefaultMutableTreeNode largest = null;
				for (int i = 0; i < selected.getChildCount(); i++) {
					DefaultMutableTreeNode child = (DefaultMutableTreeNode) selected.getChildAt(i);
					if (TreemapLayout.weight(child) > 0 && (largest == null || TreemapLayout.weight(child) > TreemapLayout.weight(largest))) {
						largest = child;
					}
				}
				if (largest != null) {
					tree.setSelectionPath(new TreePath(largest.getPath()));
				}
			}
		});
		int[][] arrows = {{KeyEvent.VK_LEFT, TreemapLayout.LEFT}, {KeyEvent.VK_RIGHT, TreemapLayout.RIGHT}, {KeyEvent.VK_UP, TreemapLayout.UP}, {KeyEvent.VK_DOWN, TreemapLayout.DOWN}};
		for (int[] arrow : arrows) {
			String name = "move" + arrow[1];
			getInputMap().put(KeyStroke.getKeyStroke(arrow[0], 0), name);
			getActionMap().put(name, new AbstractAction() {
				@Override
				public void actionPerformed(ActionEvent e) {
					move(arrow[1]);
				}
			});
		}
		tree.addTreeSelectionListener(e -> repaint());
		tree.getModel().addTreeModelListener(new TreeModelListener() {
			@Override
			public void treeNodesChanged(TreeModelEvent e) {
				repaint();
			}

			@Override
			public void treeNodesInserted(TreeModelEvent e) {
				repaint();
			}

			@Override
			public void treeNodesRemoved(TreeModelEvent e) {
				repaint();
			}

			@Override
			public void treeStructureChanged(TreeModelEvent e) {
				repaint();
			}
		});
		MouseAdapter mouse = new MouseAdapter() {
			/** Whether the last click zoomed, so its double-click does not also jump to the source. */
			private boolean zoomed;

			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				DefaultMutableTreeNode node = nodeAt(e.getPoint());
				if (node != null) {
					TreePath path = new TreePath(node.getPath());
					// Ctrl+click (Cmd on macOS) adds the node to the selection or removes it, as in the tree
					if (isToggle(e)) {
						if (tree.isPathSelected(path)) {
							tree.removeSelectionPath(path);
						}
						else {
							tree.addSelectionPath(path);
						}
					}
					// a right click on a selected node keeps the selection, as in the tree
					else if (!SwingUtilities.isRightMouseButton(e) || !tree.isPathSelected(path)) {
						tree.setSelectionPath(path);
					}
				}
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				boolean wasZoomed = zoomed;
				zoomed = false;
				// a toggle only selects: no zoom, no jump to source, no findings popup
				if (isToggle(e)) {
					return;
				}
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
					// the icon sits in the header, so it comes before zooming
					DefaultMutableTreeNode withIcon = hitAt(iconHits, e.getPoint());
					if (withIcon != null) {
						onFindings.accept(findings.apply(withIcon));
						return;
					}
					DefaultMutableTreeNode withReason = hitAt(reasonHits, e.getPoint());
					if (withReason != null) {
						onReason.accept(withReason);
						return;
					}
					DefaultMutableTreeNode withDiagnostic = hitAt(diagnosticHits, e.getPoint());
					if (withDiagnostic != null) {
						onDiagnostic.accept(withDiagnostic);
						return;
					}
					for (Hit crumb : crumbHits) {
						if (crumb.rect().contains(e.getPoint())) {
							zoomTo(crumb.node() == null ? List.of() : pathKeys(crumb.node()));
							zoomed = true;
							return;
						}
					}
					DefaultMutableTreeNode container = hitAt(zoomHits, e.getPoint());
					if (container != null) {
						zoomTo(pathKeys(container));
						zoomed = true;
						return;
					}
				}
				if (wasZoomed || nodeAt(e.getPoint()) == null) {
					return;
				}
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
					onOpen.run();
				}
				else if (SwingUtilities.isMiddleMouseButton(e)) {
					onDiff.run();
				}
			}

			private static boolean isToggle(MouseEvent e) {
				return SwingUtilities.isLeftMouseButton(e) && UIUtil.isControlKeyDown(e);
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				// a hand over a severity icon, as in the tree
				boolean onIcon = hitAt(iconHits, e.getPoint()) != null || hitAt(diagnosticHits, e.getPoint()) != null
						|| hitAt(reasonHits, e.getPoint()) != null;
				setCursor(onIcon ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	/**
	 * Paints {@code icon} left of the {@code taken} width in the top right corner of {@code r} when it fits, and records
	 * its hit; returns the width taken, 0 when it does not fit.
	 */
	private int paintCornerIcon(Graphics2D g, Icon icon, Rectangle r, int header, int pad, int taken,
								DefaultMutableTreeNode node, List<Hit> iconHits) {
		if (r.width < taken + icon.getIconWidth() + 2 * pad || r.height < icon.getIconHeight()) {
			return 0;
		}
		int width = icon.getIconWidth() + pad;
		int iconX = r.x + r.width - taken - width;
		int iconY = r.y + Math.max(0, (header - icon.getIconHeight()) / 2);
		icon.paintIcon(this, g, iconX, iconY);
		iconHits.add(new Hit(new Rectangle2D.Double(iconX, iconY, icon.getIconWidth(), icon.getIconHeight()), node));
		return width;
	}

	@Override
	public String getToolTipText(MouseEvent event) {
		if (blocked()) {
			return null;
		}
		DefaultMutableTreeNode node = nodeAt(event.getPoint());
		if (node == null) {
			return null;
		}
		int[] lines = TreemapLayout.lines(node.getUserObject());
		String text = label.apply(node);
		if (lines != null) {
			// a side with no lines is left out, as in "+10" rather than "+10 −0"
			String added = lines[0] > 0 ? "+" + lines[0] : "";
			String removed = lines[1] > 0 ? "−" + lines[1] : "";
			text += added.isEmpty() && removed.isEmpty() ? "" : "  " + (added.isEmpty() || removed.isEmpty() ? added + removed : added + " " + removed);
		}
		String fillValue = fillValue(node);
		if (fillValue != null) {
			text += "  ·  " + fillValue;
		}
		return MemberSignals.withSignals(text,
				findings.apply(node), diagnostics.apply(node), reasons.apply(node));
	}

	/** What the fill colors {@code node} by, for its tooltip; null for the Status fill, which the label already shows. */
	private @Nullable String fillValue(DefaultMutableTreeNode node) {
		switch (fill.get()) {
			case FILE_TYPE -> {
				FileDiff file = DiffTreeNodes.fileOf(node);
				return file == null ? null : TreemapLayout.fileTypeName(file);
			}
			case CHURN -> {
				ChurnState.Churn changes = churn.apply(node);
				if (changes == null) {
					return "no churn data";
				}
				return String.format("%.2f changes per line", changes.value()) + (changes.created() ? ", new code" : "");
			}
			case COVERAGE -> {
				CoverageState.Counts counts = coverage.apply(node);
				if (counts == null) {
					return "no coverage data";
				}
				return counts.percent() + " % covered (" +counts.covered() + " / " + counts.coverable() + " lines)"
						+ (counts.stale() ? ", edited since the run" : "");
			}
			default -> {
				return null;
			}
		}
	}

	private DefaultMutableTreeNode nodeAt(Point point) {
		return hitAt(hits, point);
	}

	/** The node of the last hit containing {@code point}, the deepest one. */
	private static DefaultMutableTreeNode hitAt(List<Hit> hits, Point point) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			if (hits.get(i).rect().contains(point)) {
				return hits.get(i).node();
			}
		}
		return null;
	}

	/** The painted box of the tree's selection, or null when nothing shown is selected. */
	private Hit selectedHit() {
		TreePath path = tree.getSelectionPath();
		Object selected = path == null ? null : path.getLastPathComponent();
		for (Hit hit : hits) {
			if (hit.node() == selected) {
				return hit;
			}
		}
		return null;
	}

	/**
	 * Selects the nearest painted sibling of the selection in {@code direction}; with no shown selection, the first
	 * top-level box painted.
	 */
	private void move(int direction) {
		Hit from = selectedHit();
		if (from == null) {
			if (!hits.isEmpty()) {
				tree.setSelectionPath(new TreePath(hits.getFirst().node().getPath()));
			}
			return;
		}
		List<Hit> siblings = hits.stream().filter(hit -> hit.node().getParent() == from.node().getParent()).toList();
		int next = TreemapLayout.neighbor(from.rect(), siblings.stream().map(Hit::rect).toList(), direction);
		if (next >= 0) {
			tree.setSelectionPath(new TreePath(siblings.get(next).node().getPath()));
		}
	}

	private void zoomTo(List<String> keys) {
		zoom = List.copyOf(keys);
		repaint();
	}

	/** Keys of the path from below the root down to {@code node}. */
	private List<String> pathKeys(DefaultMutableTreeNode node) {
		List<String> keys = new ArrayList<>();
		for (TreeNode n = node; n != null && n.getParent() != null; n = n.getParent()) {
			keys.addFirst(key.apply((DefaultMutableTreeNode) n));
		}
		return keys;
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			GraphicsUtil.setupAntialiasing(g2);
			g2.setColor(UIUtil.getTreeBackground());
			g2.fillRect(0, 0, getWidth(), getHeight());
			g2.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
			hits.clear();
			zoomHits.clear();
			iconHits.clear();
			diagnosticHits.clear();
			reasonHits.clear();
			crumbHits.clear();
			weights.clear();
			inFocus = focus.get();
			DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
			Rectangle2D bounds = new Rectangle2D.Double(0, 0, getWidth(), getHeight());
			if (root != null && !zoom.isEmpty()) {
				DefaultMutableTreeNode zoomed = TreemapLayout.resolve(root, zoom, key, this::cachedWeight);
				if (zoomed != root) {
					int height = paintBreadcrumb(g2, zoomed);
					bounds = new Rectangle2D.Double(0, height, getWidth(), getHeight() - height);
					root = zoomed;
				}
			}
			boolean loading = this.loading.getAsBoolean();
			Icon spinner = AnimatedIcon.Default.INSTANCE;
			if (root == null || cachedWeight(root) == 0) {
				g2.setColor(UIUtil.getInactiveTextColor());
				String empty = loading ? "Loading…" : emptyText.get();
				FontMetrics metrics = g2.getFontMetrics();
				int iconWidth = loading ? spinner.getIconWidth() + JBUI.scale(4) : 0;
				int x = (getWidth() - iconWidth - metrics.stringWidth(empty)) / 2;
				if (loading) {
					spinner.paintIcon(this, g2, x, getHeight() / 2 - metrics.getAscent() + (metrics.getAscent() - spinner.getIconHeight()) / 2);
				}
				g2.drawString(empty, x + iconWidth, getHeight() / 2);
				return;
			}
			typeColors.clear();
			if (fill.get() == ViewOptions.Fill.FILE_TYPE) {
				List<String> names = TreemapLayout.fileTypesBySize(root);
				for (int i = 0; i < names.size(); i++) {
					typeColors.put(names.get(i), i < TYPE_HUES.size() ? TYPE_HUES.get(i) : UIUtil.getLabelForeground());
				}
			}
			int legendHeight = paintLegend(g2);
			bounds = new Rectangle2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight() - legendHeight);
			paintChildren(g2, root, bounds);
			Overlay message = overlay.get();
			if (message != null) {
				paintOverlay(g2, message, spinner);
			}
			// a refresh in progress, in the corner as in the tree
			if (loading) {
				int pad = JBUI.scale(3);
				spinner.paintIcon(this, g2, getWidth() - spinner.getIconWidth() - pad, getHeight() - spinner.getIconHeight() - pad);
			}
		}
		finally {
			g2.dispose();
		}
	}

	/** Paints "All › repository › file" at the top, each segment zooming there; returns its height. */
	private int paintBreadcrumb(Graphics2D g, DefaultMutableTreeNode zoomed) {
		FontMetrics metrics = g.getFontMetrics();
		int pad = JBUI.scale(3);
		int height = metrics.getHeight() + 2 * pad;
		g.setColor(UIUtil.getPanelBackground());
		g.fillRect(0, 0, getWidth(), height);
		List<DefaultMutableTreeNode> path = new ArrayList<>();
		for (TreeNode n = zoomed; n != null; n = n.getParent()) {
			path.addFirst((DefaultMutableTreeNode) n);
		}
		String separator = " › ";
		List<String> texts = new ArrayList<>();
		for (int i = 0; i < path.size(); i++) {
			texts.add(i == 0 ? "All" : label.apply(path.get(i)));
		}
		// too wide: the segments after "All" give way to an ellipsis, the zoomed one stays
		int fullWidth = 2 * pad + texts.stream().mapToInt(metrics::stringWidth).sum()
				+ (texts.size() - 1) * metrics.stringWidth(separator);
		String ellipsis = "…" + separator;
		int hidden = 0;
		while (fullWidth > getWidth() && 1 + hidden < path.size() - 1) {
			fullWidth -= metrics.stringWidth(texts.get(1 + hidden)) + metrics.stringWidth(separator);
			if (hidden == 0) {
				fullWidth += metrics.stringWidth(ellipsis);
			}
			hidden++;
		}
		Shape clip = g.getClip();
		g.clipRect(0, 0, getWidth() - pad, height);
		int x = pad;
		int baseline = pad + metrics.getAscent();
		for (int i = 0; i < path.size(); i++) {
			if (i == 1 && hidden > 0) {
				g.setColor(UIUtil.getInactiveTextColor());
				g.drawString(ellipsis, x, baseline);
				x += metrics.stringWidth(ellipsis);
				i += hidden - 1;
				continue;
			}
			DefaultMutableTreeNode node = path.get(i);
			boolean last = i == path.size() - 1;
			String text = texts.get(i);
			int width = metrics.stringWidth(text);
			g.setColor(last ? UIUtil.getLabelForeground() : JBUI.CurrentTheme.Link.Foreground.ENABLED);
			g.drawString(text, x, baseline);
			if (!last) {
				crumbHits.add(new Hit(new Rectangle2D.Double(x, 0, width, height), i == 0 ? null : node));
				x += width;
				g.setColor(UIUtil.getInactiveTextColor());
				g.drawString(separator, x, baseline);
				x += metrics.stringWidth(separator);
			}
		}
		g.setClip(clip);
		return height;
	}

	private void paintChildren(Graphics2D g, DefaultMutableTreeNode parent, Rectangle2D bounds) {
		List<DefaultMutableTreeNode> children = new ArrayList<>();
		for (int i = 0; i < parent.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
			if (cachedWeight(child) > 0) {
				children.add(child);
			}
		}
		Rectangle2D[] rects = TreemapLayout.layout(children.stream().mapToDouble(this::cachedWeight).toArray(), bounds);
		for (int i = 0; i < rects.length; i++) {
			paintNode(g, children.get(i), rects[i]);
		}
	}

	private void paintNode(Graphics2D g, DefaultMutableTreeNode node, Rectangle2D rect) {
		// a node that appeared grows from its center, with its children laid out inside
		ChangeFlashes.Flash running = flash.apply(node);
		long now = System.currentTimeMillis();
		if (running != null) {
			rect = TreemapLayout.scaled(rect, running.grown(now));
		}
		if (rect.getWidth() < 1 || rect.getHeight() < 1) {
			return;
		}
		hits.add(new Hit(rect, node));
		Rectangle r = rect.getBounds();
		FileStatus status = status(node.getUserObject());
		Color color = statusColor(status);
		FontMetrics metrics = g.getFontMetrics();
		int pad = JBUI.scale(3);
		int header = metrics.getHeight() + pad;
		boolean nested = !node.isLeaf() && r.width > 4 * pad && r.height > header + 3 * pad;
		// focus on the selection and its related members: the other boxes fade. A container showing its children stays,
		// so its border and header keep the structure readable; its children fade or not on their own
		Composite composite = g.getComposite();
		if (!nested && !inFocus.test(node)) {
			g.setComposite(AlphaComposite.SrcOver.derive(0.5f));
		}
		// the status still shows in the border and label
		Color fillColor = color;
		// churn's heat is already the box's final color: the leaf tint would dim the orange to brown
		boolean exactFill = false;
		if (fill.get() == ViewOptions.Fill.FILE_TYPE) {
			fillColor = typeColor(node);
		}
		CoverageState.Counts counts = fill.get() == ViewOptions.Fill.COVERAGE ? coverage.apply(node) : null;
		if (fill.get() == ViewOptions.Fill.COVERAGE) {
			// the plain background without data (not a JVM file, no executable lines, or not in the run), so no share is taken
			// for it; opaque, as unfilled it would show its container's tint
			fillColor = counts == null ? null : coverageColor(2 * counts.share() - 1);
		}
		else if (fill.get() == ViewOptions.Fill.CHURN) {
			ChurnState.Churn changes = churn.apply(node);
			// the plain background without a value; new code in green until something changes it again
			fillColor = changes == null ? null : changes.created() && changes.value() == 0 ? statusColor(FileStatus.ADDED)
					: changes.value() == 0 ? null : churnColor(changes.value());
			exactFill = changes != null && changes.value() > 0;
		}
		// opaque, the tint premixed with the background: a translucent fill would add up with its container's, so the same
		// value would look different inside a tinted container than inside a plain one
		g.setColor(fillColor == null ? UIUtil.getTreeBackground()
				: node.isLeaf() ? exactFill ? fillColor : leafTint(fillColor) : ColorUtil.mix(UIUtil.getTreeBackground(), fillColor, 0.12));
		g.fillRect(r.x, r.y, r.width, r.height);
		// over the status tint, as the tree's row background: blended, the blue of a modified box turns the amber gray. The
		// border and label keep the status color. Only the header of a container showing its children, like the flash
		// below, so a class is not taken for related through one of its members
		if (counts != null && counts.stale() && (node.isLeaf() || !nested)) {
			paintHatch(g, r);
		}
		Relation relation = related.apply(node);
		if (relation != null && !tree.isPathSelected(new TreePath(node.getPath()))) {
			g.setColor(relation.background);
			g.fill(nested ? new Rectangle(r.x, r.y, r.width, header) : r);
		}
		g.setColor(ColorUtil.withAlpha(color, 0.6));
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);

		// under the label; a container showing its children flashes only its header, like its row in the tree, so a
		// class changed through one method does not look as if all its methods changed. At full strength the color
		// covers about half the background, a little more than the tree's flash, so the label in the same color stays
		// readable.
		double strength = running == null ? 0 : running.strength(now);
		if (strength > 0) {
			g.setColor(ColorUtil.withAlpha(color, (node.isLeaf() ? 0.25 : 0.4) * strength));
			g.fill(nested ? new Rectangle(r.x, r.y, r.width, header) : r);
		}
		// the icons of the most severe finding and diagnostic, in the top right corner in the tree row's order; the
		// diagnostic's only when both fit
		List<MemberFinding> found = findings.apply(node);
		List<MemberDiagnostic> diags = diagnostics.apply(node);
		Icon severity = found.isEmpty() ? null : found.getFirst().icon();
		Icon diagnostic = diags.isEmpty() ? null : diags.getFirst().icon();
		if (severity != null && diagnostic != null && r.width < severity.getIconWidth() + diagnostic.getIconWidth() + 3 * pad) {
			diagnostic = null;
		}
		int iconWidth = 0;
		if (diagnostic != null) {
			iconWidth += paintCornerIcon(g, diagnostic, r, header, pad, iconWidth, node, diagnosticHits);
		}
		if (severity != null) {
			iconWidth += paintCornerIcon(g, severity, r, header, pad, iconWidth, node, iconHits);
		}
		// the test icon before the label, as the tree's file icon, when it fits beside the corner icons; in the label's
		// color, as its own red and green would read as test results. Its outline only: colorizing the platform's icon
		// fills its light disc too and loses the glyph, or keeps it grey. Shrunk to clear the header's border
		boolean test = testFile.test(node);
		int markSize = JBUI.scale(14);
		if (markSize != testIconSize) {
			testIcons.clear();
			testIconSize = markSize;
		}
		Color labelColor = labelColor(color);
		Icon mark = test ? testIcons.computeIfAbsent(labelColor.getRGB(), rgb -> StatusColors.inColor(
				IconUtil.scale(TEST_OUTLINE, this, markSize / (float) TEST_OUTLINE.getIconHeight()), labelColor)) : null;
		int markWidth = 0;
		if (test && r.width >= iconWidth + mark.getIconWidth() + 3 * pad && r.height >= header) {
			mark.paintIcon(this, g, r.x + pad, r.y + (header - mark.getIconHeight()) / 2);
			markWidth = mark.getIconWidth() + pad;
		}
		int labelWidth = r.width - 2 * pad - iconWidth - markWidth;
		// the edit-reason mark after the label, as in the tree, so boxes with reasons are found without hovering
		List<MemberReason> nodeReasons = reasons.apply(node);
		// in the label's color, as a fixed one vanishes on the box color close to it, but grey when no reason of the node
		// is on changed code and purple when all are a skill's, as the tree marks it
		Color reasonColor = nodeReasons.isEmpty() ? null : MemberReason.markColor(nodeReasons);
		Icon pencil = nodeReasons.isEmpty() ? null
				: StatusColors.pencilMark(reasonColor != null ? reasonColor : labelColor);
		int pencilGap = metrics.charWidth(' ');
		int pencilWidth = pencil == null ? 0 : pencilGap + pencil.getIconWidth();
		String text = r.height >= metrics.getHeight() ? TreemapLayout.ellipsize(label.apply(node), metrics, labelWidth - pencilWidth) : "";
		if (!text.isEmpty()) {
			g.setColor(labelColor);
			// a container's label is its header; a leaf's is centered in the box
			int textWidth = metrics.stringWidth(text);
			int x = markWidth + (!node.isLeaf() ? r.x + pad : r.x + pad + (labelWidth - textWidth - pencilWidth) / 2);
			int y = !node.isLeaf() ? r.y + pad / 2 + metrics.getAscent()
					: r.y + (r.height - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(text, x, y);
			if (pencil != null) {
				// centered on the text line, whose top is the baseline less the ascent
				int pencilX = x + textWidth + pencilGap;
				int pencilY = y - metrics.getAscent() + (metrics.getHeight() - pencil.getIconHeight()) / 2;
				pencil.paintIcon(this, g, pencilX, pencilY);
				reasonHits.add(new Hit(new Rectangle2D.Double(pencilX, pencilY, pencil.getIconWidth(), pencil.getIconHeight()), node));
			}
		}
		if (nested) {
			paintChildren(g, node, new Rectangle2D.Double(r.x + pad, r.y + header, r.width - 2 * pad, r.height - header - pad));
			zoomHits.add(new Hit(new Rectangle2D.Double(r.x, r.y, r.width, header), node));
		}
		else if (!node.isLeaf()) {
			zoomHits.add(new Hit(rect, node));
		}
		if (tree.isPathSelected(new TreePath(node.getPath()))) {
			// neutral rather than the focus blue, which vanishes on modified boxes
			g.setStroke(new BasicStroke(JBUI.scale(2)));
			g.setColor(UIUtil.getLabelForeground());
			g.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
			g.setStroke(new BasicStroke());
		}
		g.setComposite(composite);
	}

	@Override
	public void updateUI() {
		super.updateUI();
		// the status colors change with the theme
		testIcons.clear();
	}

	/**
	 * Mixed from the background toward the covered blue for +1 and the uncovered orange for −1; at least a quarter of the
	 * hue, so a half-covered box stays tinted rather than looking like an unfilled one without data.
	 */
	private static Color coverageColor(double score) {
		Color end = score >= 0 ? COVERED : UNCOVERED;
		return ColorUtil.mix(UIUtil.getTreeBackground(), end, 0.25 + 0.75 * Math.abs(score));
	}

	/** The background mixed toward orange by {@code value} changes per line: from a third, so a little churn still shows, to nine tenths. */
	private static Color churnColor(double value) {
		return ColorUtil.mix(UIUtil.getTreeBackground(), HOT, 0.3 + 0.6 * Math.min(1, value / HOT_CHURN));
	}

	/** The hue of the type of {@code node}'s file; gray above the files, e.g. for a repository. */
	private Color typeColor(DefaultMutableTreeNode node) {
		FileDiff file = DiffTreeNodes.fileOf(node);
		return file == null ? UIUtil.getLabelForeground()
				: typeColors.getOrDefault(TreemapLayout.fileTypeName(file), UIUtil.getLabelForeground());
	}

	/** The legend of the fill along the bottom; returns the height it takes. */
	private int paintLegend(Graphics2D g) {
		FontMetrics metrics = g.getFontMetrics();
		int pad = JBUI.scale(3);
		int height = metrics.getHeight() + 2 * pad;
		int y = getHeight() - height;
		g.setColor(UIUtil.getTreeBackground());
		g.fillRect(0, y, getWidth(), height);
		if (fill.get() == ViewOptions.Fill.STATUS) {
			paintStatusLegend(g, y, pad);
		}
		else if (fill.get() == ViewOptions.Fill.FILE_TYPE) {
			paintTypeLegend(g, y, pad);
		}
		else if (fill.get() == ViewOptions.Fill.CHURN) {
			paintScaleLegend(g, y, pad, "0", "2+", churnLegend.get(), score -> churnColor(HOT_CHURN * (score + 1) / 2), true);
		}
		else if (fill.get() == ViewOptions.Fill.COVERAGE) {
			paintScaleLegend(g, y, pad, "0 %", "100 %", coverageLegend.get(), TreemapPanel::coverageColor, false);
		}
		return height;
	}

	/** A swatch and name per status, in the colors of the boxes' fill, border and label. */
	private void paintStatusLegend(Graphics2D g, int y, int pad) {
		List<Map.Entry<String, Color>> entries = new ArrayList<>();
		entries.add(Map.entry("Added", statusColor(FileStatus.ADDED)));
		entries.add(Map.entry("Modified", statusColor(FileStatus.MODIFIED)));
		entries.add(Map.entry("Deleted", statusColor(FileStatus.DELETED)));
		paintSwatchLegend(g, y, pad, entries);
	}

	/** A swatch and name per file type, the most changed first, then "other" for the gray ones. */
	private void paintTypeLegend(Graphics2D g, int y, int pad) {
		List<String> names = new ArrayList<>(typeColors.keySet());
		int colored = Math.min(names.size(), TYPE_HUES.size());
		List<Map.Entry<String, Color>> entries = new ArrayList<>();
		for (int i = 0; i < colored; i++) {
			entries.add(Map.entry(names.get(i), TYPE_HUES.get(i)));
		}
		if (names.size() > colored) {
			entries.add(Map.entry("other (" + (names.size() - colored) + ")", UIUtil.getLabelForeground()));
		}
		paintSwatchLegend(g, y, pad, entries);
	}

	/** A swatch and name per entry, then "+N" for those not fitting. */
	private void paintSwatchLegend(Graphics2D g, int y, int pad, List<Map.Entry<String, Color>> entries) {
		FontMetrics metrics = g.getFontMetrics();
		int swatch = JBUI.scale(8);
		int baseline = y + pad + metrics.getAscent();
		int swatchY = y + pad + (metrics.getHeight() - swatch) / 2;
		int x = pad;
		for (int i = 0; i < entries.size(); i++) {
			String text = entries.get(i).getKey();
			int width = swatch + pad + metrics.stringWidth(text) + 3 * pad;
			String more = "+" + (entries.size() - i);
			if (x + width > getWidth() - metrics.stringWidth(more) - pad && i < entries.size() - 1 || x + width > getWidth()) {
				g.setColor(UIUtil.getInactiveTextColor());
				g.drawString(more, x, baseline);
				return;
			}
			g.setColor(leafTint(entries.get(i).getValue()));
			g.fillRect(x, swatchY, swatch, swatch);
			g.setColor(UIUtil.getInactiveTextColor());
			g.drawString(text, x + swatch + pad, baseline);
			x += width;
		}
	}

	/**
	 * Dims the treemap and centers the message over it, in a box of the background color; its lines are wrapped at
	 * words to the treemap's width.
	 */
	private void paintOverlay(Graphics2D g, Overlay message, Icon spinner) {
		if (message.blocksInput()) {
			g.setColor(ColorUtil.withAlpha(UIUtil.getTreeBackground(), 0.6));
			g.fillRect(0, 0, getWidth(), getHeight());
		}
		g.setFont(UIUtil.getLabelFont());
		FontMetrics metrics = g.getFontMetrics();
		int pad = JBUI.scale(10);
		int iconWidth = message.busy() ? spinner.getIconWidth() + JBUI.scale(6) : 0;
		int maxText = Math.max(JBUI.scale(40), getWidth() - 2 * pad - iconWidth - JBUI.scale(8));
		List<String> lines = new ArrayList<>();
		for (String paragraph : message.text().split("\n")) {
			lines.addAll(TreemapLayout.wrap(paragraph, metrics, maxText));
		}
		int textWidth = lines.stream().mapToInt(metrics::stringWidth).max().orElse(0);
		int width = Math.min(getWidth(), iconWidth + textWidth + 2 * pad);
		int height = Math.max(lines.size() * metrics.getHeight(), spinner.getIconHeight()) + 2 * pad;
		int x = (getWidth() - width) / 2;
		int y = (getHeight() - height) / 2;
		g.setColor(UIUtil.getTreeBackground());
		g.fillRoundRect(x, y, width, height, pad, pad);
		g.setColor(UIUtil.getBoundsColor());
		g.drawRoundRect(x, y, width - 1, height - 1, pad, pad);
		if (message.busy()) {
			spinner.paintIcon(this, g, x + pad, y + (height - spinner.getIconHeight()) / 2);
		}
		g.setColor(UIUtil.getLabelForeground());
		int lineY = y + pad + metrics.getAscent();
		for (String line : lines) {
			g.drawString(line, x + pad + iconWidth + (textWidth - metrics.stringWidth(line)) / 2, lineY);
			lineY += metrics.getHeight();
		}
	}

	/** No clicks, wheel or drags while an {@link Overlay} takes the input. */
	@Override
	protected void processMouseEvent(MouseEvent e) {
		if (!blocked()) {
			super.processMouseEvent(e);
		}
	}

	@Override
	protected void processMouseMotionEvent(MouseEvent e) {
		if (!blocked()) {
			super.processMouseMotionEvent(e);
		}
	}

	@Override
	protected void processMouseWheelEvent(MouseWheelEvent e) {
		if (!blocked()) {
			super.processMouseWheelEvent(e);
		}
	}

	/** No key bindings or key listeners while an {@link Overlay} takes the input. */
	@Override
	protected boolean processKeyBinding(KeyStroke ks, KeyEvent e, int condition, boolean pressed) {
		return !blocked() && super.processKeyBinding(ks, e, condition, pressed);
	}

	@Override
	protected void processKeyEvent(KeyEvent e) {
		if (!blocked()) {
			super.processKeyEvent(e);
		}
	}

	/**
	 * The label's color on a box of {@code color}: the color itself on a light theme, lightened halfway to white on a
	 * dark one, where the box is a dark blend of the same hue and the full color on it read at about 2.5:1 (red) to 3:1.
	 */
	private static Color labelColor(Color color) {
		return JBColor.isBright() ? color : ColorUtil.mix(color, Color.WHITE, 0.5);
	}

	/** A leaf box's fill of {@code color}, which the legend shows too. */
	private static Color leafTint(Color color) {
		return ColorUtil.mix(UIUtil.getTreeBackground(), color, 0.35);
	}

	/** Whether an {@link Overlay} that takes the input shows. */
	private boolean blocked() {
		Overlay message = overlay.get();
		return message != null && message.blocksInput();
	}

	/** Diagonal lines over a box whose file changed after the coverage run, as its numbers may be off. */
	private static void paintHatch(Graphics2D g, Rectangle r) {
		Shape clip = g.getClip();
		g.clipRect(r.x, r.y, r.width, r.height);
		g.setColor(ColorUtil.withAlpha(UIUtil.getLabelForeground(), 0.25));
		int step = JBUI.scale(6);
		for (int x = r.x - r.height; x < r.x + r.width; x += step) {
			g.drawLine(x, r.y + r.height, x + r.height, r.y);
		}
		g.setClip(clip);
	}

	/**
	 * {@code left}, a bar of {@code scale} from -1 to +1, {@code right}, then {@code note} when not null; the bar shows the
	 * boxes' leaf tint of the scale, or the scale itself when {@code exact}, as the boxes do.
	 */
	private void paintScaleLegend(Graphics2D g, int y, int pad, String left, String right, @Nullable String note,
								  DoubleFunction<Color> scale, boolean exact) {
		FontMetrics metrics = g.getFontMetrics();
		int barWidth = JBUI.scale(120);
		int total = metrics.stringWidth(left) + barWidth + metrics.stringWidth(right) + 4 * pad
				+ (note == null ? 0 : metrics.stringWidth(note) + 3 * pad);
		int x = Math.max(pad, (getWidth() - total) / 2);
		int baseline = y + pad + metrics.getAscent();
		g.setColor(UIUtil.getInactiveTextColor());
		g.drawString(left, x, baseline);
		x += metrics.stringWidth(left) + 2 * pad;
		int barY = y + pad + (metrics.getHeight() - JBUI.scale(8)) / 2;
		for (int i = 0; i < barWidth; i++) {
			Color step = scale.apply(2.0 * i / (barWidth - 1) - 1);
			g.setColor(exact ? step : leafTint(step));
			g.fillRect(x + i, barY, 1, JBUI.scale(8));
		}
		x += barWidth + 2 * pad;
		g.setColor(UIUtil.getInactiveTextColor());
		g.drawString(right, x, baseline);
		if (note != null) {
			g.drawString(note, x + metrics.stringWidth(right) + 3 * pad, baseline);
		}
	}

	/** {@link TreemapLayout#weight} from this paint's cache, so each subtree is summed once. */
	private double cachedWeight(DefaultMutableTreeNode node) {
		return TreemapLayout.weight(node, weights);
	}

	/** The status's color; red rather than the platform's gray for deleted, which reads as unchanged. */
	private static Color statusColor(@Nullable FileStatus status) {
		Color color = StatusColors.of(status);
		return color == null ? UIUtil.getLabelForeground() : color;
	}

	private static FileStatus status(Object item) {
		return switch (item) {
			case FileDiff file -> file.status();
			case MemberDiff member -> member.status();
			case null, default -> null;
		};
	}

}
