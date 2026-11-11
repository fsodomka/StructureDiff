package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** Geometry of the {@link TreemapPanel}: node weights, the squarified layout, arrow-key neighbors and zoom paths. */
final class TreemapLayout {
	static final int LEFT = 0;
	static final int RIGHT = 1;
	static final int UP = 2;
	static final int DOWN = 3;

	private TreemapLayout() {
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

	/**
	 * The square root of the node's changed lines, at least the sum of its children's; 0 for a vanished node, which
	 * shows only in the tree.
	 */
	static double weight(DefaultMutableTreeNode node) {
		return weight(node, new IdentityHashMap<>());
	}

	static double weight(DefaultMutableTreeNode node, Map<DefaultMutableTreeNode, Double> cache) {
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

	/**
	 * The file types under {@code root} by changed lines, most first, then by name; members are not visited, as their
	 * file counts their lines.
	 */
	static List<String> fileTypesBySize(DefaultMutableTreeNode root) {
		Map<String, Integer> sizes = new HashMap<>();
		addFileTypeSizes(root, sizes);
		return sizes.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.map(Map.Entry::getKey).toList();
	}

	private static void addFileTypeSizes(DefaultMutableTreeNode node, Map<String, Integer> sizes) {
		if (node.getUserObject() instanceof FileDiff file) {
			sizes.merge(fileTypeName(file), file.linesAdded() + file.linesRemoved(), Integer::sum);
			return;
		}
		for (int i = 0; i < node.getChildCount(); i++) {
			addFileTypeSizes((DefaultMutableTreeNode) node.getChildAt(i), sizes);
		}
	}

	static String fileTypeName(FileDiff file) {
		return file.fileType().getDisplayName();
	}

	/** {@code text} broken at spaces into lines no wider than {@code width}; a word wider than that gets a line of its own. */
	static List<String> wrap(String text, FontMetrics metrics, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && metrics.stringWidth(candidate) > width) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else {
				line = new StringBuilder(candidate);
			}
		}
		lines.add(line.toString());
		return lines;
	}

	/** {@code rect} scaled by {@code scale} around its center. */
	static Rectangle2D scaled(Rectangle2D rect, double scale) {
		double width = rect.getWidth() * scale;
		double height = rect.getHeight() * scale;
		return new Rectangle2D.Double(rect.getCenterX() - width / 2, rect.getCenterY() - height / 2, width, height);
	}

	static int[] lines(Object item) {
		return switch (item) {
			case FileDiff file -> new int[]{file.linesAdded(), file.linesRemoved()};
			case MemberDiff member -> new int[]{member.linesAdded(), member.linesRemoved()};
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
