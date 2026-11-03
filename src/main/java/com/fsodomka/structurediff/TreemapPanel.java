package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.ImageUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

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
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.*;

/**
 * The nodes of a {@link JTree} as nested rectangles sized by their changed lines and colored by their status. Shares the
 * tree's model and selection: a click selects the node in the tree, so the tree's actions apply to it. A click on a
 * container's header zooms into it; a breadcrumb leads back.
 */
final class TreemapPanel extends JComponent {
	private static final Color REMOVED = new JBColor(new Color(0xC7_22_2A), new Color(0xE0_5A_5A));

	private final JTree tree;
	private final Function<DefaultMutableTreeNode, String> label;
	/** Identifies a node among its siblings, stable across refreshes; labels are not unique, as overloads show. */
	private final Function<DefaultMutableTreeNode, String> key;
	private final Function<DefaultMutableTreeNode, List<MemberFinding>> findings;
	private final Function<DefaultMutableTreeNode, List<MemberDiagnostic>> diagnostics;
	private final Predicate<DefaultMutableTreeNode> notAnalysed;
	private final Predicate<DefaultMutableTreeNode> testFile;
	private final Function<DefaultMutableTreeNode, ChangeFlashes.Flash> flash;
	private final Predicate<DefaultMutableTreeNode> related;
	private final BooleanSupplier loading;
	private final Supplier<String> emptyText;
	/** Painted rectangles, parents before their children; the last one containing a point is the deepest node there. */
	private final List<Hit> hits = new ArrayList<>();
	/** Where a click zooms in: container headers, and whole containers too small to show their children. */
	private final List<Hit> zoomHits = new ArrayList<>();
	/** The painted severity icons; a click opens the node's findings. */
	private final List<Hit> iconHits = new ArrayList<>();
	/** The painted diagnostic icons; a click opens the node's most severe diagnostic. */
	private final List<Hit> diagnosticHits = new ArrayList<>();
	/** The breadcrumb segments; a null node is the root. */
	private final List<Hit> crumbHits = new ArrayList<>();
	/** Weights of the nodes painted, computed once per paint. */
	private final Map<DefaultMutableTreeNode, Double> weights = new IdentityHashMap<>();
	/** {@link #opaqueBounds} of the test mark, null when it paints nothing; computed on first use per look and feel. */
	private Rectangle testMarkGlyph;
	private boolean testMarkGlyphKnown;
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
	 * @param diagnostics the IDE's diagnostics shown on a node, most severe first
	 * @param notAnalysed whether a node is a changed file the IDE has not analysed
	 * @param onDiagnostic click on a diagnostic icon, like the tree's
	 * @param testFile  whether a node is a file in test sources, marked like the tree's icon
	 * @param flash     the node's running flash, or null; the caller repaints while flashes run
	 * @param related   whether a node uses or is used by the selected one, highlighted like the tree's row; the caller
	 *                  repaints when it changes
	 * @param loading   whether a refresh is in progress; the caller repaints when it changes
	 * @param emptyText shown when there is nothing to show, like the tree's
	 */
	TreemapPanel(JTree tree, Function<DefaultMutableTreeNode, String> label, Function<DefaultMutableTreeNode, String> key,
			Runnable onOpen, Runnable onDiff,
			Function<DefaultMutableTreeNode, List<MemberFinding>> findings, Consumer<List<MemberFinding>> onFindings,
			Function<DefaultMutableTreeNode, List<MemberDiagnostic>> diagnostics, Predicate<DefaultMutableTreeNode> notAnalysed,
			Consumer<DefaultMutableTreeNode> onDiagnostic, Predicate<DefaultMutableTreeNode> testFile,
			Function<DefaultMutableTreeNode, ChangeFlashes.Flash> flash, Predicate<DefaultMutableTreeNode> related,
			BooleanSupplier loading, Supplier<String> emptyText) {
		this.tree = tree;
		this.related = related;
		this.testFile = testFile;
		this.flash = flash;
		this.label = label;
		this.key = key;
		this.findings = findings;
		this.diagnostics = diagnostics;
		this.notAnalysed = notAnalysed;
		this.loading = loading;
		this.emptyText = emptyText;
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
					// a right click on a selected node keeps the selection, as in the tree
					if (!SwingUtilities.isRightMouseButton(e) || !tree.isPathSelected(path)) {
						tree.setSelectionPath(path);
					}
				}
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				boolean wasZoomed = zoomed;
				zoomed = false;
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
					// the icon sits in the header, so it comes before zooming
					DefaultMutableTreeNode withIcon = hitAt(iconHits, e.getPoint());
					if (withIcon != null) {
						onFindings.accept(findings.apply(withIcon));
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

			@Override
			public void mouseMoved(MouseEvent e) {
				// a hand over a severity icon, as in the tree
				boolean onIcon = hitAt(iconHits, e.getPoint()) != null || hitAt(diagnosticHits, e.getPoint()) != null;
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
		return MemberSignals.withSignals(text,
				findings.apply(node), diagnostics.apply(node), notAnalysed.test(node));
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
			crumbHits.clear();
			weights.clear();
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
			paintChildren(g2, root, bounds);
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
		// red rather than the tree's gray, which a box can't strike out and which reads as unchanged
		Color color = status == FileStatus.DELETED ? REMOVED
				: status == null || status.getColor() == null ? UIUtil.getLabelForeground() : status.getColor();
		FontMetrics metrics = g.getFontMetrics();
		int pad = JBUI.scale(3);
		int header = metrics.getHeight() + pad;
		boolean nested = !node.isLeaf() && r.width > 4 * pad && r.height > header + 3 * pad;
		g.setColor(ColorUtil.withAlpha(color, node.isLeaf() ? 0.35 : 0.12));
		g.fillRect(r.x, r.y, r.width, r.height);
		// over the status tint, as the tree's row background: blended, the blue of a modified box turns the amber gray. The
		// border and label keep the status color. Only the header of a container showing its children, like the flash
		// below, so a class is not taken for related through one of its members
		if (!tree.isPathSelected(new TreePath(node.getPath())) && related.test(node)) {
			g.setColor(StructureDiffPanel.RELATED);
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
		boolean test = testFile.test(node);
		// the test mark before the label, as on the tree's icon, when it fits beside the corner icons; it is an overlay
		// whose glyph fills only a corner of the icon, so the glyph, not the icon, is placed and spaced like a character
		Icon mark = AllIcons.Nodes.JunitTestMark;
		if (test && !testMarkGlyphKnown) {
			testMarkGlyph = opaqueBounds(mark);
			testMarkGlyphKnown = true;
		}
		Rectangle glyph = testMarkGlyph;
		int markWidth = 0;
		if (test && glyph != null && r.width >= iconWidth + glyph.width + 3 * pad && r.height >= header) {
			mark.paintIcon(this, g, r.x + pad - glyph.x, r.y + (header - glyph.height) / 2 - glyph.y);
			markWidth = glyph.width + pad;
		}
		int labelWidth = r.width - 2 * pad - iconWidth - markWidth;
		String text = r.height >= metrics.getHeight() ? TreemapLayout.ellipsize(label.apply(node), metrics, labelWidth) : "";
		if (!text.isEmpty()) {
			g.setColor(color);
			// a container's label is its header; a leaf's is centered in the box
			int x = markWidth + (!node.isLeaf() ? r.x + pad : r.x + pad + (labelWidth - metrics.stringWidth(text)) / 2);
			int y = !node.isLeaf() ? r.y + pad / 2 + metrics.getAscent()
					: r.y + (r.height - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(text, x, y);
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
	}

	@Override
	public void updateUI() {
		super.updateUI();
		// the icon may change with the theme or the scale
		testMarkGlyphKnown = false;
	}

	/** The bounds of the painted pixels of {@code icon}, relative to its origin; null when it paints nothing. */
	private Rectangle opaqueBounds(Icon icon) {
		// a plain image in user space, which is what the bounds are in
		BufferedImage image = ImageUtil.createImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			icon.paintIcon(this, g, 0, 0);
		}
		finally {
			g.dispose();
		}
		Rectangle bounds = null;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) >>> 24) != 0) {
					Rectangle pixel = new Rectangle(x, y, 1, 1);
					bounds = bounds == null ? pixel : bounds.union(pixel);
				}
			}
		}
		return bounds;
	}

	/** {@link TreemapLayout#weight} from this paint's cache, so each subtree is summed once. */
	private double cachedWeight(DefaultMutableTreeNode node) {
		return TreemapLayout.weight(node, weights);
	}

	private static FileStatus status(Object item) {
		return switch (item) {
			case FileDiff file -> file.status();
			case MemberDiff member -> member.status();
			case null, default -> null;
		};
	}

}
