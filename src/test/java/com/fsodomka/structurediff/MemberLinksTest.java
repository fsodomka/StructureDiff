package com.fsodomka.structurediff;

import com.fsodomka.structurediff.MemberLinks.Link;
import com.fsodomka.structurediff.MemberLinks.Links;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

	/** Off the EDT like UsagesController: Kotlin's K2 structure view refuses to compute labels on it. */
	private Links links(PsiElement element) throws Exception {
		return ApplicationManager.getApplication().executeOnPooledThread(() -> ApplicationManager.getApplication().runReadAction(
				(Computable<Links>) () -> MemberLinks.links(getProject(), element))).get(30, TimeUnit.SECONDS);
	}

	private Links round() throws Exception {
		return links(ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> moneyClass().findMethodsByName("round", false)[0]));
	}

	private static List<String> describe(List<Link> links) {
		return links.stream().map(l -> l.file().getName() + " " + String.join(" > ", l.keys()) + " x" + l.count()
				+ (l.internal() ? " internal" : " external")).toList();
	}

	public void testCallersAreGroupedByMemberInternalFirst() throws Exception {
		assertEquals(List.of(
				"Money.java Money > caller(): int x2 internal",
				"Invoice.java Invoice > total(Money): int x2 external",
				"Checkout.java Checkout > pay(Money): int x1 external"), describe(round().callers()));
		assertFalse(round().callersCapped());
	}

	public void testCalleesAreProjectMembersOutsideTheMember() throws Exception {
		// the JDK's String is not in the project; the recursive call and the parameter are the member's own
		assertEquals(List.of(
				"Money.java Money > scale: int x1 internal",
				"Money.java Money > helper(): int x1 internal"), describe(round().callees()));
	}

	public void testCalleesInOtherFilesAreExternal() throws Exception {
		PsiElement total = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> JavaPsiFacade.getInstance(getProject()).findClass("p.Invoice", GlobalSearchScope.allScope(getProject())).getMethods()[0]);
		assertEquals(List.of("Money.java Money > round(int): int x2 external"),
				describe(links(total).callees().stream().filter(l -> !l.keys().equals(List.of("Money"))).toList()));
	}

	public void testClassCalleesExcludeItsOwnMembers() throws Exception {
		assertEquals(List.of(), describe(links(moneyClass()).callees()));
	}

	public void testClassCallersSkipImports() throws Exception {
		// Checkout's import of Money is skipped, its parameter type is not
		assertEquals(List.of(
				"Invoice.java Invoice > total(Money): int x1 external",
				"Checkout.java Checkout > pay(Money): int x1 external"), describe(links(moneyClass()).callers()));
	}

	public void testCallersStopAtTheCap() throws Exception {
		StringBuilder calls = new StringBuilder();
		for (int i = 0; i <= MemberLinks.CAP; i++) {
			calls.append("m.round(").append(i).append(");\n");
		}
		myFixture.addFileToProject("p/Many.java", "package p;\nclass Many {\n void all(Money m) {\n" + calls + "}\n}\n");
		Links links = round();
		assertTrue(links.callersCapped());
		assertEquals(MemberLinks.CAP, links.callers().stream().mapToInt(Link::count).sum());
	}

	public void testCalleesStopAtTheResolveCap() throws Exception {
		StringBuilder calls = new StringBuilder();
		for (int i = 0; i <= MemberLinks.RESOLVE_CAP; i++) {
			calls.append("m.helper();\n");
		}
		myFixture.addFileToProject("p/Busy.java", "package p;\nclass Busy {\n void all(Money m) {\n" + calls + "}\n}\n");
		PsiElement all = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> JavaPsiFacade.getInstance(getProject()).findClass("p.Busy", GlobalSearchScope.allScope(getProject())).getMethods()[0]);
		Links links = links(all);
		assertTrue(links.calleesCapped());
		assertTrue(links.callees().stream().mapToInt(Link::count).sum() < MemberLinks.RESOLVE_CAP);
		assertFalse(round().calleesCapped());
	}

	public void testCalleeConstArrowFunctionIsItsOwnMember() throws Exception {
		// TypeScript's structure view shows a const's arrow function as the member, and it starts after the const's name
		PsiFile menu = myFixture.addFileToProject("menu.ts", """
				export function menu(user: string) {
					const open = (event: number) => {
						return event;
					};
					const use = () => open(user.length);
				}
				""");
		PsiElement use = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> PsiTreeUtil.findFirstParent(
				menu.findElementAt(menu.getText().indexOf("open(user")), e -> e.getText().startsWith("() => open")));
		assertNotNull(use);
		List<String> callees = describe(links(use).callees());
		assertEquals(callees.toString(), 2, callees.size());
		assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.startsWith("menu.ts menu") && c.contains(" > open")));
	}

	public void testCalleeLambdaPropertyIsItsOwnMember() throws Exception {
		// Kotlin's structure view shows a lambda property as the property, which holds the name
		PsiFile menu = myFixture.addFileToProject("Menu.kt", """
				class Menu(val user: String) {
					val open = { event: Int -> event }
					val use = { open(user.length) }
				}
				""");
		PsiElement use = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> PsiTreeUtil.findFirstParent(
				menu.findElementAt(menu.getText().indexOf("open(user")), e -> e.getText().startsWith("val use")));
		assertNotNull(use);
		List<String> callees = describe(links(use).callees());
		assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.startsWith("Menu.kt Menu > open") && c.endsWith(" internal")));
	}

	public void testCalleeConstructorPropertyIsTheProperty() throws Exception {
		// Kotlin's structure view lists the primary constructor before its val parameters, and both contain them
		PsiFile vec = myFixture.addFileToProject("Vec.kt", """
				class Vec(val x: Int, val y: Int) {
					operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
				}
				""");
		PsiElement plus = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> PsiTreeUtil.findFirstParent(
				vec.findElementAt(vec.getText().indexOf("plus")), e -> e.getText().startsWith("operator fun plus")));
		assertNotNull(plus);
		List<String> callees = describe(links(plus).callees());
		assertEquals(callees.toString(), 3, callees.size());
		assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.startsWith("Vec.kt Vec > constructor") && c.endsWith(" x1 internal")));
		assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.startsWith("Vec.kt Vec > x") && c.endsWith(" x2 internal")));
		assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.startsWith("Vec.kt Vec > y") && c.endsWith(" x2 internal")));
	}

	public void testCalleeDestructuringCallsComponents() throws Exception {
		// each destructuring entry resolves to itself first, then to its componentN
		PsiFile vec = myFixture.addFileToProject("Pair.kt", """
				class Pair(val x: Int, val y: Int) {
					operator fun component1() = x
					operator fun component2() = y
				}

				fun sum(p: Pair): Int {
					val (a, b) = p
					return a + b
				}
				""");
		PsiElement sum = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> PsiTreeUtil.findFirstParent(
				vec.findElementAt(vec.getText().indexOf("fun sum")), e -> e.getText().startsWith("fun sum")));
		assertNotNull(sum);
		List<String> callees = describe(links(sum).callees());
		assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.startsWith("Pair.kt Pair > component1") && c.endsWith(" x1 internal")));
		assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.startsWith("Pair.kt Pair > component2") && c.endsWith(" x1 internal")));
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
