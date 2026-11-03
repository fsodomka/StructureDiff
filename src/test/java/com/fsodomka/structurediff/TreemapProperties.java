package com.fsodomka.structurediff;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.Test;

import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.stream.IntStream;

import static com.fsodomka.structurediff.PropertyChecks.forAll;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Properties of {@link TreemapLayout#layout} and of arrow-key moves between its boxes with {@link TreemapLayout#neighbor},
 * checked with jetCheck. Weights mix small and large values, so rows of similar and very different sizes both occur.
 */
public class TreemapProperties {
	private static final int[] DIRECTIONS = {TreemapLayout.LEFT, TreemapLayout.RIGHT, TreemapLayout.UP, TreemapLayout.DOWN};

	private static final Generator<Integer> POSITIVE_WEIGHT = Generator.anyOf(Generator.integers(1, 10), Generator.integers(1, 100_000));
	private static final Generator<Rectangle2D> BOUNDS = Generator.from(env -> new Rectangle2D.Double(
			env.generate(Generator.integers(-100, 100)), env.generate(Generator.integers(-100, 100)),
			env.generate(Generator.integers(1, 2000)), env.generate(Generator.integers(1, 2000))));

	private record Layout(double[] weights, Rectangle2D bounds) {
		@Override
		public String toString() {
			return Arrays.toString(weights) + " in " + bounds;
		}
	}

	private static Generator<Layout> layouts(Generator<Integer> weight) {
		return Generator.from(env -> new Layout(
				env.generate(Generator.listsOf(IntDistribution.uniform(0, 12), weight)).stream().mapToDouble(Integer::doubleValue).toArray(),
				env.generate(BOUNDS)));
	}

	@Test
	public void boxesTileTheBoundsWithAreasProportionalToTheirWeights() {
		// zero weights too, which the panel leaves out but layout accepts
		forAll(layouts(Generator.anyOf(Generator.constant(0), POSITIVE_WEIGHT)), l -> {
			Rectangle2D[] rects = TreemapLayout.layout(l.weights, l.bounds);
			assertEquals(l.weights.length, rects.length);
			double boundsArea = l.bounds.getWidth() * l.bounds.getHeight();
			double total = Arrays.stream(l.weights).sum();
			double tolerance = 1e-6 * boundsArea;
			double area = 0;
			for (int i = 0; i < rects.length; i++) {
				Rectangle2D r = rects[i];
				assertTrue(r.toString(), Double.isFinite(r.getX()) && Double.isFinite(r.getY())
						&& r.getWidth() >= 0 && r.getHeight() >= 0 && Double.isFinite(r.getWidth()) && Double.isFinite(r.getHeight()));
				double expected = total > 0 ? l.weights[i] / total * boundsArea : 0;
				assertEquals("area of " + i, expected, r.getWidth() * r.getHeight(), tolerance);
				double eps = 1e-6 * Math.max(l.bounds.getWidth(), l.bounds.getHeight());
				assertTrue("inside: " + r, r.getMinX() >= l.bounds.getMinX() - eps && r.getMinY() >= l.bounds.getMinY() - eps
						&& r.getMaxX() <= l.bounds.getMaxX() + eps && r.getMaxY() <= l.bounds.getMaxY() + eps);
				for (int j = 0; j < i; j++) {
					Rectangle2D overlap = r.createIntersection(rects[j]);
					assertTrue("overlap of " + j + " and " + i, overlap.isEmpty() || overlap.getWidth() * overlap.getHeight() < tolerance);
				}
				area += r.getWidth() * r.getHeight();
			}
			assertEquals(total > 0 ? boundsArea : 0, area, tolerance);
		});
	}

	@Test
	public void aMoveGoesTheWayOfTheArrow() {
		forAll(layouts(POSITIVE_WEIGHT), l -> {
			List<Rectangle2D> boxes = paintedBoxes(l);
			for (Rectangle2D from : boxes) {
				for (int direction : DIRECTIONS) {
					int to = TreemapLayout.neighbor(from, boxes, direction);
					if (to < 0) {
						continue;
					}
					Rectangle2D target = boxes.get(to);
					double dx = target.getCenterX() - from.getCenterX();
					double dy = target.getCenterY() - from.getCenterY();
					double ahead = switch (direction) {
						case TreemapLayout.LEFT -> -dx;
						case TreemapLayout.RIGHT -> dx;
						case TreemapLayout.UP -> -dy;
						default -> dy;
					};
					assertTrue(from + " -> " + target + " in direction " + direction, ahead > 0);
				}
			}
		});
	}

	@Test
	public void arrowKeysReachEveryBoxFromEveryBox() {
		forAll(layouts(POSITIVE_WEIGHT), l -> {
			List<Rectangle2D> boxes = paintedBoxes(l);
			for (int start = 0; start < boxes.size(); start++) {
				boolean[] reached = new boolean[boxes.size()];
				reached[start] = true;
				Deque<Integer> queue = new ArrayDeque<>(List.of(start));
				while (!queue.isEmpty()) {
					Rectangle2D from = boxes.get(queue.poll());
					for (int direction : DIRECTIONS) {
						int to = TreemapLayout.neighbor(from, boxes, direction);
						if (to >= 0 && !reached[to]) {
							reached[to] = true;
							queue.add(to);
						}
					}
				}
				int first = start;
				int[] missed = IntStream.range(0, boxes.size()).filter(i -> !reached[i]).toArray();
				assertEquals("unreachable from " + boxes.get(first) + ": " + Arrays.stream(missed).mapToObj(boxes::get).toList(),
						0, missed.length);
			}
		});
	}

	/** The boxes the panel paints and moves between: those at least a pixel wide and high. */
	private static List<Rectangle2D> paintedBoxes(Layout l) {
		return Arrays.stream(TreemapLayout.layout(l.weights, l.bounds)).filter(r -> r.getWidth() >= 1 && r.getHeight() >= 1).toList();
	}
}
