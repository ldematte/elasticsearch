/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.api;

import org.elasticsearch.core.CheckedRunnable;
import org.elasticsearch.core.CheckedSupplier;
import org.elasticsearch.core.Strings;
import org.elasticsearch.entitlement.runtime.delegation.DelegationBugs;
import org.elasticsearch.entitlement.runtime.delegation.DelegationHolder;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRuntime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;

/**
 * Runtime support for {@link DelegateToCreator}.
 * <p>
 * A class with {@link DelegateToCreator} methods calls {@link #instantiate} from its factory method. The {@code $Delegating}
 * subclass that the entitlement annotation processor generates calls the other methods.
 */
public final class Delegation {

    private static final StackWalker WALKER = StackWalker.getInstance(RETAIN_CLASS_REFERENCE);

    private Delegation() {}

    /**
     * Creates an instance of the generated {@code $Delegating} subclass of {@code base}. The instance uses the entitlements
     * of the class that called the factory method.
     *
     * @param lookup the result of {@code MethodHandles.lookup()}, called in {@code base}. It proves that the call comes from
     *               code in {@code base}.
     * @param base the class with {@link DelegateToCreator} methods
     * @param constructorType the type of the constructor to call
     * @param args the constructor arguments
     * @return the new instance
     * @throws IllegalCallerException if {@code lookup} does not belong to {@code base}, if {@code base} has no
     *                                {@link DelegateToCreator} method, or if {@code base} may not delegate entitlements
     * @throws IllegalStateException if the generated subclass does not exist or has no constructor of that type, or if a
     *                               {@link DelegateToCaller} or {@link DelegateToCreator} method of a server class calls the
     *                               factory method
     */
    public static <T> T instantiate(MethodHandles.Lookup lookup, Class<T> base, MethodType constructorType, Object... args) {
        if (lookup.lookupClass() != base || lookup.hasFullPrivilegeAccess() == false) {
            throw new IllegalCallerException(Strings.format("[%s] cannot instantiate [%s]", lookup, base.getName()));
        }
        MethodHandle constructor;
        try {
            Class<?> generated = lookup.findClass(base.getName() + DelegationRuntime.GENERATED_CLASS_SUFFIX);
            constructor = lookup.findConstructor(generated, constructorType);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                Strings.format("Cannot find the constructor %s of the generated subclass of [%s]", constructorType, base.getName()),
                e
            );
        }
        try {
            return base.cast(constructor.invokeWithArguments(args));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(Strings.format("Cannot instantiate the generated subclass of [%s]", base.getName()), t);
        }
    }

    /**
     * Returns the identity of the class that is creating the instance being constructed. The constructor of the generated
     * {@code $Delegating} class calls this once. Until bootstrap installs the entitlement runtime, it checks nothing, and
     * the creator is the base class itself.
     *
     * @throws IllegalCallerException if the caller is not a generated {@code $Delegating} class, if its superclass has no
     *                                {@link DelegateToCreator} method, or if its superclass may not delegate entitlements
     * @throws IllegalStateException if a {@link DelegateToCaller} or {@link DelegateToCreator} method of a server class
     *                               creates the instance
     */
    public static EntitlementIdentity capture() {
        Class<?> generated = WALKER.getCallerClass();
        DelegationRuntime.Support support = DelegationRuntime.support();
        Class<?> base = generated.getSuperclass();
        Class<?> creator = support == null ? base : support.captureCreator(generated);
        return new EntitlementIdentity(creator, base);
    }

    /**
     * Runs {@code op} so that entitlement checks in the code of {@link EntitlementIdentity#base()} use the entitlements of
     * {@link EntitlementIdentity#creator()}.
     *
     * @throws IllegalStateException if {@code creator} is null. This happens when a constructor of the base class calls a
     *                               {@link DelegateToCreator} method: the generated subclass sets its identity only after
     *                               the base constructor returns.
     */
    public static <T, X extends Exception> T call(EntitlementIdentity creator, CheckedSupplier<T, X> op) throws X {
        if (creator == null) {
            throw notYetCaptured(WALKER.getCallerClass());
        }
        return DelegationHolder.call(creator, op);
    }

    /**
     * The same as {@link #call}, for operations that return nothing.
     */
    public static <X extends Exception> void run(EntitlementIdentity creator, CheckedRunnable<X> op) throws X {
        if (creator == null) {
            throw notYetCaptured(WALKER.getCallerClass());
        }
        DelegationHolder.call(creator, () -> {
            op.run();
            return null;
        });
    }

    private static IllegalStateException notYetCaptured(Class<?> generated) {
        IllegalStateException bug = DelegationBugs.creatorNotYetCaptured(generated.getSuperclass());
        DelegationRuntime.Support support = DelegationRuntime.support();
        if (support != null) {
            support.reportBug(bug);
        }
        return bug;
    }
}
