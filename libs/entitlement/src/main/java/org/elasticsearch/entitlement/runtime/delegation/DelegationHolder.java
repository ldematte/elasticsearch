/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.delegation;

import org.elasticsearch.core.CheckedSupplier;
import org.elasticsearch.entitlement.runtime.api.EntitlementIdentity;

/**
 * Holds the {@link EntitlementIdentity} of the {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator} method
 * that is running on this thread. {@link #call} sets it while an operation runs. Entitlement checks read it with
 * {@link #current()}. Only the thread that called {@link #call} sees it.
 * <p>
 * This version uses a {@link ThreadLocal}. On Java 25 and later, the multi-release jar uses another version, based on
 * {@code ScopedValue}.
 */
public final class DelegationHolder {
    private static final ThreadLocal<EntitlementIdentity> CURRENT = new ThreadLocal<>();

    private DelegationHolder() {}

    /**
     * @return the identity passed to the innermost {@link #call} running on this thread, or {@code null} if there is none
     */
    public static EntitlementIdentity current() {
        return CURRENT.get();
    }

    /**
     * Runs {@code op}. Until {@code op} finishes, {@link #current()} returns {@code identity} on this thread.
     */
    public static <T, X extends Exception> T call(EntitlementIdentity identity, CheckedSupplier<T, X> op) throws X {
        EntitlementIdentity previous = CURRENT.get();
        CURRENT.set(identity);
        try {
            return op.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
