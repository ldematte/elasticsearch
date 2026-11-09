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
import org.elasticsearch.core.SuppressForbidden;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lists the methods annotated with {@link org.elasticsearch.entitlement.runtime.api.DelegateToCaller} or
 * {@link org.elasticsearch.entitlement.runtime.api.DelegateToCreator}. The entitlement annotation processor writes this list.
 * <p>
 * The processor writes one {@value #RESOURCE_NAME} resource for each compilation, such as the main source set of a module.
 * Each line describes one annotated method, as five fields separated by spaces: mode, top-level class, declaring class,
 * method name and descriptor. The mode is {@code CALLER} or {@code CREATOR}. Class names are binary names, and the
 * descriptor is the JVM method descriptor. The registry matches methods to stack frames by name, so it does not load any
 * class.
 */
public final class DelegationRegistry {
    public static final String RESOURCE_NAME = "META-INF/entitlement-delegation";
    public static final DelegationRegistry EMPTY = new DelegationRegistry(Map.of());

    public enum Mode {
        CALLER,
        CREATOR
    }

    private record ResourceLine(Mode mode, String topLevelClass, MethodKey method) {}

    /**
     * The annotated methods of one top-level class and its nested classes.
     */
    public static final class DelegatedMethods {
        private final Map<MethodKey, Mode> methods;
        private final boolean hasCreatorMethods;

        private DelegatedMethods(Map<MethodKey, Mode> methods) {
            this.methods = methods;
            this.hasCreatorMethods = methods.containsValue(Mode.CREATOR);
        }

        public boolean hasCreatorMethods() {
            return hasCreatorMethods;
        }

        /**
         * @return the mode of the method running in {@code frame}, or {@code null} if that method is not annotated
         */
        public Mode modeOf(StackWalker.StackFrame frame) {
            return methods.get(new MethodKey(frame.getClassName(), frame.getMethodName(), frame.getDescriptor()));
        }
    }

    private record MethodKey(String declaringClass, String methodName, String descriptor) {}

    private final Map<String, DelegatedMethods> methodsByTopLevelClass;

    private DelegationRegistry(Map<String, DelegatedMethods> methodsByTopLevelClass) {
        this.methodsByTopLevelClass = methodsByTopLevelClass;
    }

    /**
     * Reads all the {@value #RESOURCE_NAME} resources that {@code classLoader} can see.
     */
    public static DelegationRegistry load(ClassLoader classLoader) {
        try {
            List<URL> resources = Collections.list(classLoader.getResources(RESOURCE_NAME));
            return of(resources.stream().flatMap(DelegationRegistry::readLines).map(DelegationRegistry::parse).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE_NAME, e);
        }
    }

    private static DelegationRegistry of(List<ResourceLine> lines) {
        Map<String, Map<MethodKey, Mode>> modesByTopLevelClass = new HashMap<>();
        for (ResourceLine line : lines) {
            modesByTopLevelClass.computeIfAbsent(line.topLevelClass(), k -> new HashMap<>()).put(line.method(), line.mode());
        }
        Map<String, DelegatedMethods> methodsByTopLevelClass = new HashMap<>();
        modesByTopLevelClass.forEach(
            (topLevelClass, modes) -> methodsByTopLevelClass.put(topLevelClass, new DelegatedMethods(Map.copyOf(modes)))
        );
        return new DelegationRegistry(Map.copyOf(methodsByTopLevelClass));
    }

    private static ResourceLine parse(String line) {
        String[] fields = line.trim().split(" +");
        if (fields.length != 5) {
            throw new IllegalArgumentException(Strings.format("Malformed line in %s: [%s]", RESOURCE_NAME, line));
        }
        return new ResourceLine(Mode.valueOf(fields[0]), fields[1], new MethodKey(fields[2], fields[3], fields[4]));
    }

    /**
     * @return the annotated methods of the top-level class of {@code type} and its nested classes, or {@code null} if there
     * are none
     */
    public DelegatedMethods delegatedMethods(Class<?> type) {
        return methodsByTopLevelClass.get(type.getNestHost().getName());
    }

    @SuppressForbidden(reason = "URLs from class loader")
    private static Stream<String> readLines(URL resource) {
        try (var reader = new BufferedReader(new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> line.isBlank() == false).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
