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
import org.elasticsearch.entitlement.runtime.delegation.DelegationRegistry;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRuntime;
import org.elasticsearch.entitlement.runtime.policy.PolicyCheckerImpl;
import org.elasticsearch.entitlement.runtime.policy.PolicyManager;
import org.elasticsearch.entitlement.runtime.policy.entitlements.ExitVMEntitlement;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.benchmark.entitlement.EntitlementCheckBenchmark.EMPTY_PATH_LOOKUP;
import static org.elasticsearch.benchmark.entitlement.EntitlementCheckBenchmark.isClasspathSystemClass;
import static org.elasticsearch.benchmark.entitlement.EntitlementCheckBenchmark.pluginPolicies;
import static org.elasticsearch.benchmark.entitlement.EntitlementCheckBenchmark.scopeResolver;
import static org.elasticsearch.benchmark.entitlement.EntitlementCheckBenchmark.serverPolicy;

/**
 * Measures entitlement checks that pass and involve delegation. This class acts as the plugin, and the classes it calls act
 * as server code.
 * <ul>
 *     <li>{@link #unannotatedServerCaller}: server code without annotations. Its check looks up whether it has annotated
 *     methods. The benchmark finds server code with the scope resolver, so this does not measure the cost in production.</li>
 *     <li>{@link #callerMethod}: a {@link org.elasticsearch.entitlement.runtime.api.DelegateToCaller} method. It walks the
 *     stack to find its caller.</li>
 *     <li>{@link #creatorMethod}: a {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator} method. It sets its
 *     creator's identity while it runs.</li>
 * </ul>
 * Run it on each Java version that has its own
 * {@link org.elasticsearch.entitlement.runtime.delegation.DelegationHolder} implementation, and compare.
 */
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DelegationCheckBenchmark {

    static {
        BenchmarkLogging.configure();
    }

    private PolicyCheckerImpl checker;
    private BenchmarkCreatorUtility creatorUtility;
    private DelegationRuntime.Support previousSupport;

    @Setup
    public void setup() {
        var policyManager = new ClasspathDelegatingPolicyManager();
        checker = new PolicyCheckerImpl(Set.of(), PolicyManager.class.getModule(), policyManager, EMPTY_PATH_LOOKUP);
        previousSupport = DelegationRuntime.support();
        DelegationRuntime.install(policyManager.delegationSupport());
        creatorUtility = BenchmarkCreatorUtility.create();
    }

    @TearDown
    public void tearDown() {
        DelegationRuntime.install(previousSupport);
    }

    @Benchmark
    public void unannotatedServerCaller() {
        checker.checkEntitlementPresent(EntitlementCheckBenchmark.ServerCaller.class, ExitVMEntitlement.class);
    }

    @Benchmark
    public void callerMethod() {
        BenchmarkCallerUtility.check(checker);
    }

    @Benchmark
    public void creatorMethod() {
        creatorUtility.check(checker);
    }

    /**
     * The same as {@link EntitlementCheckBenchmark.ClasspathPolicyManager}, with delegation turned on. Without modules, it
     * identifies the entitlement library's classes by package, and server classes with the scope resolver, as tests do.
     * It does not extend {@code ClasspathPolicyManager}, because that class must only use the constructor that predates
     * delegation.
     */
    private static class ClasspathDelegatingPolicyManager extends PolicyManager {
        ClasspathDelegatingPolicyManager() {
            super(
                serverPolicy(),
                pluginPolicies(),
                Map.of(),
                scopeResolver(type -> type.getNestHost() == DelegationCheckBenchmark.class),
                name -> List.of(),
                EMPTY_PATH_LOOKUP,
                DelegationRegistry.load(DelegationCheckBenchmark.class.getClassLoader())
            );
        }

        @Override
        protected boolean isTrustedSystemClass(Class<?> requestingClass) {
            return isClasspathSystemClass(requestingClass);
        }

        @Override
        protected boolean isEntitlementClass(Class<?> type) {
            return type.getPackageName().startsWith("org.elasticsearch.entitlement");
        }

        @Override
        protected boolean canDelegate(Class<?> type) {
            return resolveScope(type).kind() == ComponentKind.SERVER;
        }
    }
}
