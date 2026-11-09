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
 * Makes the entitlement checks in the annotated method use the entitlements of the method's caller.
 * <p>
 * Use it on server methods that perform sensitive operations for their caller, such as reading a file that a plugin names.
 * Without it, those checks use the entitlements of the server, not those of the plugin.
 * <p>
 * The caller is the first class on the stack outside the annotated method's top-level class. Classes of the system layer
 * and of the entitlement library do not count, and a lambda counts as the class that defines it. If the caller is itself
 * server code that delegates, with this annotation or with {@link DelegateToCreator}, its own rule applies in turn.
 * <p>
 * The rule also applies to code of the same top-level class that the annotated method calls, as long as no other class
 * runs in between. So it is enough to annotate the methods that other classes call. All other code is checked as usual.
 * <p>
 * Annotated methods can be static, but must have a body. The module must run {@code EntitlementDelegationProcessor},
 * from {@code :libs:entitlement:delegation-processor}, as an annotation processor. Without it, the annotation does
 * nothing. It also does nothing outside the server layer.
 *
 * @see DelegateToCreator
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface DelegateToCaller {
}
