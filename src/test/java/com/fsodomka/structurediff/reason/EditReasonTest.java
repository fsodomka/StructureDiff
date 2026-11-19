package com.fsodomka.structurediff.reason;

import junit.framework.TestCase;

import java.util.List;

public class EditReasonTest extends TestCase {
	private static String record(String toolUseId, String file, String lines, String reason, String source) {
		return record("2026-11-15T12:00:00Z", toolUseId, file, lines, reason, source);
	}

	private static String record(String time, String toolUseId, String file, String lines, String reason, String source) {
		return """
				{"session":"s","time":"%s","turn":"t","agent":null,"tool":"Edit","toolUseId":"%s",\
				"file":%s,"lines":%s,"reason":%s,"source":"%s"}""".formatted(time, toolUseId, file, lines, reason, source);
	}

	public void testAnUndoneChangeIsDropped() {
		String text = String.join("\n",
				record("t1", "\"a/A.java\"", "[3,5]", "null", "pending"),
				record("t2", "\"a/B.java\"", "[1,1]", "null", "pending"),
				record("t1", "\"a/A.java\"", "[3,5]", "null", "undone"));

		assertEquals(List.of("a/B.java"), EditReason.read(text).stream().map(EditReason::file).toList());
	}

	public void testTheLastRecordOfAChangeWins() {
		String text = String.join("\n",
				record("t1", "\"a/A.java\"", "[3,5]", "null", "pending"),
				record("t2", "\"a/B.java\"", "[1,1]", "null", "pending"),
				record("t1", "\"a/A.java\"", "[3,5]", "\"Fix the cache key.\"", "agent"));

		List<EditReason> reasons = EditReason.read(text);

		assertEquals(2, reasons.size());
		EditReason first = reasons.getFirst();
		assertEquals("a/A.java", first.file());
		assertEquals(3, first.start());
		assertEquals(5, first.end());
		assertEquals("Fix the cache key.", first.reason());
		assertFalse(first.pending());
		assertTrue(reasons.get(1).pending());
	}

	public void testAChangeKeepsTheTimeOfItsFirstRecord() {
		String text = record("2026-11-15T12:00:00Z", "t1", "\"A.java\"", "[3,5]", "null", "pending") + "\n"
				+ record("2026-11-15T13:30:00Z", "t1", "\"A.java\"", "[3,5]", "\"Why.\"", "agent");

		EditReason reason = EditReason.read(text).getFirst();

		assertEquals("Why.", reason.reason());
		assertEquals(java.time.Instant.parse("2026-11-15T12:00:00Z"), reason.time());
	}

	public void testAChangeKeepsTheTextOfItsPendingRecord() {
		String text = record("t1", "\"A.java\"", "[3,4]", "null", "pending").replace("\"reason\"", "\"text\":\"a\\nb\",\"reason\"")
				+ "\n" + record("t1", "\"A.java\"", "[3,4]", "\"Why.\"", "agent");

		EditReason reason = EditReason.read(text).getFirst();

		assertEquals("Why.", reason.reason());
		assertEquals("a\nb", reason.text());
	}

	public void testOneCallWithSeveralRangesKeepsEach() {
		String text = record("t1", "\"A.java\"", "[3,5]", "\"r\"", "agent") + "\n"
				+ record("t1", "\"A.java\"", "[9,9]", "\"r\"", "agent");

		assertEquals(2, EditReason.read(text).size());
	}

	public void testAChangeWithoutFileOrLines() {
		EditReason reason = EditReason.read(record("t1", "null", "null", "\"Rename Foo to Bar.\"", "agent")).getFirst();

		assertNull(reason.file());
		assertEquals(-1, reason.start());
		assertEquals("Rename Foo to Bar.", reason.reason());
	}

	public void testSkipsHalfWrittenAndForeignLines() {
		String text = record("t1", "\"A.java\"", "[1,2]", "\"r\"", "agent") + "\r\n"
				+ "{\"toolUseId\":\"t2\",\"time\":\"2026-11-15T12:00:00Z\",\"file\":\"B.ja" + "\n"
				+ "not json\n\n"
				+ "{\"time\":\"2026-11-15T12:00:00Z\"}\n"
				+ "[1,2]\n";

		List<EditReason> reasons = EditReason.read(text);

		assertEquals(1, reasons.size());
		assertEquals("A.java", reasons.getFirst().file());
	}

	public void testSessionAndSeqAreRead() {
		String text = String.join("\n",
				record("t1", "\"A.java\"", "[1,1]", "null", "pending"),
				record("t2", "\"B.java\"", "[2,2]", "\"Why B.\"", "agent"),
				record("t1", "\"A.java\"", "[1,1]", "\"Why A.\"", "agent"));

		List<EditReason> reasons = EditReason.read(text, "file-name");

		assertEquals(List.of("s", "s"), reasons.stream().map(EditReason::session).toList());
		// the merged change keeps its first record's position
		assertEquals(List.of(0, 1), reasons.stream().map(EditReason::seq).toList());
	}

	public void testARecordWithoutSessionTakesTheFilesName() {
		String text = record("t1", "\"A.java\"", "[1,1]", "\"r\"", "skill").replace("\"session\":\"s\",", "");

		assertEquals("walkthrough-27", EditReason.read(text, "walkthrough-27").getFirst().session());
	}

	public void testABlankReasonIsPending() {
		assertTrue(EditReason.read(record("t1", "\"A.java\"", "[1,1]", "\"  \"", "agent")).getFirst().pending());
	}
}
