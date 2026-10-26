package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import org.junit.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.FontMetrics;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TreemapLayoutTest {
	@Test
	public void areasAreProportionalAndFillTheBounds() {
		double[] weights = {1, 6, 2, 3, 2, 6, 4};
		Rectangle2D bounds = new Rectangle2D.Double(10, 20, 600, 400);
		Rectangle2D[] rects = TreemapPanel.layout(weights, bounds);
		double area = 0;
		for (int i = 0; i < weights.length; i++) {
			Rectangle2D r = rects[i];
			assertEquals(weights[i] / 24 * 600 * 400, r.getWidth() * r.getHeight(), 1e-6);
			assertTrue(bounds.contains(r.getX() + 1e-9, r.getY() + 1e-9, r.getWidth() - 1e-6, r.getHeight() - 1e-6));
			for (int j = 0; j < i; j++) {
				Rectangle2D overlap = r.createIntersection(rects[j]);
				assertTrue(overlap.isEmpty() || overlap.getWidth() * overlap.getHeight() < 1e-6);
			}
			area += r.getWidth() * r.getHeight();
		}
		assertEquals(600 * 400, area, 1e-6);
	}

	@Test
	public void resolveFallsBackToTheDeepestContainerStillThere() {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
		DefaultMutableTreeNode repo = new DefaultMutableTreeNode("repo");
		DefaultMutableTreeNode folder = new DefaultMutableTreeNode("folder");
		// a member gives the containers above it a weight, so they are shown
		DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(
				new MemberDiff("leaf", "leaf", "leaf", "leaf", null, FileStatus.MODIFIED, 0, 0, 1, 1, true, 0, List.of()));
		root.add(repo);
		repo.add(folder);
		folder.add(leaf);
		Function<DefaultMutableTreeNode, String> label =
				node -> node.getUserObject() instanceof String text ? text : "leaf";
		assertSame(folder, TreemapPanel.resolve(root, List.of("repo", "folder"), label, TreemapPanel::weight));
		assertSame(repo, TreemapPanel.resolve(root, List.of("repo", "gone"), label, TreemapPanel::weight));
		// a member is not a container to zoom into
		assertSame(folder, TreemapPanel.resolve(root, List.of("repo", "folder", "leaf"), label, TreemapPanel::weight));
	}

	@Test
	public void resolveTellsSiblingsWithTheSameLabelApartByKey() {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
		DefaultMutableTreeNode first = new DefaultMutableTreeNode(member("a/pom.xml", true));
		DefaultMutableTreeNode second = new DefaultMutableTreeNode(member("b/pom.xml", true));
		first.add(new DefaultMutableTreeNode(member("a/pom.xml#x", false)));
		second.add(new DefaultMutableTreeNode(member("b/pom.xml#x", false)));
		root.add(first);
		root.add(second);
		Function<DefaultMutableTreeNode, String> key = node -> node.getUserObject() instanceof MemberDiff m ? m.key() : "";
		assertSame(second, TreemapPanel.resolve(root, List.of("b/pom.xml"), key, TreemapPanel::weight));
	}

	/** A member labelled "pom.xml" whatever its key, as siblings of the same name are. */
	private static MemberDiff member(String key, boolean container) {
		return new MemberDiff(key, key, "pom.xml", "pom.xml", null, FileStatus.MODIFIED, 0, 0, 1, 1, container, 0, List.of());
	}

	@Test
	public void ellipsizeCutsToTheWidth() {
		// every character, the ellipsis too, 10 wide
		FontMetrics metrics = new FontMetrics(null) {
			@Override
			public int stringWidth(String text) {
				return 10 * text.length();
			}
		};
		assertEquals("method", TreemapPanel.ellipsize("method", metrics, 60));
		assertEquals("meth…", TreemapPanel.ellipsize("method", metrics, 59));
		assertEquals("met…", TreemapPanel.ellipsize("method", metrics, 49));
		assertEquals("m…", TreemapPanel.ellipsize("method", metrics, 20));
		assertEquals("", TreemapPanel.ellipsize("method", metrics, 19));
	}

	@Test
	public void sizesGrowWithTheSquareRootOfChangedLines() {
		DefaultMutableTreeNode small = new DefaultMutableTreeNode(
				new MemberDiff("a", "a", "a", "a", null, FileStatus.MODIFIED, 0, 0, 1, 3, false, 0, List.of()));
		DefaultMutableTreeNode large = new DefaultMutableTreeNode(
				new MemberDiff("b", "b", "b", "b", null, FileStatus.MODIFIED, 0, 0, 300, 100, false, 0, List.of()));
		assertEquals(2, TreemapPanel.weight(small), 1e-9);
		assertEquals(20, TreemapPanel.weight(large), 1e-9);
	}

	@Test
	public void neighborIsTheNearestInLineThatWay() {
		// a 2x2 grid of 10x10 boxes, and a wide one below it
		Rectangle2D topLeft = new Rectangle2D.Double(0, 0, 10, 10);
		Rectangle2D topRight = new Rectangle2D.Double(10, 0, 10, 10);
		Rectangle2D bottomLeft = new Rectangle2D.Double(0, 10, 10, 10);
		Rectangle2D bottomRight = new Rectangle2D.Double(10, 10, 10, 10);
		Rectangle2D wide = new Rectangle2D.Double(0, 20, 20, 10);
		List<Rectangle2D> all = List.of(topLeft, topRight, bottomLeft, bottomRight, wide);
		assertEquals(1, TreemapPanel.neighbor(topLeft, all, TreemapPanel.RIGHT));
		assertEquals(2, TreemapPanel.neighbor(topLeft, all, TreemapPanel.DOWN));
		assertEquals(4, TreemapPanel.neighbor(bottomRight, all, TreemapPanel.DOWN));
		assertEquals(1, TreemapPanel.neighbor(bottomRight, all, TreemapPanel.UP));
		assertEquals(-1, TreemapPanel.neighbor(topLeft, all, TreemapPanel.LEFT));
		assertEquals(-1, TreemapPanel.neighbor(topLeft, all, TreemapPanel.UP));
	}

	@Test
	public void noWeightsGiveNoRectangles() {
		assertEquals(0, TreemapPanel.layout(new double[0], new Rectangle2D.Double(0, 0, 10, 10)).length);
	}
}
