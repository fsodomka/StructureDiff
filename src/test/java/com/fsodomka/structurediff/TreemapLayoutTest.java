package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.MemberDiff;
import com.intellij.openapi.vcs.FileStatus;
import org.junit.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.*;

public class TreemapLayoutTest {
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
		assertSame(folder, TreemapLayout.resolve(root, List.of("repo", "folder"), label, TreemapLayout::weight));
		assertSame(repo, TreemapLayout.resolve(root, List.of("repo", "gone"), label, TreemapLayout::weight));
		// a member is not a container to zoom into
		assertSame(folder, TreemapLayout.resolve(root, List.of("repo", "folder", "leaf"), label, TreemapLayout::weight));
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
		assertSame(second, TreemapLayout.resolve(root, List.of("b/pom.xml"), key, TreemapLayout::weight));
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
		assertEquals("method", TreemapLayout.ellipsize("method", metrics, 60));
		assertEquals("meth…", TreemapLayout.ellipsize("method", metrics, 59));
		assertEquals("met…", TreemapLayout.ellipsize("method", metrics, 49));
		assertEquals("m…", TreemapLayout.ellipsize("method", metrics, 20));
		assertEquals("", TreemapLayout.ellipsize("method", metrics, 19));
	}

	@Test
	public void sizesGrowWithTheSquareRootOfChangedLines() {
		DefaultMutableTreeNode small = new DefaultMutableTreeNode(
				new MemberDiff("a", "a", "a", "a", null, FileStatus.MODIFIED, 0, 0, 1, 3, false, 0, List.of()));
		DefaultMutableTreeNode large = new DefaultMutableTreeNode(
				new MemberDiff("b", "b", "b", "b", null, FileStatus.MODIFIED, 0, 0, 300, 100, false, 0, List.of()));
		assertEquals(2, TreemapLayout.weight(small), 1e-9);
		assertEquals(20, TreemapLayout.weight(large), 1e-9);
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
		assertEquals(1, TreemapLayout.neighbor(topLeft, all, TreemapLayout.RIGHT));
		assertEquals(2, TreemapLayout.neighbor(topLeft, all, TreemapLayout.DOWN));
		assertEquals(4, TreemapLayout.neighbor(bottomRight, all, TreemapLayout.DOWN));
		assertEquals(1, TreemapLayout.neighbor(bottomRight, all, TreemapLayout.UP));
		assertEquals(-1, TreemapLayout.neighbor(topLeft, all, TreemapLayout.LEFT));
		assertEquals(-1, TreemapLayout.neighbor(topLeft, all, TreemapLayout.UP));
	}

	@Test
	public void grownRectangleKeepsItsCenter() {
		Rectangle2D rect = new Rectangle2D.Double(10, 20, 100, 40);
		assertEquals(new Rectangle2D.Double(35, 30, 50, 20), TreemapLayout.scaled(rect, 0.5));
		assertEquals(rect, TreemapLayout.scaled(rect, 1));
		assertTrue(TreemapLayout.scaled(rect, 0).isEmpty());
	}
}
