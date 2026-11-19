package com.fsodomka.structurediff.reason;

/** The kind of change at an anchor's range, from the snapshot's before and after texts of its file. */
public enum ChangeKind {
	ADDED,
	MODIFIED,
	REMOVED
}
