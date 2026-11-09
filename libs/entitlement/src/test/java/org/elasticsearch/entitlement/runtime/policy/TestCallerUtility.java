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
import org.elasticsearch.entitlement.runtime.policy.entitlements.ExitVMEntitlement;

final class TestCallerUtility {
    private TestCallerUtility() {}

    @DelegateToCaller
    static Class<?> requestingClass(PolicyCheckerImpl checker) {
        return helperRequestingClass(checker);
    }

    @DelegateToCaller
    static Class<?> nestedRequestingClass(PolicyCheckerImpl checker) {
        return Nested.requestingClass(checker);
    }

    @DelegateToCaller
    static void checkExitVM(PolicyCheckerImpl checker) {
        checker.checkEntitlementPresent(TestCallerUtility.class, ExitVMEntitlement.class);
    }

    @DelegateToCaller
    static TestCreatorUtility createCreatorUtility() {
        return TestCreatorUtility.create();
    }

    static Class<?> undelegatedRequestingClass(PolicyCheckerImpl checker) {
        return helperRequestingClass(checker);
    }

    private static Class<?> helperRequestingClass(PolicyCheckerImpl checker) {
        return checker.requestingClass(TestCallerUtility.class);
    }

    static final class Nested {
        static Class<?> requestingClass(PolicyCheckerImpl checker) {
            return checker.requestingClass(Nested.class);
        }
    }
}
