/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.entitlement;

import org.elasticsearch.entitlement.runtime.api.DelegateToCaller;
import org.elasticsearch.entitlement.runtime.policy.PolicyCheckerImpl;
import org.elasticsearch.entitlement.runtime.policy.entitlements.ExitVMEntitlement;

/**
 * Server code with a {@link DelegateToCaller} method. It is what
 * {@link DelegationCheckBenchmark#callerMethod} measures.
 */
final class BenchmarkCallerUtility {
    private BenchmarkCallerUtility() {}

    @DelegateToCaller
    static void check(PolicyCheckerImpl checker) {
        checker.checkEntitlementPresent(BenchmarkCallerUtility.class, ExitVMEntitlement.class);
    }
}
