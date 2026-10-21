package com.fsodomka.structurediff.review;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Highlights findings in the document markup model: every editor of the document shows them, also editors opened later,
 * for every language, and the highlights follow edits. EDT only.
 */
final class ReviewHighlighter {
	private final Project project;
	private final Map<Finding, Placement> placements = new LinkedHashMap<>();

	ReviewHighlighter(Project project) {
		this.project = project;
	}

	Collection<Placement> placements() {
		return placements.values();
	}

	void place(VirtualFile root, Collection<Finding> findings) {
		for (Finding finding : findings) {
			remove(finding);
			placements.put(finding, placeOne(root, finding));
		}
	}

	/** Places the findings of {@code file} again, after its text was replaced, e.g. reloaded from disk; false if it has none. */
	boolean replaceIn(VirtualFile file) {
		boolean any = false;
		for (Placement placement : List.copyOf(placements.values())) {
			if (file.equals(placement.file())) {
				place(placement.root(), List.of(placement.finding()));
				any = true;
			}
		}
		return any;
	}

	/**
	 * After an edit of {@code document}: marks the findings whose reviewed code was deleted as {@link ReviewAnchor.Status#GONE},
	 * as a load would, and places {@code GONE} findings again whose code is back near its line, e.g. by an undo; false if
	 * none changed. Only findings placed by their snippet: a line may be empty.
	 */
	boolean update(DocumentEvent event) {
		Document document = event.getDocument();
		VirtualFile file = FileDocumentManager.getInstance().getFile(document);
		boolean any = false;
		for (Placement placement : List.copyOf(placements.values())) {
			RangeHighlighter highlighter = placement.highlighter();
			boolean bySnippet = placement.status() == ReviewAnchor.Status.EXACT || placement.status() == ReviewAnchor.Status.MOVED;
			String snippet = placement.finding().snippet();
			if (snippet == null || snippet.isEmpty()) {
				continue;
			}
			if (highlighter != null && bySnippet && highlighter.getDocument() == document
					&& (!highlighter.isValid() || highlighter.getStartOffset() == highlighter.getEndOffset())) {
				remove(placement.finding());
				placements.put(placement.finding(), new Placement(placement.finding(), placement.root(), placement.file(),
						ReviewAnchor.Status.GONE, null));
				any = true;
			}
			else if (placement.status() == ReviewAnchor.Status.GONE && file != null && file.equals(placement.file())
					&& completesSnippet(event, snippet)) {
				ReviewAnchor.Status status = ReviewAnchor.locate(document, placement.finding()).status();
				// not STALE: the snippet typed elsewhere, such as a copy, does not bring the finding back to its old line
				if (status == ReviewAnchor.Status.EXACT || status == ReviewAnchor.Status.MOVED) {
					place(placement.root(), List.of(placement.finding()));
					any = true;
				}
			}
		}
		return any;
	}

	/**
	 * Whether the edit may have brought {@code snippet} back: it occurs in the text around the edit. Saves searching the
	 * whole file on every keystroke for each finding whose code is gone.
	 */
	private static boolean completesSnippet(DocumentEvent event, String snippet) {
		CharSequence text = event.getDocument().getImmutableCharSequence();
		int from = Math.max(0, event.getOffset() - snippet.length() + 1);
		int to = Math.min(text.length(), event.getOffset() + event.getNewLength() + snippet.length() - 1);
		return from < to && StringUtil.contains(text.subSequence(from, to), snippet);
	}

	/** Marks the findings of deleted files {@link ReviewAnchor.Status#MISSING_FILE}, as a load would; false if none changed. */
	boolean dropDeleted() {
		boolean any = false;
		for (Placement placement : List.copyOf(placements.values())) {
			if (placement.file() != null && !placement.file().isValid() && placement.status() != ReviewAnchor.Status.MISSING_FILE) {
				remove(placement.finding());
				placements.put(placement.finding(), new Placement(placement.finding(), placement.root(), placement.file(),
						ReviewAnchor.Status.MISSING_FILE, null));
				any = true;
			}
		}
		return any;
	}

	void remove(Finding finding) {
		Placement placement = placements.remove(finding);
		if (placement != null && placement.highlighter() != null) {
			// not via DocumentMarkupModel.forDocument: once the project is disposing it returns an empty model that throws
			placement.highlighter().dispose();
		}
	}

	void removeRoot(VirtualFile root) {
		List<Finding> findings = new ArrayList<>();
		for (Placement placement : placements.values()) {
			if (placement.root().equals(root)) {
				findings.add(placement.finding());
			}
		}
		findings.forEach(this::remove);
	}

	void removeAll() {
		List.copyOf(placements.keySet()).forEach(this::remove);
	}

	private Placement placeOne(VirtualFile root, Finding finding) {
		VirtualFile file = resolve(root, finding.path());
		Document document = file == null || !file.isValid() ? null : FileDocumentManager.getInstance().getDocument(file);
		if (document == null) {
			return new Placement(finding, root, file, ReviewAnchor.Status.MISSING_FILE, null);
		}
		ReviewAnchor.Anchor anchor = ReviewAnchor.locate(document, finding);
		if (anchor.status() == ReviewAnchor.Status.GONE) {
			return new Placement(finding, root, file, anchor.status(), null);
		}
		RangeHighlighter highlighter = DocumentMarkupModel.forDocument(document, project, true).addRangeHighlighter(
				finding.severity().attributes, anchor.start(), anchor.end(), HighlighterLayer.WARNING, HighlighterTargetArea.EXACT_RANGE);
		highlighter.setErrorStripeTooltip(ReviewText.html(finding, anchor.status()));
		highlighter.setGutterIconRenderer(new FindingGutterRenderer(project, finding, anchor.status()));
		return new Placement(finding, root, file, anchor.status(), highlighter);
	}

	/**
	 * Only local and jar URLs: a committed SARIF is loaded when the project opens, and must not make the IDE resolve, or
	 * fetch, a remote file.
	 */
	static @Nullable VirtualFile resolve(VirtualFile root, String path) {
		if (path.contains("://")) {
			String protocol = VirtualFileManager.extractProtocol(path);
			boolean local = StandardFileSystems.FILE_PROTOCOL.equals(protocol) || StandardFileSystems.JAR_PROTOCOL.equals(protocol);
			return local ? VirtualFileManager.getInstance().findFileByUrl(path) : null;
		}
		if (FileUtil.isAbsolute(path)) {
			return LocalFileSystem.getInstance().findFileByPath(path);
		}
		return root.findFileByRelativePath(path);
	}
}
