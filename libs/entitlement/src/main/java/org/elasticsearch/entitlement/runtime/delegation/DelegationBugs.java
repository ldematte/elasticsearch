/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.delegation;

import org.elasticsearch.core.Strings;

/**
 * Creates the exceptions thrown when {@link org.elasticsearch.entitlement.runtime.api.DelegateToCaller} or
 * {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator} are used wrongly. These are bugs in the code that uses
 * the annotations, not missing entitlements. Each message says which code to fix, and how.
 */
public final class DelegationBugs {
    private DelegationBugs() {}

    public static IllegalStateException unboundCreatorMethod(Class<?> callerClass, Class<?> base) {
        return bug(
            callerClass,
            Strings.format(
                "a @DelegateToCreator method ran on an instance of [%s] that its factory did not create, and performed a sensitive"
                    + " operation",
                base.getName()
            ),
            Strings.format("the code that created this instance of [%s]", base.getName()),
            Strings.format("creating the instance through the factory of [%s], instead of as a mock, a spy or a subclass", base.getName())
        );
    }

    public static IllegalStateException creatorNotYetCaptured(Class<?> base) {
        return bug(
            base,
            "a @DelegateToCreator method was called from a constructor, before the instance recorded its creator",
            Strings.format("[%s]", base.getName()),
            "not calling @DelegateToCreator methods from constructors"
        );
    }

    public static IllegalStateException createdByDelegatingCode(Class<?> creatorClass, Class<?> base) {
        return bug(
            creatorClass,
            Strings.format(
                "a @DelegateToCaller or @DelegateToCreator method creates an instance of [%s]. It is not clear whether the instance"
                    + " should act for [%s] or for its caller",
                base.getName(),
                creatorClass.getName()
            ),
            Strings.format("[%s]", creatorClass.getName()),
            Strings.format(
                "creating the instance outside annotated methods, so that it acts for [%s], or by letting the caller create it,"
                    + " so that it acts for the caller",
                creatorClass.getName()
            )
        );
    }

    private static IllegalStateException bug(Class<?> where, String what, String culprit, String fix) {
        return new IllegalStateException(
            Strings.format(
                "Entitlement delegation bug in [%s]: %s. This is a bug in %s, not a missing entitlement. Fix it by %s.",
                where.getName(),
                what,
                culprit,
                fix
            )
        );
    }
}
