package com.fsodomka.structurediff;

import com.fsodomka.structurediff.MemberLinks.Link;
import com.fsodomka.structurediff.MemberLinks.Links;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class MemberLinksTest extends BasePlatformTestCase {
	private PsiFile money;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		money = myFixture.addFileToProject("p/Money.java", """
				package p;

				public class Money {
					int scale;

					public int round(int cents) { return cents / scale + helper() + round(0) + String.valueOf(cents).length(); }

					int helper() { return scale; }

					int caller() { return round(1) + round(2); }
				}
				""");
		myFixture.addFileToProject("p/Invoice.java", """
				package p;

				class Invoice {
					int total(Money m) { return m.round(1) + m.round(2); }
				}
				""");
		myFixture.addFileToProject("q/Checkout.java", """
				package q;

				import p.Money;

				class Checkout {
					int pay(Money m) { return m.round(3); }
				}
				""");
	}

	private PsiClass moneyClass() {
		return ((PsiJavaFile) money).getClasses()[0];
	}

	private Links links(PsiElement element) {
		return ReadAction.compute(() -> MemberLinks.links(getProject(), element));
	}

	private Links round() {
		return links(ReadAction.compute(() -> moneyClass().findMethodsByName("round", false)[0]));
	}

	private static List<String> describe(List<Link> links) {
		return links.stream().map(l -> l.file().getName() + " " + String.join(" > ", l.keys()) + " x" + l.count()
				+ (l.internal() ? " internal" : " external")).toList();
	}

	public void testCallersAreGroupedByMemberInternalFirst() {
		assertEquals(List.of(
				"Money.java Money > caller(): int x2 internal",
				"Invoice.java Invoice > total(Money): int x2 external",
				"Checkout.java Checkout > pay(Money): int x1 external"), describe(round().callers()));
		assertFalse(round().callersCapped());
	}

	public void testCalleesAreProjectMembersOutsideTheMember() {
		// the JDK's String is not in the project; the recursive call and the parameter are the member's own
		assertEquals(List.of(
				"Money.java Money > scale: int x1 internal",
				"Money.java Money > helper(): int x1 internal"), describe(round().callees()));
	}

	public void testCalleesInOtherFilesAreExternal() {
		PsiElement total = ReadAction.compute(() -> JavaPsiFacade.getInstance(getProject()).findClass("p.Invoice", GlobalSearchScope.allScope(getProject())).getMethods()[0]);
		assertEquals(List.of("Money.java Money > round(int): int x2 external"),
				describe(links(total).callees().stream().filter(l -> !l.keys().equals(List.of("Money"))).toList()));
	}

	public void testClassCalleesExcludeItsOwnMembers() {
		assertEquals(List.of(), describe(links(moneyClass()).callees()));
	}

	public void testClassCallersSkipImports() {
		// Checkout's import of Money is skipped, its parameter type is not
		assertEquals(List.of(
				"Invoice.java Invoice > total(Money): int x1 external",
				"Checkout.java Checkout > pay(Money): int x1 external"), describe(links(moneyClass()).callers()));
	}

	public void testCallersStopAtTheCap() {
		StringBuilder calls = new StringBuilder();
		for (int i = 0; i <= MemberLinks.CAP; i++) {
			calls.append("m.round(").append(i).append(");\n");
		}
		myFixture.addFileToProject("p/Many.java", "package p;\nclass Many {\n void all(Money m) {\n" + calls + "}\n}\n");
		Links links = round();
		assertTrue(links.callersCapped());
		assertEquals(MemberLinks.CAP, links.callers().stream().mapToInt(Link::count).sum());
	}

	public void testCalleesStopAtTheResolveCap() {
		StringBuilder calls = new StringBuilder();
		for (int i = 0; i <= MemberLinks.RESOLVE_CAP; i++) {
			calls.append("m.helper();\n");
		}
		myFixture.addFileToProject("p/Busy.java", "package p;\nclass Busy {\n void all(Money m) {\n" + calls + "}\n}\n");
		PsiElement all = ReadAction.compute(() -> JavaPsiFacade.getInstance(getProject()).findClass("p.Busy", GlobalSearchScope.allScope(getProject())).getMethods()[0]);
		Links links = links(all);
		assertTrue(links.calleesCapped());
		assertTrue(links.callees().stream().mapToInt(Link::count).sum() < MemberLinks.RESOLVE_CAP);
		assertFalse(round().calleesCapped());
	}

	public void testImportNamesAreWholeWords() {
		for (String name : List.of("PsiImportStatementImpl", "PsiImportStaticReferenceElementImpl", "KtImportDirective", "ES6ImportDeclaration",
				"PyFromImportStatementImpl", "GoImportSpecImpl", "JSImport", "Import")) {
			assertTrue(name, MemberLinks.isImportName(name));
		}
		for (String name : List.of("PsiMethodCallExpressionImpl", "ImportantNode", "ImporterCall", "ReimportedRef", "PsiImports")) {
			assertFalse(name, MemberLinks.isImportName(name));
		}
	}
}
