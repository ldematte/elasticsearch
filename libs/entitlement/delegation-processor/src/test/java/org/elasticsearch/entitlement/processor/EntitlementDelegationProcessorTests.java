/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.processor;

import org.elasticsearch.entitlement.runtime.api.DelegateToCaller;
import org.elasticsearch.entitlement.runtime.api.DelegateToCreator;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRegistry;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRuntime;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

public class EntitlementDelegationProcessorTests extends ESTestCase {

    private static final String CREATOR_CLASS = """
        package p;
        import org.elasticsearch.entitlement.runtime.api.DelegateToCreator;
        import org.elasticsearch.entitlement.runtime.api.Delegation;
        import java.io.IOException;
        import java.lang.invoke.MethodHandles;
        import java.lang.invoke.MethodType;
        public abstract class Utility {
            Utility(String name, int... sizes) {}
            public static Utility create(String name) {
                return Delegation.instantiate(MethodHandles.lookup(), Utility.class,
                    MethodType.methodType(void.class, String.class, int[].class), name, new int[0]);
            }
            @DelegateToCreator
            protected byte[] read(java.nio.file.Path path) throws IOException { return null; }
            @DelegateToCreator
            public <T extends Comparable<T>> void sort(java.util.List<T> list) {}
            protected void notDelegated() {}
        }
        """;

    public void testCreatorClassGeneratesDelegatingSubclassAndResource() throws IOException {
        var result = compile(Map.of("p.Utility", CREATOR_CLASS));
        assertEquals(List.of(), result.errors());

        String generated = Files.readString(result.sourceOutput().resolve("p/Utility$Delegating.java"));
        assertThat(generated, containsString("final class Utility$Delegating extends Utility"));
        assertThat(generated, containsString("Utility$Delegating(java.lang.String name, int... sizes)"));
        assertThat(
            generated,
            containsString("protected byte[] read(java.nio.file.Path path) throws java.io.IOException {\n        return ")
        );
        assertThat(generated, containsString("public <T extends java.lang.Comparable<T>> void sort(java.util.List<T> list) {"));
        assertThat(generated, containsString(".Delegation.run(this.entitlementCreator, () -> super.sort(list));"));
        assertFalse(generated.contains("notDelegated"));

        assertThat(
            result.resourceLines(),
            containsInAnyOrder(
                "CREATOR p.Utility p.Utility read (Ljava/nio/file/Path;)[B",
                "CREATOR p.Utility p.Utility sort (Ljava/util/List;)V"
            )
        );
    }

    public void testCallerMethodsAreListedWithTheirTopLevelClass() throws IOException {
        var result = compile(Map.of("p.Outer", """
            package p;
            import org.elasticsearch.entitlement.runtime.api.DelegateToCaller;
            public class Outer {
                @DelegateToCaller
                public static String read(String path, long offset) { return path; }
                static class Inner {
                    @DelegateToCaller
                    private void write(Object[][] data) {}
                }
            }
            """));
        assertEquals(List.of(), result.errors());
        assertThat(
            result.resourceLines(),
            containsInAnyOrder(
                "CALLER p.Outer p.Outer read (Ljava/lang/String;J)Ljava/lang/String;",
                "CALLER p.Outer p.Outer$Inner write ([[Ljava/lang/Object;)V"
            )
        );
    }

    public void testCreatorMethodMustBeOverridable() throws IOException {
        assertCreatorError("@DelegateToCreator private void m() {}", "must be overridable");
        assertCreatorError("@DelegateToCreator static void m() {}", "must be overridable");
        assertCreatorError("@DelegateToCreator protected final void m() {}", "must be overridable");
        assertCreatorError("@DelegateToCreator protected abstract void m();", "must have a body");
    }

    public void testCreatorMethodDeclaresAtMostOneCheckedException() throws IOException {
        assertCreatorError(
            "@DelegateToCreator protected void m() throws java.io.IOException, InterruptedException {}",
            "at most one checked exception"
        );
        assertCreatorError("@DelegateToCreator protected void m() throws Throwable {}", "checked exceptions that extend Exception");
    }

    public void testCallerMethodMustHaveABody() throws IOException {
        assertError("public abstract class C { @DelegateToCaller protected abstract void m(); }", "must have a body");
        assertError("public interface C { @DelegateToCaller void m(); }", "must have a body");
    }

    public void testCreatorClassMustOnlyBeInstantiableThroughItsFactory() throws IOException {
        assertError("public class C { C() {} @DelegateToCreator protected void m() {} }", "must be abstract");
        assertError("public abstract class C { public C() {} @DelegateToCreator protected void m() {} }", "must be package-private");
        assertError(
            "public abstract class C { C() throws java.io.IOException {} @DelegateToCreator protected void m() {} }",
            "cannot declare checked exceptions"
        );
        assertError("public abstract class C<T> { C() {} @DelegateToCreator protected void m() {} }", "cannot be generic");
        assertError(
            "public class C { abstract static class N { N() {} @DelegateToCreator protected void m() {} } }",
            "must be declared in a top-level class"
        );
        assertError("public interface C { @DelegateToCreator default void m() {} }", "must be declared in a top-level class");
    }

    public void testCreatorClassCannotBeExtended() throws IOException {
        var result = compile(
            Map.of("p.Utility", CREATOR_CLASS, "p.Sub", "package p; class Sub extends Utility { Sub() { super(\"\"); } }")
        );
        assertThat(result.errors(), hasItem(containsString("classes with @DelegateToCreator methods cannot be extended")));
    }

    public void testMethodCannotHaveBothAnnotations() throws IOException {
        assertCreatorError("@DelegateToCaller @DelegateToCreator protected void m() {}", "cannot be both");
    }

    public void testNamesMatchTheRuntime() {
        assertEquals(DelegationRegistry.RESOURCE_NAME, EntitlementDelegationProcessor.RESOURCE_NAME);
        assertEquals(DelegationRuntime.GENERATED_CLASS_SUFFIX, EntitlementDelegationProcessor.GENERATED_CLASS_SUFFIX);
        assertEquals(DelegateToCaller.class.getName(), EntitlementDelegationProcessor.DELEGATE_TO_CALLER);
        assertEquals(DelegateToCreator.class.getName(), EntitlementDelegationProcessor.DELEGATE_TO_CREATOR);
    }

    private void assertCreatorError(String member, String expectedError) throws IOException {
        assertError("public abstract class C { C() {} " + member + " }", expectedError);
    }

    private void assertError(String classBody, String expectedError) throws IOException {
        var result = compile(Map.of("p.C", """
            package p;
            import org.elasticsearch.entitlement.runtime.api.DelegateToCaller;
            import org.elasticsearch.entitlement.runtime.api.DelegateToCreator;
            """ + classBody));
        assertThat(result.errors(), hasItem(containsString(expectedError)));
    }

    private record CompilationResult(List<String> errors, Path sourceOutput, Path classOutput) {
        List<String> resourceLines() throws IOException {
            return Files.readAllLines(classOutput.resolve(EntitlementDelegationProcessor.RESOURCE_NAME));
        }
    }

    private CompilationResult compile(Map<String, String> sources) throws IOException {
        Path sourceOutput = createTempDir();
        Path classOutput = createTempDir();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> files = sources.entrySet().stream().<JavaFileObject>map(e -> new Source(e.getKey(), e.getValue())).toList();
        List<String> options = List.of(
            "-classpath",
            System.getProperty("java.class.path"),
            "-s",
            sourceOutput.toString(),
            "-d",
            classOutput.toString()
        );
        var task = compiler.getTask(null, null, diagnostics, options, null, files);
        task.setProcessors(List.of(new EntitlementDelegationProcessor()));
        task.call();
        List<String> errors = diagnostics.getDiagnostics()
            .stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();
        return new CompilationResult(errors, sourceOutput, classOutput);
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
