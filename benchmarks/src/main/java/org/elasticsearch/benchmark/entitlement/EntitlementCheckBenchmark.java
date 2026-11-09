/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.entitlement;

import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.entitlement.runtime.policy.PathLookup;
import org.elasticsearch.entitlement.runtime.policy.Policy;
import org.elasticsearch.entitlement.runtime.policy.PolicyCheckerImpl;
import org.elasticsearch.entitlement.runtime.policy.PolicyManager;
import org.elasticsearch.entitlement.runtime.policy.PolicyManager.PolicyScope;
import org.elasticsearch.entitlement.runtime.policy.Scope;
import org.elasticsearch.entitlement.runtime.policy.entitlements.ExitVMEntitlement;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ALL_UNNAMED;

/**
 * Measures an entitlement check that passes, for code that does not delegate entitlements. Almost every check is like this.
 * <p>
 * The benchmark only uses API that predates delegation, so it can also run on code without delegation. Comparing the two
 * runs gives the cost that delegation adds to every check.
 * <p>
 * Benchmarks do not run with modules, so {@link #serverCaller} is not in the server layer, and may not delegate. Its check
 * stops there, before the lookup of annotated methods. {@link DelegationCheckBenchmark} measures that lookup.
 */
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
public class EntitlementCheckBenchmark {

    static {
        BenchmarkLogging.configure();
    }

    static final String PLUGIN_NAME = "bench-plugin";

    static final PathLookup EMPTY_PATH_LOOKUP = new PathLookup() {
        @Override
        public Path pidFile() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<Path> getBaseDirPaths(BaseDir baseDir) {
            return Stream.empty();
        }

        @Override
        public Stream<Path> resolveSettingPaths(BaseDir baseDir, String settingName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isPathOnDefaultFilesystem(Path path) {
            return true;
        }
    };

    private PolicyCheckerImpl checker;

    @Setup
    public void setup() {
        checker = new PolicyCheckerImpl(
            Set.of(),
            PolicyManager.class.getModule(),
            new ClasspathPolicyManager(scopeResolver(type -> type == PluginCaller.class)),
            EMPTY_PATH_LOOKUP
        );
    }

    @Benchmark
    public void pluginCaller() {
        checker.checkEntitlementPresent(PluginCaller.class, ExitVMEntitlement.class);
    }

    @Benchmark
    public void serverCaller() {
        checker.checkEntitlementPresent(ServerCaller.class, ExitVMEntitlement.class);
    }

    static Policy serverPolicy() {
        return new Policy("server", List.of(new Scope(ALL_UNNAMED, List.of(new ExitVMEntitlement()))));
    }

    static Map<String, Policy> pluginPolicies() {
        return Map.of(PLUGIN_NAME, new Policy(PLUGIN_NAME, List.of(new Scope(ALL_UNNAMED, List.of(new ExitVMEntitlement())))));
    }

    static Function<Class<?>, PolicyScope> scopeResolver(Predicate<Class<?>> isPlugin) {
        return type -> isPlugin.test(type) ? PolicyScope.plugin(PLUGIN_NAME, ALL_UNNAMED) : PolicyScope.server(ALL_UNNAMED);
    }

    /**
     * Like tests, benchmarks do not run with modules, so JDK classes are identified by their class loader instead of their
     * module. This costs differently from production, but different benchmark runs can still be compared.
     */
    static boolean isClasspathSystemClass(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    /**
     * Identifies JDK classes with {@link #isClasspathSystemClass}.
     */
    static class ClasspathPolicyManager extends PolicyManager {
        ClasspathPolicyManager(Function<Class<?>, PolicyScope> scopeResolver) {
            super(serverPolicy(), pluginPolicies(), Map.of(), scopeResolver, name -> List.of(), EMPTY_PATH_LOOKUP);
        }

        @Override
        protected boolean isTrustedSystemClass(Class<?> requestingClass) {
            return isClasspathSystemClass(requestingClass);
        }
    }

    static final class PluginCaller {}

    static final class ServerCaller {}
}
