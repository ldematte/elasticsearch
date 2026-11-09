/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.delegation;

/**
 * Connects the static {@link org.elasticsearch.entitlement.runtime.api.Delegation} methods, which generated code calls, to
 * the policy that checks entitlements. Until bootstrap installs a {@link Support}, for example in tests that do not bootstrap
 * entitlements, the {@code Delegation} methods check nothing.
 */
public final class DelegationRuntime {

    /**
     * The suffix of the name of the subclass that the processor generates for each class with
     * {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator} methods.
     */
    public static final String GENERATED_CLASS_SUFFIX = "$Delegating";

    /**
     * The checks that {@link org.elasticsearch.entitlement.runtime.api.Delegation} needs from the policy.
     */
    public interface Support {
        /**
         * @param generatedClass the generated {@code $Delegating} class whose instance is being constructed
         * @return the class whose entitlements the instance uses
         * @throws IllegalCallerException if {@code generatedClass} is not a generated {@code $Delegating} class, if its
         *                                superclass has no {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator}
         *                                method, or if its superclass may not delegate entitlements
         * @throws IllegalStateException if a {@link org.elasticsearch.entitlement.runtime.api.DelegateToCaller} or
         *                               {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator} method of a
         *                               server class creates the instance
         */
        Class<?> captureCreator(Class<?> generatedClass);

        /**
         * Lets the policy record each wrong use of the annotations before it is thrown. Tests use it to fail even when the
         * code that catches the exception only logs it.
         */
        void reportBug(IllegalStateException bug);
    }

    private static volatile Support support;

    private DelegationRuntime() {}

    /**
     * Makes the {@link org.elasticsearch.entitlement.runtime.api.Delegation} methods use {@code newSupport}. Bootstrap calls
     * this with the support of its policy.
     */
    public static void install(Support newSupport) {
        support = newSupport;
    }

    /**
     * @return the installed support, or {@code null} if there is none
     */
    public static Support support() {
        return support;
    }
}
