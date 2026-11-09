/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.api;

import org.elasticsearch.core.Strings;

/**
 * Records which class created an instance of a class with {@link DelegateToCreator} methods. While one of those methods runs,
 * entitlement checks in the code of that class use the entitlements of the creator. Only {@link Delegation#capture()}
 * creates identities.
 */
public final class EntitlementIdentity {
    private final Class<?> creator;
    private final Class<?> base;

    EntitlementIdentity(Class<?> creator, Class<?> base) {
        this.creator = creator;
        this.base = base;
    }

    /**
     * @return the class that called the factory method of the instance
     */
    public Class<?> creator() {
        return creator;
    }

    /**
     * @return the class that declares the {@link DelegateToCreator} methods
     */
    public Class<?> base() {
        return base;
    }

    @Override
    public String toString() {
        return Strings.format("EntitlementIdentity[creator=%s, base=%s]", creator.getName(), base.getName());
    }
}
