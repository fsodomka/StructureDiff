package com.fsodomka.structurediff.reason;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.EditorGutterComponentEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.LineMarkerRendererEx;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.NewUI;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the anchored reasons in the editor: one pencil at the range that best shows the reason, with the reason as tooltip,
 * and a thin bar in the left margin over every range of it the snapshot changes, in the reason's own color; in every editor
 * of the document and in those opened later. The marks follow the edits like the anchors, which are range markers
 * themselves. EDT only, and only while reasons are shown.
 */
final class EditReasonHighlighter {
	/**
	 * The platform's strip for left line markers is a fixed 8 px, 10 px in the new UI, as widening it takes internal API:
	 * with a 1 px inset on both sides and 1 px between lanes, that holds two 3 px bars, or three 2 px bars in the new UI.
	 */
	private static final boolean NEW_UI = NewUI.isEnabled();
	/** Wide enough to see at a glance and to click; {@link #BAR_ALPHA} keeps it from shouting. */
	private static final int BAR_WIDTH = JBUI.scale(NEW_UI ? 2 : 3);
	/** The lane step: the width and a 1 px gap, so the lanes of a line stay apart. */
	private static final int BAR_LANE_STEP = BAR_WIDTH + JBUI.scale(1);
	private static final int BAR_INSET = JBUI.scale(1);
	/** Lanes as many reasons over the same lines can get, as many as the strip holds; a further reason shares the last lane. */
	private static final int MAX_LANES = NEW_UI ? 3 : 2;
	private static final int BAR_GAP = 1;
	private static final int BAR_ALPHA = 110;
	private final Project project;
	/** By root and reason identity, the same key as the service's anchors. */
	private final Map<String, Placed> placed = new LinkedHashMap<>();

	/**
	 * A reason's marks: one highlighter per range it covers, the pencil only on the range it stands for, then one per bar
	 * over the merged changed ranges.
	 */
	private record Placed(EditReason reason, boolean approximate, @Nullable ChangeKind kind, int lane,
						  List<ChangeKind> rangeKinds, List<RangeHighlighter> highlighters, EditReasonGutter pencil) {
		void dispose() {
			highlighters.forEach(RangeHighlighter::dispose);
		}
	}

	EditReasonHighlighter(Project project) {
		this.project = project;
	}

	/**
	 * Places the reasons that are new or whose text, status or kind of change changed, keeps those already placed and
	 * unchanged, and drops the rest; reasons without a valid anchor have no place. {@code kinds} says, by root and reason
	 * identity, what kind of change each anchor's range is, where the snapshot tells.
	 */
	void sync(Collection<EditReasonsService.Anchored> reasons, Map<String, ChangeKind> kinds) {
		Map<String, Placed> previous = new LinkedHashMap<>(placed);
		placed.clear();
		Map<String, List<EditReasonsService.Anchored>> byReason = byReason(reasons);
		Map<String, Integer> lanes = lanes(byReason);
		byReason.forEach((reasonKey, ranges) -> {
			EditReasonsService.Anchored head = headOf(ranges, kinds);
			List<ChangeKind> rangeKinds = ranges.stream().map(anchored -> kinds.get(placedKey(anchored))).toList();
			String key = placedKey(head);
			ChangeKind kind = kinds.get(key);
			int lane = lanes.getOrDefault(reasonKey, 0);
			Placed old = previous.remove(key);
			if (old != null && old.reason().equals(head.reason()) && old.approximate() == head.approximate()
					&& old.kind() == kind && old.lane() == lane && old.rangeKinds().equals(rangeKinds)
					&& sameDocuments(old.highlighters(), ranges)) {
				placed.put(key, old);
				return;
			}
			if (old != null) {
				old.dispose();
			}
			placed.put(key, place(head, ranges, kind, lane, rangeKinds));
		});
		previous.values().forEach(Placed::dispose);
	}

	/** Removes every mark, e.g. while reasons are hidden. */
	void clear() {
		placed.values().forEach(Placed::dispose);
		placed.clear();
	}

	/** The reason one of whose bars is at {@code point}, or whose pencil is on the line there; null when none is. */
	@Nullable
	Hint hintAt(Editor editor, Point point) {
		Document document = editor.getDocument();
		for (Placed placed : placed.values()) {
			for (RangeHighlighter highlighter : placed.highlighters()) {
				if (highlighter.getDocument() == document && highlighter.getLineMarkerRenderer() instanceof ReasonBar bar
						&& bar.atX(point.x) && onLines(editor, highlighter, point)) {
					return new Hint(placed.reason(), placed.approximate(), bar.pencil(), sharing(editor, bar, point));
				}
			}
		}
		// the whole width of the strip, on a line only one reason's bar covers: its 3 px lane is hard to hit
		Placed onStrip = null;
		ReasonBar stripBar = null;
		int covering = 0;
		for (Placed placed : placed.values()) {
			ReasonBar bar = stripCovers(placed, editor, point);
			if (bar != null) {
				onStrip = placed;
				stripBar = bar;
				covering++;
			}
		}
		if (covering == 1) {
			return new Hint(onStrip.reason(), onStrip.approximate(), stripBar.pencil(), 0);
		}
		EditReasonGutter pencil = pencilAt(editor, point);
		return pencil == null ? null : new Hint(pencil.reason(), pencil.approximate(), pencil, 0);
	}

	/** Bars other than {@code hit} in the last lane on the line at {@code point}: the reasons the strip had no lane for. */
	private int sharing(Editor editor, ReasonBar hit, Point point) {
		Document document = editor.getDocument();
		if (hit.lane() < MAX_LANES - 1) {
			return 0;
		}
		int count = 0;
		for (Placed placed : placed.values()) {
			for (RangeHighlighter highlighter : placed.highlighters()) {
				if (highlighter.getDocument() == document && highlighter.getLineMarkerRenderer() instanceof ReasonBar bar
						&& bar != hit && bar.lane() == hit.lane() && bar.onStripX(point.x) && onLines(editor, highlighter, point)) {
					count++;
					break;
				}
			}
		}
		return count;
	}

	/**
	 * The pencil at {@code point}: the icon under it, whose reason may share its line with another pencil, or else the
	 * line's first pencil over any x of the gutter, as the icon is small and the mouse hard to keep on it.
	 */
	@Nullable
	EditReasonGutter pencilAt(Editor editor, Point point) {
		if (editor.getGutter() instanceof EditorGutterComponentEx gutter
				&& gutter.getGutterRenderer(point) instanceof EditReasonGutter pencil) {
			return pencil;
		}
		for (Placed placed : placed.values()) {
			EditReasonGutter pencil = pencilOnLine(placed, editor, point);
			if (pencil != null) {
				return pencil;
			}
		}
		return null;
	}

	/** The pencil, full or small, the gutter draws for {@code placed} on the line at {@code point}; null when none. */
	@Nullable
	private static EditReasonGutter pencilOnLine(Placed placed, Editor editor, Point point) {
		Document document = editor.getDocument();
		int lineHeight = editor.getLineHeight();
		for (RangeHighlighter highlighter : placed.highlighters()) {
			if (highlighter.getDocument() != document || !(highlighter.getGutterIconRenderer() instanceof EditReasonGutter pencil)) {
				continue;
			}
			// the visual line, where the gutter draws the icon: a collapsed fold above it makes that line differ from
			// the document's, and the band from the document line would then name it where the icon is not
			int top = editor.visualLineToY(editor.offsetToVisualPosition(highlighter.getStartOffset()).line);
			if (point.y >= top && point.y < top + lineHeight) {
				return pencil;
			}
		}
		return null;
	}

	/** The reason a hover names, and whether its lines may have drifted: the same text the pencil's tooltip carries. */
	/** @param sharing other reasons whose bars share the last lane at the point, which the hover counts as "+N" */
	record Hint(EditReason reason, boolean approximate, EditReasonGutter pencil, int sharing) {
	}

	/** {@code placed}'s bar in the strip at {@code point}, whole width, which names its line's hint; null when none. */
	@Nullable
	private static ReasonBar stripCovers(Placed placed, Editor editor, Point point) {
		for (RangeHighlighter highlighter : placed.highlighters()) {
			if (highlighter.getDocument() == editor.getDocument() && highlighter.getLineMarkerRenderer() instanceof ReasonBar bar
					&& bar.onStripX(point.x) && onLines(editor, highlighter, point)) {
				return bar;
			}
		}
		return null;
	}

	/**
	 * True when {@code point} is on the lines {@code highlighter} covers in {@code editor}, from the editor's own layout: a
	 * bar paints in every editor of its document and only where it is visible, so the y of its last paint may belong to
	 * another editor or to where the lines were before an edit.
	 */
	private static boolean onLines(Editor editor, RangeHighlighter highlighter, Point point) {
		int top = editor.visualLineToY(editor.offsetToVisualPosition(highlighter.getStartOffset()).line);
		int bottom = editor.visualLineToY(editor.offsetToVisualPosition(highlighter.getEndOffset()).line) + editor.getLineHeight();
		return point.y >= top && point.y < bottom;
	}

	/**
	 * Marks the ranges of the reason the snapshot changes with a bar in the reason's own color, and {@code head}'s with the
	 * pencil as well: a bar over code that has not changed would mark what the reason does not explain.
	 */
	private Placed place(EditReasonsService.Anchored head, List<EditReasonsService.Anchored> ranges, ChangeKind kind, int lane,
						 List<ChangeKind> rangeKinds) {
		// only the changed ones, as the bars: a click tints what the snapshot shows as the reason's, so code it does not
		// change stays as it is even when the reason covers it
		List<RangeMarker> otherRanges = new ArrayList<>();
		for (int i = 0; i < ranges.size(); i++) {
			if (ranges.get(i) != head && rangeKinds.get(i) != null) {
				otherRanges.add(ranges.get(i).marker());
			}
		}
		Document document = head.marker().getDocument();
		List<TextRange> changed = new ArrayList<>();
		List<ChangeKind> changedKinds = new ArrayList<>();
		List<TextRange> approximate = new ArrayList<>();
		for (int i = 0; i < ranges.size(); i++) {
			if (rangeKinds.get(i) != null) {
				TextRange range = ranges.get(i).marker().getTextRange();
				changed.add(range);
				changedKinds.add(rangeKinds.get(i));
				if (ranges.get(i).approximate()) {
					approximate.add(range);
				}
			}
		}
		List<TextRange> spans = barSpans(document, changed);
		// each place in its own kind: one reason often removes code in one place and changes it in another
		List<ChangeKind> placeKinds = spans.stream().map(span -> placeKind(changed, changedKinds, span, kind)).toList();
		int headStart = head.marker().getStartOffset();
		int headPlace = 1;
		for (int i = 0; i < spans.size(); i++) {
			if (spans.get(i).containsOffset(headStart)) {
				headPlace = i + 1;
			}
		}
		// made once: a click on any x of the head's line finds it again through pencilAt
		EditReasonGutter pencil = new EditReasonGutter(project, head.reason(), head.approximate(), head.marker(),
				spans.isEmpty() ? kind : placeKinds.get(headPlace - 1), otherRanges, headPlace, spans.size());
		MarkupModel markup = DocumentMarkupModel.forDocument(document, project, true);
		List<RangeHighlighter> highlighters = new ArrayList<>(ranges.size());
		for (int i = 0; i < ranges.size(); i++) {
			EditReasonsService.Anchored anchored = ranges.get(i);
			RangeMarker marker = anchored.marker();
			// no attributes: the pencil and the bar are the only marks, the range itself stays as the editor shows it
			RangeHighlighter highlighter = markup.addRangeHighlighter(marker.getStartOffset(), marker.getEndOffset(),
					HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE);
			if (anchored == head) {
				highlighter.setGutterIconRenderer(pencil);
			}
			highlighters.add(highlighter);
		}
		// one bar per run of the reason's changed lines: its records often overlap (a whole block, then a line in it),
		// and a bar per record paints the shared lines twice, darker, as if a second bar
		for (int i = 0; i < spans.size(); i++) {
			TextRange span = spans.get(i);
			RangeHighlighter barHighlighter = markup.addRangeHighlighter(span.getStartOffset(), span.getEndOffset(),
					HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE);
			// a small pencil at the start of every other place, as the full one marks only the first
			ChangeKind placeKind = placeKinds.get(i);
			// its own place's: the head may be placed exactly while a short record elsewhere was only found near its lines
			boolean placeApproximate = approximate.stream().anyMatch(range -> span.containsOffset(range.getStartOffset()));
			EditReasonGutter placePencil = i + 1 == headPlace ? pencil
					: pencil.atPlace(i + 1, barHighlighter, placeKind, placeApproximate);
			if (placePencil != pencil) {
				barHighlighter.setGutterIconRenderer(placePencil);
			}
			// a place where code was only removed has no bar: the line it stands on is the one after the gap, which the
			// reason does not explain; its pencil's wedge marks the gap
			if (placeKind != ChangeKind.REMOVED) {
				barHighlighter.setLineMarkerRenderer(new ReasonBar(placePencil.color(), lane, placePencil));
			}
			highlighters.add(barHighlighter);
		}
		return new Placed(head.reason(), head.approximate(), kind, lane, rangeKinds, List.copyOf(highlighters), pencil);
	}

	/**
	 * Marks the old lines of the reasons on {@code anchors}' file in the before side of its diff, {@code before}: a pencil
	 * at each place a change deleted or replaced, as in the editor, but no bars, as the diff's own colors show the change.
	 * The marks live in that editor's markup, so they go with the diff.
	 */
	static void markBefore(Project project, Editor before, Collection<EditReasonsService.Anchored> anchors,
						   LineChanges changes) {
		Document document = before.getDocument();
		MarkupModel markup = before.getMarkupModel();
		byReason(anchors).forEach((reasonKey, ranges) -> {
			List<TextRange> old = new ArrayList<>();
			List<ChangeKind> oldKinds = new ArrayList<>();
			EditReasonsService.Anchored head = null;
			for (EditReasonsService.Anchored anchored : ranges) {
				RangeMarker marker = anchored.marker();
				MarkerLines placed = MarkerLines.of(marker.getDocument(), marker);
				LineChanges.BeforeLines lines = changes.beforeLines(placed.first(), placed.last());
				if (lines == null || lines.last() >= document.getLineCount()) {
					continue;
				}
				old.add(new TextRange(document.getLineStartOffset(lines.first()), document.getLineEndOffset(lines.last())));
				oldKinds.add(changes.kind(placed.first(), placed.last()));
				if (head == null) {
					head = anchored;
				}
			}
			if (head == null) {
				return;
			}
			List<TextRange> spans = barSpans(document, old);
			// each place in its own kind, as in the editor: old lines may have been removed or replaced
			List<ChangeKind> placeKinds = spans.stream()
					.map(span -> placeKind(old, oldKinds, span, ChangeKind.REMOVED)).toList();
			List<RangeMarker> markers = new ArrayList<>(spans.size());
			for (TextRange span : spans) {
				markers.add(markup.addRangeHighlighter(span.getStartOffset(), span.getEndOffset(),
						HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE));
			}
			EditReasonGutter pencil = EditReasonGutter.before(project, head.reason(), head.approximate(), markers.getFirst(),
					placeKinds.getFirst(), markers.subList(1, markers.size()), spans.size());
			for (int i = 0; i < markers.size(); i++) {
				((RangeHighlighter)markers.get(i)).setGutterIconRenderer(i == 0 ? pencil : pencil.atPlace(i + 1, markers.get(i), placeKinds.get(i),
						pencil.approximate()));
			}
		});
	}

	/**
	 * The kind of change at one place, from the changed ranges inside {@code span}: theirs when they agree, modified when
	 * they don't; {@code fallback} when none is inside.
	 */
	private static ChangeKind placeKind(List<TextRange> ranges, List<ChangeKind> kinds, TextRange span, ChangeKind fallback) {
		ChangeKind found = null;
		for (int i = 0; i < ranges.size(); i++) {
			if (span.contains(ranges.get(i))) {
				if (found != null && found != kinds.get(i)) {
					return ChangeKind.MODIFIED;
				}
				found = kinds.get(i);
			}
		}
		return found == null ? fallback : found;
	}

	/** {@code ranges} merged where their lines overlap or touch, by start. */
	static List<TextRange> barSpans(Document document, List<TextRange> ranges) {
		return MarkerLines.runs(ranges, range -> new MarkerLines(document.getLineNumber(range.getStartOffset()),
				document.getLineNumber(range.getEndOffset()))).stream()
				.map(run -> run.stream().reduce(TextRange::union).orElseThrow()).toList();
	}

	/**
	 * The margin lane of each reason, so two reasons over the same lines show side by side instead of one over the other: the
	 * first lane whose ranges this reason does not touch, in the order of the reasons' newest records, as the markers on one
	 * line sit left to right.
	 */
	private static Map<String, Integer> lanes(Map<String, List<EditReasonsService.Anchored>> byReason) {
		Map<Document, List<String>> byDocument = new LinkedHashMap<>();
		byReason.forEach((key, ranges) -> byDocument
				.computeIfAbsent(ranges.getFirst().marker().getDocument(), document -> new ArrayList<>()).add(key));
		Map<String, Integer> lanes = new HashMap<>();
		byDocument.forEach((document, keys) -> {
			// by first range, each reason taking the first lane no reason before it already uses: an interval graph needs
			// exactly as many lanes as reasons that really meet, so the strip is only as wide as the document needs
			keys.sort(Comparator.comparingInt(key -> firstStart(byReason.get(key))));
			List<List<int[]>> taken = new ArrayList<>();
			for (String key : keys) {
				List<int[]> mine = spans(byReason.get(key));
				int lane = 0;
				while (lane < taken.size() && overlaps(taken.get(lane), mine)) {
					lane++;
				}
				if (lane == taken.size()) {
					taken.add(new ArrayList<>());
				}
				taken.get(lane).addAll(mine);
				lanes.put(key, Math.min(lane, MAX_LANES - 1));
			}
		});
		return lanes;
	}

	/** True when a range of {@code one} overlaps one of {@code other}; touching ends count, so the lanes stay apart. */
	private static boolean overlaps(List<int[]> one, List<int[]> other) {
		return one.stream()
				.anyMatch(left -> other.stream().anyMatch(right -> left[0] <= right[1] && right[0] <= left[1]));
	}

	/** The start of the reason's first range, which orders the lanes. */
	private static int firstStart(List<EditReasonsService.Anchored> ranges) {
		return ranges.stream().mapToInt(anchored -> anchored.marker().getStartOffset()).min().orElse(0);
	}

	private static List<int[]> spans(List<EditReasonsService.Anchored> ranges) {
		return ranges.stream()
				.map(anchored -> new int[]{anchored.marker().getStartOffset(), anchored.marker().getEndOffset()})
				.toList();
	}

	/**
	 * The placed ranges of each reason, by the text that stands for it and its file: the mod writes a record per change, so
	 * a reason often covers several ranges (a whole file, or one tool call with several), which the tree lists once as well
	 * ({@code MemberSignals.distinct}). Pending ones stand for themselves: they show where the agent is still working.
	 */
	private static Map<String, List<EditReasonsService.Anchored>> byReason(Collection<EditReasonsService.Anchored> reasons) {
		Map<String, List<EditReasonsService.Anchored>> byReason = new LinkedHashMap<>();
		for (EditReasonsService.Anchored anchored : reasons) {
			RangeMarker marker = anchored.marker();
			if (marker != null && marker.isValid()) {
				byReason.computeIfAbsent(dedupKey(anchored), key -> new ArrayList<>()).add(anchored);
			}
		}
		return byReason;
	}

	/**
	 * The range the pencil stands for: the first one the snapshot shows as changed, so the pencil sits on code the reason
	 * explains rather than on a nearby import; else the newest record, whose first range stands for a reason recorded in
	 * several.
	 */
	private static EditReasonsService.Anchored headOf(List<EditReasonsService.Anchored> ranges, Map<String, ChangeKind> kinds) {
		return ranges.stream()
				.filter(anchored -> kinds.get(placedKey(anchored)) != null)
				.min(Comparator.comparingInt(anchored -> anchored.marker().getStartOffset()))
				.orElseGet(() -> ranges.stream().reduce(EditReasonHighlighter::newest).orElseThrow());
	}

	/**
	 * True when every range still has its highlighter in its marker's document, i.e. no document was reloaded since; the
	 * ranges' highlighters come first, the bars' after them, all in the one document of the reason's file.
	 */
	private static boolean sameDocuments(List<RangeHighlighter> highlighters, List<EditReasonsService.Anchored> ranges) {
		if (highlighters.size() < ranges.size()) {
			return false;
		}
		for (int i = 0; i < ranges.size(); i++) {
			if (highlighters.get(i).getDocument() != ranges.get(i).marker().getDocument()) {
				return false;
			}
		}
		Document document = ranges.getFirst().marker().getDocument();
		return highlighters.stream().allMatch(highlighter -> highlighter.getDocument() == document);
	}

	private static String dedupKey(EditReasonsService.Anchored anchored) {
		return (anchored.reason().pending() ? "pending|" + anchored.reason().identity() : "reason|" + anchored.reason().reason())
				+ "|" + anchored.path();
	}

	/** The key of the placed pencil and of the service's anchors; the kind of change is looked up by it. */
	private static String placedKey(EditReasonsService.Anchored anchored) {
		return anchored.key();
	}

	private static EditReasonsService.Anchored newest(EditReasonsService.Anchored one, EditReasonsService.Anchored other) {
		int byTime = one.reason().time().compareTo(other.reason().time());
		if (byTime != 0) {
			return byTime > 0 ? one : other;
		}
		// the first of the reason's ranges, when its records share a time
		RangeMarker kept = one.marker();
		RangeMarker candidate = other.marker();
		return kept != null && candidate != null && candidate.getStartOffset() < kept.getStartOffset() ? other : one;
	}

	/**
	 * A thin bar in the editor's left margin over a range the reason covers, in the reason's color: in the strip the
	 * platform keeps for plugins, left of the icons, so it misses the line numbers, the pencils, the folding anchors and
	 * the changed-lines stripe; in the reason's lane so two reasons over the same lines sit side by side, and with a gap at
	 * its ends so bars of different colors never run into one another. It remembers the x where it painted itself, so the
	 * hover can tell whether the mouse is on it (a line marker has no tooltip of its own); the y comes from the hovered
	 * editor, see {@link #onLines}.
	 */
	static final class ReasonBar implements LineMarkerRendererEx {
		private final Color color;
		private final int lane;
		private final EditReasonGutter pencil;
		/** The x span of the last paint, in the gutter's coordinates; null until painted. The gutters of one document's editors share it. */
		private @Nullable Rectangle painted;
		/** The x span of the whole strip at the last paint, whose width names the reason on a line no other reason covers. */
		private @Nullable Rectangle strip;

		ReasonBar(Color color, int lane, EditReasonGutter pencil) {
			this.color = color;
			this.lane = lane;
			this.pencil = pencil;
		}

		/** The pencil of the place this bar marks, whose hover names that place. */
		EditReasonGutter pencil() {
			return pencil;
		}

		int lane() {
			return lane;
		}

		Color color() {
			return color;
		}

		/** The strip the platform keeps for plugins, which the platform hands a left-positioned line marker. */
		@Override
		public @NotNull Position getPosition() {
			return Position.LEFT;
		}

		/** True when {@code x}, in the gutter's coordinates, is on the bar's lane. */
		boolean atX(int x) {
			return painted != null && x >= painted.x && x < painted.x + painted.width;
		}

		/** True when {@code x} is in the strip the bar paints in. */
		boolean onStripX(int x) {
			return strip != null && x >= strip.x && x < strip.x + strip.width;
		}

		@Override
		public void paint(@NotNull Editor editor, @NotNull Graphics g, @NotNull Rectangle bounds) {
			strip = new Rectangle(bounds);
			painted = new Rectangle(bounds.x + BAR_INSET + lane * BAR_LANE_STEP, bounds.y + BAR_GAP, BAR_WIDTH,
					Math.max(1, bounds.height - 2 * BAR_GAP));
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), BAR_ALPHA));
			g.fillRect(painted.x, painted.y, painted.width, painted.height);
		}
	}
}
