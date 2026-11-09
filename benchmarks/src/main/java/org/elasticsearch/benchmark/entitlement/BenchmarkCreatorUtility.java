/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.entitlement;

import org.elasticsearch.entitlement.runtime.api.DelegateToCreator;
import org.elasticsearch.entitlement.runtime.api.Delegation;
import org.elasticsearch.entitlement.runtime.policy.PolicyCheckerImpl;
import org.elasticsearch.entitlement.runtime.policy.entitlements.ExitVMEntitlement;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Server code with a {@link DelegateToCreator} method. It is what
 * {@link DelegationCheckBenchmark#creatorMethod} measures.
 */
abstract class BenchmarkCreatorUtility {
    BenchmarkCreatorUtility() {}

    static BenchmarkCreatorUtility create() {
        return Delegation.instantiate(MethodHandles.lookup(), BenchmarkCreatorUtility.class, MethodType.methodType(void.class));
    }

    @DelegateToCreator
    protected void check(PolicyCheckerImpl checker) {
        checker.checkEntitlementPresent(BenchmarkCreatorUtility.class, ExitVMEntitlement.class);
    }
}
