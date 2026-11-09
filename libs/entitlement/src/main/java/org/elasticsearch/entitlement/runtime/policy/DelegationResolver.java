/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.policy;

import org.elasticsearch.core.Strings;
import org.elasticsearch.entitlement.runtime.api.EntitlementIdentity;
import org.elasticsearch.entitlement.runtime.delegation.DelegationBugs;
import org.elasticsearch.entitlement.runtime.delegation.DelegationHolder;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRegistry;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRegistry.DelegatedMethods;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRegistry.Mode;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRuntime;

import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;
import static java.lang.StackWalker.Option.SHOW_HIDDEN_FRAMES;

/**
 * Implements the rules of {@link org.elasticsearch.entitlement.runtime.api.DelegateToCaller} and
 * {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator}. It decides whose entitlements an entitlement check
 * uses. It also decides whose entitlements a new instance of a generated {@code $Delegating} class uses.
 * <p>
 * It treats a top-level class and its nested classes as one unit. It ignores the stack frames of the system layer and of
 * the entitlement library.
 */
final class DelegationResolver implements DelegationRuntime.Support {
    private static final StackWalker WALKER = StackWalker.getInstance(Set.of(RETAIN_CLASS_REFERENCE, SHOW_HIDDEN_FRAMES));

    private final PolicyManager policyManager;
    private final DelegationRegistry registry;

    DelegationResolver(PolicyManager policyManager, DelegationRegistry registry) {
        this.policyManager = policyManager;
        this.registry = registry;
    }

    /**
     * @param callerClass the class that called the instrumented method
     * @return the class whose entitlements the check uses
     * @throws IllegalStateException if a {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator} method performs
     *                               the check on an instance that its class's factory did not create, such as a mock
     */
    Class<?> requestingClass(Class<?> callerClass) {
        DelegatedMethods methods = delegatedMethods(callerClass);
        if (methods == null) {
            return callerClass;
        }
        Class<?> creator = boundCreator(callerClass, methods);
        if (creator != null) {
            return creator;
        }
        return WALKER.walk(frames -> findRequestingClass(frames, callerClass, methods));
    }

    @Override
    public Class<?> captureCreator(Class<?> generatedClass) {
        Class<?> base = requireGenerated(generatedClass);
        return WALKER.walk(frames -> findCreator(frames, generatedClass, base));
    }

    @Override
    public void reportBug(IllegalStateException bug) {
        policyManager.onDelegationBug(bug);
    }

    /**
     * Reads the frames of each class in turn, starting from {@code callerClass}. While the frames of a class include a
     * {@code DelegateToCaller} method, the check moves on to the next class.
     */
    private Class<?> findRequestingClass(Stream<StackWalker.StackFrame> stack, Class<?> callerClass, DelegatedMethods callerMethods) {
        Iterator<StackWalker.StackFrame> frames = stack.filter(frame -> isIgnored(frame.getDeclaringClass()) == false)
            .dropWhile(frame -> topLevelClass(frame.getDeclaringClass()) != topLevelClass(callerClass))
            .iterator();
        Class<?> requestingClass = callerClass;
        DelegatedMethods methods = callerMethods;
        StackWalker.StackFrame frame = next(frames);
        while (frame != null) {
            Class<?> topLevelClass = topLevelClass(requestingClass);
            boolean delegatesToCaller = false;
            while (frame != null && topLevelClass(frame.getDeclaringClass()) == topLevelClass) {
                Mode mode = methods.modeOf(frame);
                if (mode == Mode.CREATOR) {
                    throw report(DelegationBugs.unboundCreatorMethod(frame.getDeclaringClass(), topLevelClass));
                }
                delegatesToCaller |= mode == Mode.CALLER;
                frame = next(frames);
            }
            if (delegatesToCaller == false || frame == null) {
                return requestingClass;
            }
            requestingClass = requestingClassOf(frame.getDeclaringClass());
            methods = delegatedMethods(requestingClass);
            if (methods == null) {
                return requestingClass;
            }
            Class<?> creator = boundCreator(requestingClass, methods);
            if (creator != null) {
                return creator;
            }
        }
        return requestingClass;
    }

    /**
     * The creator is the class that called the factory method: the first class on the stack after the generated class and
     * the base class.
     */
    private Class<?> findCreator(Stream<StackWalker.StackFrame> stack, Class<?> generatedClass, Class<?> base) {
        Iterator<StackWalker.StackFrame> frames = stack.filter(frame -> isIgnored(frame.getDeclaringClass()) == false)
            .dropWhile(frame -> frame.getDeclaringClass() == generatedClass || topLevelClass(frame.getDeclaringClass()) == base)
            .iterator();
        StackWalker.StackFrame frame = next(frames);
        if (frame == null) {
            return base;
        }
        Class<?> creator = frame.getDeclaringClass();
        if (isInDelegatingMethod(frame, frames)) {
            throw report(DelegationBugs.createdByDelegatingCode(topLevelClass(creator), base));
        }
        return requestingClassOf(creator);
    }

    /**
     * Whether the frames of the class of {@code first}, starting from {@code first}, include an annotated method.
     */
    private boolean isInDelegatingMethod(StackWalker.StackFrame first, Iterator<StackWalker.StackFrame> frames) {
        DelegatedMethods methods = delegatedMethods(first.getDeclaringClass());
        if (methods == null) {
            return false;
        }
        Class<?> topLevelClass = topLevelClass(first.getDeclaringClass());
        for (var frame = first; frame != null && topLevelClass(frame.getDeclaringClass()) == topLevelClass; frame = next(frames)) {
            if (methods.modeOf(frame) != null) {
                return true;
            }
        }
        return false;
    }

    private Class<?> requireGenerated(Class<?> generatedClass) {
        Class<?> base = generatedClass.getSuperclass();
        if (base == null
            || Modifier.isFinal(generatedClass.getModifiers()) == false
            || generatedClass.getName().equals(base.getName() + DelegationRuntime.GENERATED_CLASS_SUFFIX) == false
            || generatedClass.getClassLoader() != base.getClassLoader()
            || generatedClass.getPackageName().equals(base.getPackageName()) == false) {
            throw new IllegalCallerException(Strings.format("[%s] is not a generated delegating class", generatedClass.getName()));
        }
        DelegatedMethods methods = registry.delegatedMethods(base);
        if (methods == null || methods.hasCreatorMethods() == false) {
            throw new IllegalCallerException(Strings.format("[%s] has no @DelegateToCreator method", base.getName()));
        }
        if (policyManager.canDelegate(base) == false) {
            throw new IllegalCallerException(Strings.format("[%s] is not allowed to delegate entitlements", base.getName()));
        }
        return base;
    }

    /**
     * @return the annotated methods of the top-level class of {@code type}, or {@code null} if it has none or may not delegate
     */
    private DelegatedMethods delegatedMethods(Class<?> type) {
        return policyManager.canDelegate(type) ? registry.delegatedMethods(type) : null;
    }

    private static Class<?> boundCreator(Class<?> type, DelegatedMethods methods) {
        if (methods.hasCreatorMethods()) {
            EntitlementIdentity identity = DelegationHolder.current();
            if (identity != null && identity.base() == topLevelClass(type)) {
                return identity.creator();
            }
        }
        return null;
    }

    private static Class<?> topLevelClass(Class<?> type) {
        return type.getNestHost();
    }

    private static Class<?> requestingClassOf(Class<?> type) {
        return type.isHidden() ? topLevelClass(type) : type;
    }

    private static StackWalker.StackFrame next(Iterator<StackWalker.StackFrame> frames) {
        return frames.hasNext() ? frames.next() : null;
    }

    private boolean isIgnored(Class<?> type) {
        return policyManager.isEntitlementClass(type) || policyManager.isTrustedSystemClass(type);
    }

    private IllegalStateException report(IllegalStateException bug) {
        reportBug(bug);
        return bug;
    }
}
