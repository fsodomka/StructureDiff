package com.fsodomka.structurediff.reason;

import com.fsodomka.structurediff.StatusColors;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.LayeredIcon;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The pencil at the first line of the range that stands for a reason, with the reason as tooltip; a click tints every
 * changed range of it, since the editor can select only one. One per reason, placed by {@link EditReasonHighlighter};
 * its kind of change shows in the pencil's color and in the bars of the other ranges, which name their reason when
 * hovered.
 */
final class EditReasonGutter extends GutterIconRenderer implements DumbAware {
	/** One pencil per kind, at its resolved color, so a theme switch re-tints it. */
	private static final Map<Integer, Icon> PENCILS = new HashMap<>();

	private final Project project;
	private final EditReason reason;
	private final boolean approximate;
	/** The kind of change at the range, from the snapshot's diff; null when the snapshot doesn't tell. */
	private final @Nullable ChangeKind kind;
	/** The anchor itself, so the tint tracks later edits that moved the range. */
	private final RangeMarker marker;
	/** The reason's other changed ranges, which a click tints as well: one selection would not show the whole reason. */
	private final List<RangeMarker> otherRanges;
	/** Separate blocks of changed code the reason's bars mark, which the hover counts when there are several. */
	private final int places;
	/** Which of the {@link #places} this pencil marks, from the top, 1-based. */
	private final int place;
	/** A smaller pencil at the start of another place of the reason, so each place shows whose it is. */
	private final boolean small;
	/** Where this pencil stands: the anchor for the full pencil, its place's start for a small one. */
	private final RangeMarker at;
	/** On the before side of a diff, whose old lines it marks: there a click tints them, as nowhere else shows them. */
	private final boolean before;
	private final AnAction clickAction = new AnAction() {
		@Override
		public void actionPerformed(@NotNull AnActionEvent e) {
			if (!before) {
				project.getMessageBus().syncPublisher(EditReasonsListener.TOPIC).reasonClicked(reason, at);
			}
			// removed code is not in the editor: the click shows it where it still is, on the left of the file's diff
			if (kind == ChangeKind.REMOVED && !before) {
				EditReasonsService.getInstance(project).openBefore(at);
				return;
			}
			Editor editor = e.getData(CommonDataKeys.EDITOR);
			if (editor != null) {
				reveal(editor);
				return;
			}
			// the icon of another editor of the same document, e.g. a split's
			Editor selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
			if (selected != null) {
				reveal(selected);
			}
		}

		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread() {
			return ActionUpdateThread.EDT;
		}
	};

	EditReasonGutter(Project project, EditReason reason, boolean approximate, RangeMarker marker,
					 @Nullable ChangeKind kind, List<RangeMarker> otherRanges, int place, int places) {
		this(project, reason, approximate, marker, kind, otherRanges, place, places, false);
	}

	private EditReasonGutter(Project project, EditReason reason, boolean approximate, RangeMarker marker,
							 @Nullable ChangeKind kind, List<RangeMarker> otherRanges, int place, int places, boolean before) {
		this.project = project;
		this.reason = reason;
		this.approximate = approximate;
		this.marker = marker;
		this.kind = kind;
		this.otherRanges = List.copyOf(otherRanges);
		this.places = places;
		this.place = place;
		this.small = false;
		this.at = marker;
		this.before = before;
	}

	/** The pencil for a reason's old lines on the before side of a diff, {@code marker} in that side's document. */
	static EditReasonGutter before(Project project, EditReason reason, boolean approximate, RangeMarker marker,
								   @Nullable ChangeKind kind, List<RangeMarker> otherRanges, int places) {
		return new EditReasonGutter(project, reason, approximate, marker, kind, otherRanges, 1, places, true);
	}

	private EditReasonGutter(EditReasonGutter of, int place, RangeMarker at, @Nullable ChangeKind kind, boolean approximate) {
		this.project = of.project;
		this.reason = of.reason;
		this.approximate = approximate;
		this.marker = of.marker;
		this.kind = kind;
		this.otherRanges = of.otherRanges;
		this.places = of.places;
		this.place = place;
		this.small = true;
		this.at = at;
		this.before = of.before;
	}

	/**
	 * The small pencil for another place of this reason, {@code place} from the top, starting {@code at}, with that
	 * place's own {@code kind} of change and whether that place is {@code approximate}: a smaller icon.
	 */
	EditReasonGutter atPlace(int place, RangeMarker at, @Nullable ChangeKind kind, boolean approximate) {
		return new EditReasonGutter(this, place, at, kind, approximate);
	}

	@Override
	public @NotNull Icon getIcon() {
		// in the editor, removed code is gone: a wedge at the top of the pencil marks the gap above the line it stands on
		Icon icon = pencil(color(), kind == null, kind == ChangeKind.REMOVED && !before);
		return small ? IconUtil.scale(icon, null, 0.7f) : icon;
	}

	/** The reason this pencil stands for. */
	EditReason reason() {
		return reason;
	}

	/** Whether the reason's lines may have drifted, as its tooltip says. */
	boolean approximate() {
		return approximate;
	}

	/**
	 * The pencil in {@code color}, dimmed when {@code dimmed}: a reason on code the snapshot shows unchanged is stale, so
	 * it steps back behind the live ones.
	 */
	private static Icon pencil(Color color, boolean dimmed, boolean wedge) {
		return PENCILS.computeIfAbsent((color.getRGB() & 0xFF_FF_FF) | (wedge ? 1 << 24 : 0) | (dimmed ? 1 << 25 : 0), rgb -> {
			Icon tinted = StatusColors.pencil(color);
			Icon icon = dimmed ? IconLoader.getTransparentIcon(tinted, 0.45f) : tinted;
			if (!wedge) {
				return icon;
			}
			LayeredIcon layered = new LayeredIcon(2);
			layered.setIcon(icon, 0);
			layered.setIcon(new WedgeIcon(color, icon.getIconWidth(), icon.getIconHeight()), 1);
			return layered;
		});
	}

	/** A small triangle in the top left corner, over the pencil: where the deleted lines were, as the diff's stripe marks it. */
	private record WedgeIcon(Color color, int width, int height) implements Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			int size = JBUI.scale(6);
			g.setColor(color);
			g.fillPolygon(new int[]{x, x + size, x}, new int[]{y, y, y + size}, 3);
		}

		@Override
		public int getIconWidth() {
			return width;
		}

		@Override
		public int getIconHeight() {
			return height;
		}
	}

	/**
	 * This pencil's color, which its place's bar uses too: purple for a skill's mark, whatever the kind of change (still
	 * dimmed where the snapshot shows none); else the kind's.
	 */
	Color color() {
		return reason.skill() ? StatusColors.SKILL : color(kind);
	}

	/** The color of {@code kind} of change, grey when it isn't known; the pencil and the bars of the other ranges use it. */
	static Color color(@Nullable ChangeKind kind) {
		return kind == null ? StatusColors.UNKNOWN
				: kind == ChangeKind.ADDED ? StatusColors.ADDED
				: kind == ChangeKind.MODIFIED ? StatusColors.MODIFIED
				: StatusColors.REMOVED_MARK;
	}

	@Override
	public @Nullable String getTooltipText() {
		// the gutter's own tooltip waits for the mouse to rest; the hover in EditReasonsService shows this text at once
		return null;
	}

	@Override
	public @Nullable String getAccessibleTooltipText() {
		return hoverText();
	}

	/** The reason as its hover shows it: with how many lines were deleted where this pencil marks a removal. */
	String hoverText() {
		int deleted = kind == ChangeKind.REMOVED && !before ? EditReasonsService.getInstance(project).deletedLines(at) : 0;
		return text(reason, approximate, kind, place, places)
				+ (deleted > 0 ? " · " + deleted + (deleted == 1 ? " line" : " lines") + " deleted" : "");
	}

	/** Separate blocks of changed code the reason marks. */
	int places() {
		return places;
	}

	/** Which place this pencil marks, from the top, 1-based. */
	int place() {
		return place;
	}

	/** The kind of change at the range, null when the snapshot shows none there. */
	@Nullable ChangeKind kind() {
		return kind;
	}

	/** The reason as one line of text, for the hover and for screen readers. */
	static String text(EditReason reason, boolean approximate, @Nullable ChangeKind kind, int place, int places) {
		return EditReason.when(reason.time()) + " · " + (reason.pending() ? "reason pending" : reason.reason())
				+ (places > 1 ? " [" + place + "/" + places + "]" : "")
				+ (reason.start() < 1 ? " (whole file)" : "")
				+ (approximate ? " (approximate)" : "")
				+ (kind == null ? " (no change here in the shown snapshot)" : "");
	}

	@Override
	public AnAction getClickAction() {
		return clickAction;
	}

	/** Tints every changed range of the reason in {@code editor}, unless it shows another document, until the caret moves. */
	void reveal(Editor editor) {
		if (!marker.isValid() || editor.getDocument() != marker.getDocument()) {
			return;
		}
		List<RangeMarker> ranges = new ArrayList<>(otherRanges);
		// the pencil's own range only when its kind is known: a grey pencil has no bar either, and a tint would mark code
		// the snapshot doesn't change
		if (kind != null) {
			ranges.add(marker);
		}
		EditReasonsService.getInstance(project).revealRanges(editor, ranges, reason.skill() ? StatusColors.SKILL_TINT : color());
	}

	@Override
	public boolean isNavigateAction() {
		return true;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof EditReasonGutter other && other.reason.equals(reason) && other.approximate == approximate
				&& other.kind == kind && other.places == places && other.place == place && other.before == before
				&& other.small == small;
	}

	@Override
	public int hashCode() {
		return Objects.hash(reason, approximate, kind, places, place, before, small);
	}
}
