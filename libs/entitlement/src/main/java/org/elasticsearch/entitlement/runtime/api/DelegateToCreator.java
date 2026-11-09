/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes the entitlement checks in the annotated method use the entitlements of the class that called the factory method of
 * the instance, whichever thread runs the method.
 * <p>
 * Use it on server classes that keep working for a plugin after the plugin's call returns, for example on a background
 * thread. Without it, those checks use the entitlements of the server, not those of the plugin.
 * <p>
 * The module must run {@code EntitlementDelegationProcessor}, from {@code :libs:entitlement:delegation-processor}, as an
 * annotation processor. For each class with annotated methods, the processor generates a subclass named
 * {@code <class>$Delegating}. The subclass records the class that created it, and overrides each annotated method to run
 * with that class's entitlements. While an annotated method runs, this applies to all the code of its top-level class,
 * including nested classes and lambdas. Code of other classes is checked as usual.
 * <p>
 * Every instance must be an instance of the generated subclass. So the class creates its instances in a static factory
 * method, by calling {@link Delegation#instantiate}. The processor reports a compilation error for:
 * <ul>
 *     <li>a method with both this annotation and {@link DelegateToCaller};</li>
 *     <li>annotated methods that are private, static, final or abstract;</li>
 *     <li>annotated methods that declare more than one checked exception, or a {@link Throwable} that is not an
 *     {@link Exception};</li>
 *     <li>annotated methods of an interface, an enum or a record;</li>
 *     <li>declaring classes that are nested, generic or not abstract;</li>
 *     <li>constructors that are not package-private, or that declare checked exceptions;</li>
 *     <li>other subclasses of the declaring class in the same compilation.</li>
 * </ul>
 * These are bugs too, and throw an {@link IllegalStateException} at runtime:
 * <ul>
 *     <li>an instance that the factory did not create, such as a mock, performs a checked operation in an annotated
 *     method;</li>
 *     <li>a constructor of the class calls an annotated method;</li>
 *     <li>a method of another server class, annotated with this or with {@link DelegateToCaller}, calls the factory. It is
 *     not clear whether the new instance should act for that class or for its caller.</li>
 * </ul>
 * Only classes in the server layer may use the annotation. When entitlements are enabled, the factory method of any other
 * class throws an {@link IllegalCallerException}.
 *
 * @see DelegateToCaller
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface DelegateToCreator {
}
