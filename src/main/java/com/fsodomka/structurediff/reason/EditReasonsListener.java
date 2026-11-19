package com.fsodomka.structurediff.reason;

import com.intellij.openapi.editor.RangeMarker;
import com.intellij.util.messages.Topic;

/** Project-level notification that the loaded edit reasons changed. */
public interface EditReasonsListener {
	Topic<EditReasonsListener> TOPIC = Topic.create("Edit reasons", EditReasonsListener.class);

	void reasonsChanged();

	/** The kinds of change at the anchors were computed again, e.g. for another snapshot; see {@link EditReasonsService#kind}. */
	default void kindsChanged() {
	}

	/** A gutter pencil of {@code reason} was clicked, the one standing at {@code at}: e.g. to select that place in a list. */
	default void reasonClicked(EditReason reason, RangeMarker at) {
	}
}
