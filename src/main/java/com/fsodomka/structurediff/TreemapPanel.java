package com.fsodomka.structurediff;

import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

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
	private final BooleanSupplier loading;
	private final Supplier<String> emptyText;
	/** Painted rectangles, parents before their children; the last one containing a point is the deepest node there. */
	private final List<Hit> hits = new ArrayList<>();
	/** Where a click zooms in: container headers, and whole containers too small to show their children. */
	private final List<Hit> zoomHits = new ArrayList<>();
	/** The painted severity icons; a click opens the node's findings. */
	private final List<Hit> iconHits = new ArrayList<>();
	/** The breadcrumb segments; a null node is the root. */
	private final List<Hit> crumbHits = new ArrayList<>();
	/** Weights of the nodes painted, computed once per paint. */
	private final Map<DefaultMutableTreeNode, Double> weights = new IdentityHashMap<>();
	/**
	 * Keys of the zoomed node's path below the root, empty when not zoomed. Keys, not nodes, because every refresh
	 * rebuilds the tree model.
	 */
	private List<String> zoom = List.of();

	static final int LEFT = 0;
	static final int RIGHT = 1;
	static final int UP = 2;
	static final int DOWN = 3;

	private record Hit(Rectangle2D rect, DefaultMutableTreeNode node) {
	}

	/**
	 * @param key      identifies a node among its siblings across refreshes
	 * @param onOpen   double-click, like the tree's
	 * @param onDiff   middle click, like the tree's
	 * @param findings the review findings shown on a node, most severe first
	 * @param onFindings click on a severity icon, like the tree's
	 * @param loading   whether a refresh is in progress; the caller repaints when it changes
	 * @param emptyText shown when there is nothing to show, like the tree's
	 */
	TreemapPanel(JTree tree, Function<DefaultMutableTreeNode, String> label, Function<DefaultMutableTreeNode, String> key,
			Runnable onOpen, Runnable onDiff,
			Function<DefaultMutableTreeNode, List<MemberFinding>> findings, Consumer<List<MemberFinding>> onFindings,
			BooleanSupplier loading, Supplier<String> emptyText) {
		this.tree = tree;
		this.label = label;
		this.key = key;
		this.findings = findings;
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
				DefaultMutableTreeNode left = resolve(root, zoom, key, TreemapPanel::weight);
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
				DefaultMutableTreeNode selected = selectedHit() == null ? null : selectedHit().node();
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
					if (weight(child) > 0 && (largest == null || weight(child) > weight(largest))) {
						largest = child;
					}
				}
				if (largest != null) {
					tree.setSelectionPath(new TreePath(largest.getPath()));
				}
			}
		});
		int[][] arrows = {{KeyEvent.VK_LEFT, LEFT}, {KeyEvent.VK_RIGHT, RIGHT}, {KeyEvent.VK_UP, UP}, {KeyEvent.VK_DOWN, DOWN}};
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
				boolean onIcon = hitAt(iconHits, e.getPoint()) != null;
				setCursor(onIcon ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	@Override
	public String getToolTipText(MouseEvent event) {
		DefaultMutableTreeNode node = nodeAt(event.getPoint());
		if (node == null) {
			return null;
		}
		int[] lines = lines(node.getUserObject());
		String text = label.apply(node);
		return StructureDiffPanel.withFindings(lines == null ? text : text + "  +" + lines[0] + " −" + lines[1],
				findings.apply(node));
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
		int next = neighbor(from.rect(), siblings.stream().map(Hit::rect).toList(), direction);
		if (next >= 0) {
			tree.setSelectionPath(new TreePath(siblings.get(next).node().getPath()));
		}
	}

	/**
	 * Index of the rectangle in {@code candidates} nearest to {@code from} in {@code direction}, by their centers, with a
	 * sideways offset counting double so the move stays in line; -1 when there is none that way.
	 */
	static int neighbor(Rectangle2D from, List<Rectangle2D> candidates, int direction) {
		int best = -1;
		double bestScore = Double.MAX_VALUE;
		for (int i = 0; i < candidates.size(); i++) {
			Rectangle2D to = candidates.get(i);
			double dx = to.getCenterX() - from.getCenterX();
			double dy = to.getCenterY() - from.getCenterY();
			double ahead = switch (direction) {
				case LEFT -> -dx;
				case RIGHT -> dx;
				case UP -> -dy;
				default -> dy;
			};
			double aside = direction == LEFT || direction == RIGHT ? Math.abs(dy) : Math.abs(dx);
			if (ahead <= 0) {
				continue;
			}
			double score = ahead + 2 * aside;
			if (score < bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
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

	/**
	 * The container at {@code keys} below {@code root}, or its deepest ancestor still there, for when a refresh
	 * removed the zoomed node.
	 */
	static DefaultMutableTreeNode resolve(DefaultMutableTreeNode root, List<String> keys,
			Function<DefaultMutableTreeNode, String> key, ToDoubleFunction<DefaultMutableTreeNode> weight) {
		DefaultMutableTreeNode node = root;
		for (String wanted : keys) {
			DefaultMutableTreeNode next = null;
			for (int i = 0; i < node.getChildCount() && next == null; i++) {
				DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
				if (wanted.equals(key.apply(child)) && weight.applyAsDouble(child) > 0) {
					next = child;
				}
			}
			if (next == null || next.isLeaf()) {
				break;
			}
			node = next;
		}
		return node;
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
			crumbHits.clear();
			weights.clear();
			DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
			Rectangle2D bounds = new Rectangle2D.Double(0, 0, getWidth(), getHeight());
			if (root != null && !zoom.isEmpty()) {
				DefaultMutableTreeNode zoomed = resolve(root, zoom, key, this::cachedWeight);
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
		Rectangle2D[] rects = layout(children.stream().mapToDouble(this::cachedWeight).toArray(), bounds);
		for (int i = 0; i < rects.length; i++) {
			paintNode(g, children.get(i), rects[i]);
		}
	}

	private void paintNode(Graphics2D g, DefaultMutableTreeNode node, Rectangle2D rect) {
		if (rect.getWidth() < 1 || rect.getHeight() < 1) {
			return;
		}
		hits.add(new Hit(rect, node));
		Rectangle r = rect.getBounds();
		FileStatus status = status(node.getUserObject());
		// red rather than the tree's gray, which a box can't strike out and which reads as unchanged
		Color color = status == FileStatus.DELETED ? REMOVED
				: status == null || status.getColor() == null ? UIUtil.getLabelForeground() : status.getColor();
		g.setColor(ColorUtil.withAlpha(color, node.isLeaf() ? 0.35 : 0.12));
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setColor(ColorUtil.withAlpha(color, 0.6));
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);

		FontMetrics metrics = g.getFontMetrics();
		int pad = JBUI.scale(3);
		int header = metrics.getHeight() + pad;
		boolean nested = !node.isLeaf() && r.width > 4 * pad && r.height > header + 3 * pad;
		// the icon of the most severe finding, in the top right corner as in the tree's row
		List<MemberFinding> found = findings.apply(node);
		Icon severity = found.isEmpty() ? null : found.getFirst().icon();
		int iconWidth = 0;
		if (severity != null && r.width >= severity.getIconWidth() + 2 * pad && r.height >= severity.getIconHeight()) {
			iconWidth = severity.getIconWidth() + pad;
			int iconX = r.x + r.width - iconWidth;
			int iconY = r.y + Math.max(0, (header - severity.getIconHeight()) / 2);
			severity.paintIcon(this, g, iconX, iconY);
			iconHits.add(new Hit(new Rectangle2D.Double(iconX, iconY, severity.getIconWidth(), severity.getIconHeight()), node));
		}
		int labelWidth = r.width - 2 * pad - iconWidth;
		String text = r.height >= metrics.getHeight() ? ellipsize(label.apply(node), metrics, labelWidth) : "";
		if (!text.isEmpty()) {
			g.setColor(color);
			// a container's label is its header; a leaf's is centered in the box
			int x = !node.isLeaf() ? r.x + pad : r.x + pad + (labelWidth - metrics.stringWidth(text)) / 2;
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
			g.setColor(JBUI.CurrentTheme.Focus.focusColor());
			g.setStroke(new BasicStroke(JBUI.scale(2)));
			g.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
			g.setStroke(new BasicStroke());
		}
	}

	/** {@code text} cut to fit {@code width}, ending in "…"; empty when not even the first character and "…" fit. */
	static String ellipsize(String text, FontMetrics metrics, int width) {
		if (metrics.stringWidth(text) <= width) {
			return text;
		}
		// the longest prefix that fits with the ellipsis
		int low = 0;
		int high = text.length();
		while (low < high) {
			int mid = (low + high + 1) / 2;
			if (metrics.stringWidth(text.substring(0, mid) + "…") <= width) {
				low = mid;
			}
			else {
				high = mid - 1;
			}
		}
		return low == 0 ? "" : text.substring(0, low) + "…";
	}

	/** {@link #weight} from this paint's cache, so each subtree is summed once. */
	private double cachedWeight(DefaultMutableTreeNode node) {
		return weight(node, weights);
	}

	/**
	 * The square root of the node's changed lines, at least the sum of its children's; 0 for a vanished node, which
	 * shows only in the tree.
	 */
	static double weight(DefaultMutableTreeNode node) {
		return weight(node, new IdentityHashMap<>());
	}

	private static double weight(DefaultMutableTreeNode node, Map<DefaultMutableTreeNode, Double> cache) {
		Double cached = cache.get(node);
		if (cached != null) {
			return cached;
		}
		double weight = uncachedWeight(node, cache);
		cache.put(node, weight);
		return weight;
	}

	private static double uncachedWeight(DefaultMutableTreeNode node, Map<DefaultMutableTreeNode, Double> cache) {
		double children = 0;
		for (int i = 0; i < node.getChildCount(); i++) {
			children += weight((DefaultMutableTreeNode) node.getChildAt(i), cache);
		}
		int[] lines = lines(node.getUserObject());
		if (lines == null) {
			// the root, a repository, or a vanished node
			return children;
		}
		// the square root, so a 1-line change next to a 500-line one is still a box, not a sliver
		return Math.max(children, Math.max(1, Math.sqrt(lines[0] + lines[1])));
	}

	private static int[] lines(Object item) {
		return switch (item) {
			case FileDiff file -> new int[]{file.linesAdded(), file.linesRemoved()};
			case MemberDiff member -> new int[]{member.linesAdded(), member.linesRemoved()};
			case null, default -> null;
		};
	}

	private static FileStatus status(Object item) {
		return switch (item) {
			case FileDiff file -> file.status();
			case MemberDiff member -> member.status();
			case null, default -> null;
		};
	}

	/**
	 * Squarified treemap layout (Bruls, Huizing, van Wijk): rectangles filling {@code bounds} with areas proportional to
	 * {@code weights} and aspect ratios close to 1. Returned in the order of {@code weights}.
	 */
	static Rectangle2D[] layout(double[] weights, Rectangle2D bounds) {
		int n = weights.length;
		Rectangle2D[] result = new Rectangle2D[n];
		double total = Arrays.stream(weights).sum();
		if (n == 0 || total <= 0) {
			Arrays.setAll(result, i -> new Rectangle2D.Double(bounds.getX(), bounds.getY(), 0, 0));
			return result;
		}
		// largest first, so the rows are filled with similar sizes
		Integer[] order = new Integer[n];
		Arrays.setAll(order, i -> i);
		Arrays.sort(order, Comparator.comparingDouble((Integer i) -> weights[i]).reversed());
		double scale = bounds.getWidth() * bounds.getHeight() / total;
		double[] areas = new double[n];
		for (int i = 0; i < n; i++) {
			areas[i] = weights[order[i]] * scale;
		}
		double x = bounds.getX();
		double y = bounds.getY();
		double w = bounds.getWidth();
		double h = bounds.getHeight();
		int start = 0;
		while (start < n) {
			double side = Math.min(w, h);
			int end = start + 1;
			double rowArea = areas[start];
			double worst = worst(areas, start, end, rowArea, side);
			while (end < n) {
				double next = worst(areas, start, end + 1, rowArea + areas[end], side);
				if (next > worst) {
					break;
				}
				rowArea += areas[end];
				worst = next;
				end++;
			}
			double thickness = side > 0 ? rowArea / side : 0;
			double offset = 0;
			for (int i = start; i < end; i++) {
				double length = thickness > 0 ? areas[i] / thickness : 0;
				// the row runs along the shorter side
				result[order[i]] = w >= h
						? new Rectangle2D.Double(x, y + offset, thickness, length)
						: new Rectangle2D.Double(x + offset, y, length, thickness);
				offset += length;
			}
			if (w >= h) {
				x += thickness;
				w -= thickness;
			}
			else {
				y += thickness;
				h -= thickness;
			}
			start = end;
		}
		return result;
	}

	/** The worst aspect ratio in the row of {@code areas[start, end)} laid along {@code side}. */
	private static double worst(double[] areas, int start, int end, double rowArea, double side) {
		double worst = 0;
		for (int i = start; i < end; i++) {
			double a = areas[i];
			double s2 = side * side;
			double r2 = rowArea * rowArea;
			worst = Math.max(worst, Math.max(s2 * a / r2, r2 / (s2 * a)));
		}
		return worst;
	}
}
