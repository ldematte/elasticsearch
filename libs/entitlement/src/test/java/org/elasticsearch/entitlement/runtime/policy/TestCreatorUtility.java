/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.policy;

import org.elasticsearch.entitlement.runtime.api.DelegateToCreator;
import org.elasticsearch.entitlement.runtime.api.Delegation;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

abstract class TestCreatorUtility {
    TestCreatorUtility() {}

    static TestCreatorUtility create() {
        return Delegation.instantiate(MethodHandles.lookup(), TestCreatorUtility.class, MethodType.methodType(void.class));
    }

    @DelegateToCreator
    protected Class<?> requestingClass(PolicyCheckerImpl checker) {
        return checker.requestingClass(TestCreatorUtility.class);
    }

    @DelegateToCreator
    protected <T> T withCallback(Supplier<T> callback) {
        return callback.get();
    }

    protected Class<?> undelegatedRequestingClass(PolicyCheckerImpl checker) {
        return checker.requestingClass(TestCreatorUtility.class);
    }
}
