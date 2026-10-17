package com.fsodomka.structurediff.review;

import com.intellij.openapi.util.io.FileUtil;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class SarifReaderTest {
	private static final String ROOT = "/repo";
	private static final String SARIF = """
			{"version": "2.1.0", "runs": [{"results": [
			  {"ruleId": "SECURITY", "level": "note",
			   "message": {"text": "Token TTL is long.\\nSecond line.\\n\\nFix: Lower the TTL."},
			   "locations": [{"physicalLocation": {
			     "artifactLocation": {"uri": "src/A.java", "uriBaseId": "SRCROOT"},
			     "region": {"startLine": 27, "startColumn": 2, "charOffset": 959, "charLength": 5, "snippet": {"text": "a\\r\\nb"}}}}],
			   "partialFingerprints": {"equalIndicator/v1": "abc"},
			   "properties": {"ideaSeverity": "WEAK WARNING"}},
			  {"ruleId": "CORRECTNESS", "level": "error", "message": {"text": "No fix."},
			   "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/B.java"}}}]},
			  {"ruleId": "NOWHERE", "message": {"text": "No location."}}
			]}]}
			""";

	@Test
	public void readsFields() {
		List<Finding> findings = SarifReader.read(SARIF, ROOT);
		assertEquals(2, findings.size());

		Finding first = findings.getFirst();
		assertEquals("/repo|SECURITY:src/A.java:27:a\nb", first.fingerprint());
		assertEquals("SECURITY", first.ruleId());
		assertEquals(ReviewSeverity.WEAK_WARNING, first.severity());
		assertEquals("Token TTL is long.\nSecond line.", first.message());
		assertEquals("Token TTL is long.", first.title());
		assertEquals("Lower the TTL.", first.fix());
		assertEquals("src/A.java", first.path());
		assertEquals(27, first.line());
		assertEquals(2, first.column());
		assertEquals(959, first.offset());
		assertEquals(5, first.length());
		assertEquals("a\nb", first.snippet());
	}

	@Test
	public void missingFieldsDefault() {
		Finding second = SarifReader.read(SARIF, ROOT).get(1);
		assertEquals(ReviewSeverity.ERROR, second.severity());
		assertNull(second.fix());
		assertNull(second.snippet());
		assertEquals(-1, second.line());
		assertEquals(-1, second.offset());
		assertEquals("/repo|CORRECTNESS:src/B.java:-1", second.fingerprint());
	}

	@Test
	public void nonIntegerNumbersCountAsMissing() {
		String odd = SARIF.replace("\"startLine\": 27", "\"startLine\": 4294967297").replace("\"startColumn\": 2", "\"startColumn\": 1.5");
		Finding first = SarifReader.read(odd, ROOT).getFirst();
		assertEquals(-1, first.line());
		assertEquals(-1, first.column());
		assertEquals(959, first.offset());
	}

	@Test
	public void fingerprintIgnoresFindingNumberAndMessage() {
		String later = SARIF.replace("\"SECURITY\"", "\"SECURITY-7\"").replace("Token TTL is long.", "#7 [CONFIRMED] TTL too long.");
		assertEquals(SarifReader.read(SARIF, ROOT).getFirst().fingerprint(), SarifReader.read(later, ROOT).getFirst().fingerprint());
	}

	@Test
	public void fingerprintAndFindingDifferPerRoot() {
		Finding inOne = SarifReader.read(SARIF, "/one").getFirst();
		Finding inTwo = SarifReader.read(SARIF, "/two").getFirst();
		assertNotEquals(inOne.fingerprint(), inTwo.fingerprint());
		assertNotEquals(inOne, inTwo);
	}

	@Test
	public void decodesUris() {
		assertEquals("src/My File.java", SarifReader.path("src/My%20File.java"));
		Path absolute = Path.of(System.getProperty("java.io.tmpdir"), "A b.java").toAbsolutePath();
		assertEquals(FileUtil.toSystemIndependentName(absolute.toString()), SarifReader.path(absolute.toUri().toString()));
		// not URIs, or not file URIs: kept
		assertEquals("src/A b.java", SarifReader.path("src/A b.java"));
		assertEquals("C:/src/A.java", SarifReader.path("C:/src/A.java"));
		assertEquals("https://example.com/A.java", SarifReader.path("https://example.com/A.java"));
	}

	@Test
	public void reviewFileNames() {
		assertEquals("20261015-141239-review", ReviewFindingsService.reviewName("20261015-141239-review.sarif"));
		assertEquals("20261015-141239-review", ReviewFindingsService.reviewName("20261015-141239-review.sarif.json"));
		assertNull(ReviewFindingsService.reviewName("20261015-141239-review.json"));
		assertNull(ReviewFindingsService.reviewName(".sarif"));
	}

	@Test
	public void severityFallsBackToLevel() {
		assertEquals(ReviewSeverity.WARNING, ReviewSeverity.of(null, null));
		assertEquals(ReviewSeverity.WARNING, ReviewSeverity.of("TYPO", "warning"));
		assertEquals(ReviewSeverity.WEAK_WARNING, ReviewSeverity.of(null, "note"));
		assertEquals(ReviewSeverity.ERROR, ReviewSeverity.of("ERROR", "note"));
	}

	@Test
	public void rankTellsCriticalFromHigh() {
		assertEquals(0, ReviewSeverity.rank("Critical", ReviewSeverity.ERROR));
		assertEquals(1, ReviewSeverity.rank("High", ReviewSeverity.ERROR));
		assertEquals(1, ReviewSeverity.rank(null, ReviewSeverity.ERROR));
		assertEquals(2, ReviewSeverity.rank("TYPO", ReviewSeverity.WARNING));
		assertEquals(3, ReviewSeverity.rank(null, ReviewSeverity.WEAK_WARNING));
		String critical = SARIF.replace("\"ideaSeverity\": \"WEAK WARNING\"", "\"ideaSeverity\": \"ERROR\", \"qodanaSeverity\": \"Critical\"");
		assertEquals(0, SarifReader.read(critical, ROOT).getFirst().rank());
		assertEquals(1, SarifReader.read(critical, ROOT).get(1).rank());
	}

	@Test
	public void severityCountsText() {
		assertEquals("1 error", ReviewSeverity.ERROR.countText(1));
		assertEquals("1 error · 2 weak warnings", ReviewSeverity.countsText(
				List.of(ReviewSeverity.WEAK_WARNING, ReviewSeverity.ERROR, ReviewSeverity.WEAK_WARNING)));
		assertEquals("", ReviewSeverity.countsText(List.of()));
	}
}
