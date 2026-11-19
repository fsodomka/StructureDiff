package com.fsodomka.structurediff;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.JBColor;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.ImageUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The colors of changed code, the same in the tree, the treemap and the editor gutter: added green, modified blue,
 * removed red. Red rather than the platform's gray for deleted, which reads as unchanged; a box cannot strike out a
 * removed member and neither can a gutter icon. Grey when the kind of change is not known.
 */
public final class StatusColors {
	// resolved on each use: a value read once keeps the theme of its first use, dark blue on a dark tree after a switch
	public static final Color ADDED = JBColor.lazy(() -> FileStatus.ADDED.getColor());
	public static final Color MODIFIED = JBColor.lazy(() -> FileStatus.MODIFIED.getColor());
	public static final JBColor REMOVED = new JBColor(new Color(0xC7_22_2A), new Color(0xE0_5A_5A));
	/**
	 * Removed for text, as the tree's struck-out names: the same hue, muted, as the full red that fills a treemap box
	 * reads as an alarm in a line of text.
	 */
	public static final JBColor REMOVED_TEXT = new JBColor(new Color(0xA3_3A_3A), new Color(0xD0_80_80));
	/**
	 * Removed for the editor's reason pencils and bars: a gutter mark is a few pixels wide, and the box red sinks into a
	 * dark theme's grey gutter.
	 */
	public static final JBColor REMOVED_MARK = new JBColor(new Color(0xE0_10_10), new Color(0xFF_45_45));
	/** For code whose kind the snapshot does not tell, e.g. a reason outside its changed lines. */
	public static final JBColor UNKNOWN = new JBColor(new Color(0x6C_70_7E), new Color(0xA9_B0_BA));
	/**
	 * For the pencils and bars of marks a skill wrote (e.g. a code walkthrough), whatever the kind of change: purple, apart
	 * from the kinds' colors and from the pink GitLab paints changed lines with.
	 */
	public static final JBColor SKILL = new JBColor(new Color(0x6B_21_A8), new Color(0xB0_7C_F0));
	/**
	 * A skill's color for tinting text (a clicked pencil's ranges): the light theme's pencil purple tints too dark under
	 * code, so a lighter one there.
	 */
	public static final JBColor SKILL_TINT = new JBColor(new Color(0xB0_7C_F0), new Color(0xB0_7C_F0));

	/** By RGB: a theme switch resolves the lazy colors to another one. */
	private static final Map<Integer, Icon> PENCILS = new HashMap<>();
	/** By RGB, as {@link #PENCILS}. */
	private static final Map<Integer, Icon> PENCIL_MARKS = new HashMap<>();

	/** The editor gutter's pencil, full size, in {@code color} exactly: the gutter's reason pencils and the panels' rows. EDT only. */
	public static Icon pencil(Color color) {
		return PENCILS.computeIfAbsent(color.getRGB(), key -> inColor(AllIcons.Actions.Edit, new Color(key, true)));
	}

	/**
	 * The editor gutter's pencil, smaller and in {@code color}: the reason mark the tree and the treemap draw after a
	 * label, so it points the way the gutter's does (a text pencil, ✎, points the other way). EDT only.
	 */
	public static Icon pencilMark(Color color) {
		int rgb = color.getRGB();
		return PENCIL_MARKS.computeIfAbsent(rgb,
				key -> IconUtil.scale(pencil(new Color(key, true)), null, 0.75f));
	}

	private StatusColors() {
	}

	/**
	 * {@code icon} with every pixel in {@code color} itself, keeping only its alpha: {@link IconUtil#colorize} blends the
	 * color with the icon's own dark strokes, so a mark drew darker than its color.
	 */
	public static Icon inColor(Icon icon, Color color) {
		Color opaque = new Color(color.getRGB() & 0xFF_FF_FF);
		return new Icon() {
			@Override
			public void paintIcon(Component component, Graphics g, int x, int y) {
				// paints the icon on its own, then fills only where it drew: the source-in fill keeps the icon's alpha
				BufferedImage image = ImageUtil.createImage(g, getIconWidth(), getIconHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D ig = image.createGraphics();
				try {
					icon.paintIcon(component, ig, 0, 0);
					ig.setComposite(AlphaComposite.SrcIn);
					ig.setColor(opaque);
					ig.fillRect(0, 0, getIconWidth(), getIconHeight());
				}
				finally {
					ig.dispose();
				}
				UIUtil.drawImage(g, image, x, y, null);
			}

			@Override
			public int getIconWidth() {
				return icon.getIconWidth();
			}

			@Override
			public int getIconHeight() {
				return icon.getIconHeight();
			}
		};
	}

	/** The color of text with {@code status}, the muted red for deleted; null when it has none. */
	public static @Nullable Color ofText(@Nullable FileStatus status) {
		return status == FileStatus.DELETED ? REMOVED_TEXT : of(status);
	}

	/** The color of {@code status}, red for deleted; null when it has none. */
	public static @Nullable Color of(@Nullable FileStatus status) {
		if (status == FileStatus.DELETED) {
			return REMOVED;
		}
		return status == null ? null : status.getColor();
	}
}
