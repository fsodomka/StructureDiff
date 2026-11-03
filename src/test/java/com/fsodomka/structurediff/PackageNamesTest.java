package com.fsodomka.structurediff;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.util.Map;

public class PackageNamesTest extends BasePlatformTestCase {
	public void testRelativePackagesDropTheSharedPrefix() {
		Map<String, String> relative = PackageNames.relativePackages(Map.of(
				"a", "com.acme.app", "b", "com.acme.app.history", "c", "META-INF", "d", ""), Map.of());
		assertEquals(Map.of("a", "", "b", "history", "c", "META-INF", "d", ""), relative);
	}

	public void testRelativePackagesOfUnrelatedPackagesKeepTheirNames() {
		assertEquals(Map.of("a", "com.acme", "b", "org.other"),
				PackageNames.relativePackages(Map.of("a", "com.acme", "b", "org.other"), Map.of()));
	}

	public void testRelativePackagesPerRootAndSeparator() throws IOException {
		VirtualFile one = myFixture.getTempDirFixture().findOrCreateDir("one");
		VirtualFile two = myFixture.getTempDirFixture().findOrCreateDir("two");
		Map<String, String> relative = PackageNames.relativePackages(
				Map.of("a", "com.acme.rest.model", "b", "com.acme.services", "c", "admin/basket", "d", "admin/converter",
						"e", "org.other.impl", "f", "org.other.model.notebook"),
				Map.of("a", one, "b", one, "c", one, "d", one, "e", two, "f", two));
		assertEquals(Map.of("a", "rest.model", "b", "services", "c", "basket", "d", "converter",
				"e", "impl", "f", "model.notebook"), relative);
	}

	public void testConventionalPackage() {
		assertEquals("com.acme.impl", PackageNames.conventionalPackage("src/main/java/com/acme/impl/A.java"));
		assertEquals("com.acme", PackageNames.conventionalPackage("lib/src/test/kotlin/com/acme/ATest.kt"));
		assertEquals("admin/basket", PackageNames.conventionalPackage("src/main/js/admin/basket/edit.tsx"));
		assertEquals("", PackageNames.conventionalPackage("src/main/js/index.ts"));
		assertNull(PackageNames.conventionalPackage("templates/marimo.html"));
		assertNull(PackageNames.conventionalPackage("README.md"));
	}
}
