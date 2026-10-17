package com.fsodomka.structurediff.review;

import com.intellij.util.messages.Topic;

/** Project-level notification that the loaded reviews, their placements or dismissals changed. */
public interface ReviewFindingsListener {
	Topic<ReviewFindingsListener> TOPIC = Topic.create("Review findings", ReviewFindingsListener.class);

	void findingsChanged();
}
