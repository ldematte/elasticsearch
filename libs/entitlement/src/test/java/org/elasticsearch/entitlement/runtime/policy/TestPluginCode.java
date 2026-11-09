/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.policy;

import org.elasticsearch.entitlement.runtime.api.DelegateToCaller;

import java.util.function.Supplier;
import java.util.stream.Stream;

final class TestPluginCode {
    private TestPluginCode() {}

    static Class<?> callCallerUtility(PolicyCheckerImpl checker) {
        return TestCallerUtility.requestingClass(checker);
    }

    static Class<?> callCallerUtilityNested(PolicyCheckerImpl checker) {
        return TestCallerUtility.nestedRequestingClass(checker);
    }

    static Class<?> callCallerUtilityThroughStream(PolicyCheckerImpl checker) {
        return Stream.of(checker).map(TestCallerUtility::requestingClass).findFirst().orElseThrow();
    }

    static Class<?> callCallerUtilityUndelegated(PolicyCheckerImpl checker) {
        return TestCallerUtility.undelegatedRequestingClass(checker);
    }

    static Class<?> callServerThroughCallerUtility(PolicyCheckerImpl checker) {
        return TestServerCode.callCallerUtility(checker);
    }

    static Class<?> callCallerUtilityThroughAnotherCallerUtility(PolicyCheckerImpl checker) {
        return TestServerCode.callCallerUtilityDelegated(checker);
    }

    static void checkExitVMThroughCallerUtility(PolicyCheckerImpl checker) {
        TestCallerUtility.checkExitVM(checker);
    }

    static TestCreatorUtility createCreatorUtility() {
        return TestCreatorUtility.create();
    }

    static TestCreatorUtility createCreatorUtilityThroughMethodReference() {
        Supplier<TestCreatorUtility> factory = TestCreatorUtility::create;
        return factory.get();
    }

    static TestCreatorUtility createCreatorUtilityThroughCallerUtility() {
        return TestCallerUtility.createCreatorUtility();
    }

    static Class<?> requestingClass(PolicyCheckerImpl checker) {
        return checker.requestingClass(TestPluginCode.class);
    }

    static final class Annotated {
        @DelegateToCaller
        static Class<?> requestingClass(PolicyCheckerImpl checker) {
            return checker.requestingClass(Annotated.class);
        }
    }
}
