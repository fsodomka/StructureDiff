package com.fsodomka.structurediff.reason;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reason hover on editor gutters and the click reveal of a reason's other ranges, for {@link EditReasonsService}.
 * EDT only.
 */
final class EditReasonHover implements Disposable {
	/** Characters per line of the hover hint, as the tree's tooltips wrap a reason. */
	private static final int HINT_LINE = 60;

	private final Project project;
	private final EditReasonHighlighter highlighter;
	/** The reason hover on each editor's gutter and content, and the components to take it off again. */
	private final Map<Editor, EditorHover> hovers = new HashMap<>();
	/** The hint naming the bar under the mouse, and the reason it names, to show it only once per reason. */
	private @Nullable Balloon balloon;
	private @Nullable EditReason hoveredReason;
	/** The tint over the other ranges of the reason whose pencil was clicked, until the caret moves or the editor closes. */
	private @Nullable Reveal reveal;

	/** What {@link #attachBarHover} added to an editor's components, so {@link #detachBarHover} can take it off. */
	private record EditorHover(List<JComponent> components, MouseAdapter listener) {
	}

	/** Highlights over the rest of a clicked reason's ranges, and the listener that takes them off. */
	private record Reveal(List<RangeHighlighter> marks, CaretModel caret, CaretListener listener) {
	}

	EditReasonHover(Project project, EditReasonHighlighter highlighter) {
		this.project = project;
		this.highlighter = highlighter;
		installBarHovers();
	}

	/**
	 * Shows a reason while the mouse rests on one of its bars: a bar is a line marker, which has no tooltip of its own, so
	 * without this the pencil's tooltip would be the only way to name it.
	 */
	private void installBarHovers() {
		EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
			@Override
			public void editorCreated(@NotNull EditorFactoryEvent event) {
				attachBarHover(event.getEditor());
			}

			@Override
			public void editorReleased(@NotNull EditorFactoryEvent event) {
				detachBarHover(event.getEditor());
			}
		}, this);
		// editors opened before this service: the tool window's own, for one
		for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
			attachBarHover(editor);
		}
	}

	private void attachBarHover(Editor editor) {
		// the editor factory is the application's: another project's service would hover, click and size the strip of
		// this project's editors too, two balloons over one pencil
		if (hovers.containsKey(editor) || editor.getProject() != project) {
			return;
		}
		// the marks live in the gutter: listening to the text area too would name a reason whenever the mouse crosses
		// the pencil's line, over code or whitespace, which reads as a tooltip on unrelated lines
		JComponent gutter = editor.getGutter() instanceof JComponent component ? component : editor.getContentComponent();
		MouseAdapter hover = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent event) {
				hoverBar(editor, event.getComponent(), event.getPoint());
			}

			@Override
			public void mouseExited(MouseEvent event) {
				hideBarHint();
			}
		};
		gutter.addMouseListener(hover);
		gutter.addMouseMotionListener(hover);
		hovers.put(editor, new EditorHover(List.of(gutter), hover));
	}

	private void detachBarHover(Editor editor) {
		EditorHover hover = hovers.remove(editor);
		if (hover != null) {
			hover.components().forEach(component -> {
				component.removeMouseListener(hover.listener());
				component.removeMouseMotionListener(hover.listener());
			});
		}
		// a reveal on this editor must not outlive it: its tint lives in the document's markup model, so it would keep
		// showing in every other editor of the document, with no caret left to take it off
		if (reveal != null && reveal.caret() == editor.getCaretModel()) {
			hideReveal();
		}
		hideBarHint();
	}

	/** A hint for the reason under the mouse, unless it is the one already shown. */
	private void hoverBar(Editor editor, Component component, Point point) {
		EditReasonHighlighter.Hint hint = hintAt(editor, point);
		EditReason reason = hint == null ? null : hint.reason();
		if (reason != null && reason.equals(hoveredReason)) {
			return;
		}
		hideBalloon();
		hoveredReason = reason;
		if (hint == null) {
			return;
		}
		balloon = JBPopupFactory.getInstance()
				.createHtmlTextBalloonBuilder(hintHtml(hint.pencil().hoverText()
						+ (hint.sharing() > 0 ? " (+" + hint.sharing() + " more in this lane)" : "")), null,
						null, null, null)
				// a hover, not a notification: it stays until the mouse leaves the mark
				.setFadeoutTime(-1)
				.createBalloon();
		balloon.show(new RelativePoint(component, point), Balloon.Position.above);
	}

	/** The reason under {@code point} in {@code editor}'s gutter: the hover's lookup, apart from the balloon it shows. */
	@Nullable
	EditReasonHighlighter.Hint hintAt(Editor editor, Point point) {
		return highlighter.hintAt(editor, point);
	}

	/** {@code text} escaped and broken between words, as the tree's tooltips wrap a reason, so the hint stays narrow. */
	private static String hintHtml(String text) {
		StringBuilder html = new StringBuilder("<html>");
		int lineLength = 0;
		for (String word : text.split("\\s+")) {
			if (lineLength > 0 && lineLength + 1 + word.length() > HINT_LINE) {
				html.append("<br>");
				lineLength = 0;
			}
			else if (lineLength > 0) {
				html.append(' ');
				lineLength++;
			}
			html.append(StringUtil.escapeXmlEntities(word));
			lineLength += word.length();
		}
		return html.append("</html>").toString();
	}

	private void hideBarHint() {
		hoveredReason = null;
		hideBalloon();
	}

	private void hideBalloon() {
		if (balloon != null) {
			balloon.dispose();
			balloon = null;
		}
	}

	/**
	 * Tints the other ranges of a clicked reason in {@code editor}, in its color, until the caret moves: the editor has one
	 * selection, so this is how a click shows a reason that spans several ranges.
	 */
	void revealRanges(Editor editor, List<RangeMarker> ranges, Color color) {
		hideReveal();
		if (ranges.isEmpty()) {
			return;
		}
		TextAttributes attributes = new TextAttributes(null, ColorUtil.withAlpha(color, 0.25), null, null, Font.PLAIN);
		List<RangeHighlighter> marks = new ArrayList<>(ranges.size());
		for (RangeMarker range : ranges) {
			if (range.isValid() && range.getDocument() == editor.getDocument()) {
				marks.add(DocumentMarkupModel.forDocument(editor.getDocument(), project, true)
						.addRangeHighlighter(range.getStartOffset(), range.getEndOffset(), HighlighterLayer.SELECTION - 1,
								attributes, HighlighterTargetArea.EXACT_RANGE));
			}
		}
		CaretListener clear = new CaretListener() {
			@Override
			public void caretPositionChanged(@NotNull CaretEvent event) {
				hideReveal();
			}
		};
		reveal = new Reveal(marks, editor.getCaretModel(), clear);
		editor.getCaretModel().addCaretListener(clear);
	}

	private void hideReveal() {
		if (reveal != null) {
			reveal.marks().forEach(RangeHighlighter::dispose);
			reveal.caret().removeCaretListener(reveal.listener());
			reveal = null;
		}
	}

	@Override
	public void dispose() {
		hovers.values().forEach(hover -> hover.components().forEach(component -> {
			component.removeMouseListener(hover.listener());
			component.removeMouseMotionListener(hover.listener());
		}));
		hovers.clear();
		hideBarHint();
		hideReveal();
	}
}
