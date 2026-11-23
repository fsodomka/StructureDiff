package com.fsodomka.structurediff.reason;

import junit.framework.TestCase;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

public class EditReasonOrderTest extends TestCase {
	private static EditReason reason(String session, String id, String file, int line, String reason, String time,
									 String source, int seq) {
		return new EditReason(id, "Edit", file, line, line, null, reason, Instant.parse(time), source, session, null, seq);
	}

	private static List<List<String>> order(List<EditReason> reasons) {
		return EditReasonOrder.runs(reasons, Function.identity()).stream()
				.map(run -> run.items().stream().map(EditReason::toolUseId).toList()).toList();
	}

	public void testAgentReasonsByTimeWithOneCallsRangesInRecordOrder() {
		List<EditReason> reasons = List.of(
				reason("s", "late", "A.java", 1, "r", "2026-11-17T10:05:00Z", "agent", 0),
				reason("s", "call", "A.java", 400, "r", "2026-11-17T10:01:00Z", "agent", 2),
				reason("s", "call", "A.java", 3, "r", "2026-11-17T10:01:00Z", "agent", 1),
				reason("s", "early", "B.java", 9, "r", "2026-11-17T10:00:00Z", "agent", 3));

		List<EditReasonOrder.Run<EditReason>> runs = EditReasonOrder.runs(reasons, Function.identity());

		assertEquals(List.of(List.of("early", "call", "call", "late")), order(reasons));
		assertEquals(List.of(3, 400), runs.getFirst().items().subList(1, 3).stream().map(EditReason::start).toList());
		assertFalse(runs.getFirst().skill());
	}

	public void testASkillRunKeepsRecordOrderDespiteEqualTimes() {
		String time = "2026-11-17T16:59:00Z";
		List<EditReason> reasons = List.of(
				reason("walk", "c", "B.java", 1, "r", time, "skill", 2),
				reason("walk", "a", "A.java", 60, "Main change", time, "skill", 0),
				reason("walk", "b", "A.java", 3, "r", time, "skill", 1));

		List<EditReasonOrder.Run<EditReason>> runs = EditReasonOrder.runs(reasons, Function.identity());

		assertEquals(List.of(List.of("a", "b", "c")), order(reasons));
		assertTrue(runs.getFirst().skill());
		assertEquals("walk", runs.getFirst().session());
	}

	public void testTwoSessionsAreTwoRunsByEarliestTime() {
		List<EditReason> reasons = List.of(
				reason("later", "l", "A.java", 1, "r", "2026-11-17T12:00:00Z", "agent", 0),
				reason("earlier", "e2", "A.java", 1, "r", "2026-11-17T13:00:00Z", "agent", 1),
				reason("earlier", "e1", "A.java", 2, "r", "2026-11-17T11:00:00Z", "agent", 0));

		List<EditReasonOrder.Run<EditReason>> runs = EditReasonOrder.runs(reasons, Function.identity());

		assertEquals(List.of("earlier", "later"), runs.stream().map(EditReasonOrder.Run::session).toList());
		assertEquals(List.of(List.of("e1", "e2"), List.of("l")), order(reasons));
		assertEquals(Instant.parse("2026-11-17T11:00:00Z"), runs.getFirst().earliest());
	}

	public void testFilelessReasonsAreLeftOutPendingOnesKept() {
		List<EditReason> reasons = List.of(
				reason("s", "pending", "A.java", 1, null, "2026-11-17T10:00:00Z", "pending", 0),
				reason("s", "fileless", null, -1, "r", "2026-11-17T10:00:00Z", "agent", 1),
				reason("s", "kept", "A.java", 2, "r", "2026-11-17T10:00:00Z", "agent", 2));

		assertEquals(List.of(List.of("pending", "kept")), order(reasons));
		assertEquals(List.of(), EditReasonOrder.runs(reasons.subList(1, 2), Function.identity()));
	}
}
