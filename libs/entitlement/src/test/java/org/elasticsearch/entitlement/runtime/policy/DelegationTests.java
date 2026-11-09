/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.policy;

import org.elasticsearch.entitlement.bridge.NotEntitledException;
import org.elasticsearch.entitlement.runtime.api.Delegation;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRegistry;
import org.elasticsearch.entitlement.runtime.delegation.DelegationRuntime;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ALL_UNNAMED;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * Tests delegation through the processor's real output for the fixture classes {@link TestCallerUtility},
 * {@link TestCreatorUtility}, {@link TestPluginCode} and {@link TestServerCode}. {@code TestPluginCode} is a plugin, and the
 * other fixtures are server code.
 * <p>
 * The fixtures need "Test" in their names. {@link TestPolicyManager} treats the other classes of
 * {@code org.elasticsearch.entitlement} as part of the entitlement library, and delegation ignores their stack frames.
 */
public class DelegationTests extends ESTestCase {

    private TestPolicyManager policyManager;
    private PolicyCheckerImpl checker;
    private DelegationRuntime.Support previousSupport;

    @Before
    public void installDelegation() {
        policyManager = new TestPolicyManager(
            new Policy("server", List.of()),
            Map.of(),
            type -> type.getNestHost() == TestPluginCode.class
                ? PolicyManager.PolicyScope.plugin("test-plugin", ALL_UNNAMED)
                : PolicyManager.PolicyScope.server(ALL_UNNAMED),
            EmptyPathLookup.INSTANCE,
            List.of(),
            List.of(),
            DelegationRegistry.load(DelegationTests.class.getClassLoader())
        );
        policyManager.setActive(true);
        policyManager.setTriviallyAllowingTestCode(false);
        checker = new PolicyCheckerImpl(Set.of(), PolicyManager.class.getModule(), policyManager, EmptyPathLookup.INSTANCE);
        previousSupport = DelegationRuntime.support();
        DelegationRuntime.install(policyManager.delegationSupport());
    }

    @After
    public void restoreDelegation() {
        DelegationRuntime.install(previousSupport);
    }

    public void testUnannotatedCodeIsCheckedAsItself() {
        assertEquals(TestPluginCode.class, TestPluginCode.requestingClass(checker));
        assertEquals(TestServerCode.class, TestServerCode.requestingClass(checker));
        assertEquals(TestCallerUtility.class, TestPluginCode.callCallerUtilityUndelegated(checker));
        assertEquals(TestCreatorUtility.class, TestPluginCode.createCreatorUtility().undelegatedRequestingClass(checker));
    }

    public void testCallerMethodIsCheckedAsItsCaller() {
        assertEquals(TestPluginCode.class, TestPluginCode.callCallerUtility(checker));
        assertEquals(TestPluginCode.class, TestPluginCode.callCallerUtilityNested(checker));
        assertEquals(TestPluginCode.class, TestPluginCode.callCallerUtilityThroughStream(checker));
        assertEquals(TestServerCode.class, TestPluginCode.callServerThroughCallerUtility(checker));
    }

    public void testCallerMethodCalledByCallerMethodIsCheckedAsTheOuterCaller() {
        assertEquals(TestPluginCode.class, TestPluginCode.callCallerUtilityThroughAnotherCallerUtility(checker));
    }

    public void testAnnotatedCodeThatCannotDelegateIsCheckedAsItself() {
        assertEquals(TestPluginCode.Annotated.class, TestPluginCode.Annotated.requestingClass(checker));
    }

    public void testNotEntitledMessageNamesTheDelegatingClass() {
        var exception = expectThrows(NotEntitledException.class, () -> TestPluginCode.checkExitVMThroughCallerUtility(checker));
        assertThat(exception.getMessage(), containsString("class [" + TestPluginCode.class + "]"));
        assertThat(exception.getMessage(), containsString("delegated by [" + TestCallerUtility.class + "]"));
    }

    public void testCreatorMethodIsCheckedAsTheCreator() {
        assertEquals(TestPluginCode.class, TestPluginCode.createCreatorUtility().requestingClass(checker));
        assertEquals(TestPluginCode.class, TestPluginCode.createCreatorUtilityThroughMethodReference().requestingClass(checker));
        assertEquals(TestServerCode.class, TestServerCode.createCreatorUtility().requestingClass(checker));
    }

    public void testCodeCalledByCreatorMethodIsCheckedAsItself() {
        var pluginCreated = TestPluginCode.createCreatorUtility();
        assertEquals(TestServerCode.class, pluginCreated.withCallback(() -> TestServerCode.requestingClass(checker)));
        assertEquals(TestPluginCode.class, pluginCreated.withCallback(() -> TestPluginCode.requestingClass(checker)));
    }

    public void testNestedCreatorMethodsRestoreTheOuterCreator() {
        var pluginCreated = TestPluginCode.createCreatorUtility();
        var serverCreated = TestServerCode.createCreatorUtility();
        List<Class<?>> requestingClasses = pluginCreated.withCallback(
            () -> List.of(serverCreated.requestingClass(checker), pluginCreated.requestingClass(checker))
        );
        assertEquals(List.of(TestServerCode.class, TestPluginCode.class), requestingClasses);
    }

    public void testCreatingFromCallerMethodIsABug() {
        var exception = expectThrows(IllegalStateException.class, TestPluginCode::createCreatorUtilityThroughCallerUtility);
        assertThat(exception.getMessage(), containsString("Entitlement delegation bug in [" + TestCallerUtility.class.getName() + "]"));
        assertSame(exception, expectThrows(AssertionError.class, () -> policyManager.assertNoDelegationBugs("suite")).getCause());
    }

    public void testCreatorMethodWithoutCreatorIsABug() {
        // A mock is the usual way to get an instance that the factory did not create
        TestCreatorUtility notCreatedThroughFactory = mock(TestCreatorUtility.class, CALLS_REAL_METHODS);
        var exception = expectThrows(IllegalStateException.class, () -> notCreatedThroughFactory.requestingClass(checker));
        assertThat(
            exception.getMessage(),
            containsString("This is a bug in the code that created this instance of [" + TestCreatorUtility.class.getName() + "]")
        );

        var suiteFailure = expectThrows(AssertionError.class, () -> policyManager.assertNoDelegationBugs("suite"));
        assertThat(suiteFailure.getMessage(), containsString("[1] entitlement delegation bug(s) detected during [suite]"));
        assertSame(exception, suiteFailure.getCause());
        policyManager.clearDelegationBugs();
        policyManager.assertNoDelegationBugs("suite");

        policyManager.setActive(false);
        expectThrows(IllegalStateException.class, () -> notCreatedThroughFactory.requestingClass(checker));
    }

    public void testOnlyGeneratedClassesCanCaptureACreator() {
        expectThrows(IllegalCallerException.class, Delegation::capture);
        expectThrows(
            IllegalCallerException.class,
            () -> Delegation.instantiate(MethodHandles.lookup(), TestCreatorUtility.class, MethodType.methodType(void.class))
        );
    }
}
