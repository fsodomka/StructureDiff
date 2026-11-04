package com.fsodomka.structurediff.review;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static com.fsodomka.structurediff.PropertyChecks.forAll;
import static org.junit.Assert.assertEquals;

/**
 * Properties of {@link SarifReader#path}, checked with jetCheck: it undoes the encoding of a relative URI reference and of a
 * {@code file:} URI. Names mix characters that URIs escape (space, {@code #}, {@code %}, non-ASCII) with plain ones.
 */
public class SarifPathProperties {
	/** Never ends in a space or dot, which Windows does not keep in a file name. */
	private static final Generator<String> NAME = Generator.stringsOf(IntDistribution.uniform(0, 6), Generator.charsFrom("ab #%é中_"))
			.map(s -> s + "x");
	private static final Generator<List<String>> SEGMENTS = Generator.listsOf(IntDistribution.uniform(1, 4), NAME);

	private static URI uri(String scheme, String path) {
		try {
			return new URI(scheme, null, path, null);
		} catch (URISyntaxException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	public void encodedRelativePathsAreDecoded() {
		forAll(SEGMENTS, segments -> {
			String path = String.join("/", segments);
			URI uri = uri(null, path);
			// SARIF writers escape non-ASCII characters or keep them
			assertEquals(uri.toString(), path, SarifReader.path(uri.toString()));
			assertEquals(uri.toASCIIString(), path, SarifReader.path(uri.toASCIIString()));
		});
	}

	@Test
	public void fileUrisBecomeAbsolutePaths() {
		Path base = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
		forAll(SEGMENTS, segments -> {
			Path file = base.resolve(String.join("/", segments));
			String expected = file.toString().replace('\\', '/');
			String uri = file.toUri().toASCIIString();
			assertEquals(uri, expected, SarifReader.path(uri));
			String upperScheme = "FILE" + uri.substring("file".length());
			assertEquals(upperScheme, expected, SarifReader.path(upperScheme));
		});
	}
}
