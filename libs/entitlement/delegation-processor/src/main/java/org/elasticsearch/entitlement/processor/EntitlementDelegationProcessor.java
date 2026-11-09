/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * Generates what the {@code @DelegateToCaller} and {@code @DelegateToCreator} annotations need to work at runtime:
 * <ul>
 *     <li>a final {@code $Delegating} subclass for each class with {@code @DelegateToCreator} methods. The subclass records
 *     its creator in its constructor. It overrides each of those methods to run with the creator's entitlements.</li>
 *     <li>a {@code META-INF/entitlement-delegation} resource that lists every annotated method. Entitlement checks use it to
 *     recognize annotated methods on the stack. {@code DelegationRegistry} describes its format.</li>
 * </ul>
 * It also reports wrong uses of the annotations as compilation errors. {@code DelegateToCaller} and
 * {@code DelegateToCreator} list them.
 */
@SupportedAnnotationTypes({ EntitlementDelegationProcessor.DELEGATE_TO_CALLER, EntitlementDelegationProcessor.DELEGATE_TO_CREATOR })
public final class EntitlementDelegationProcessor extends AbstractProcessor {
    static final String API_PACKAGE = "org.elasticsearch.entitlement.runtime.api";
    static final String DELEGATE_TO_CALLER = API_PACKAGE + ".DelegateToCaller";
    static final String DELEGATE_TO_CREATOR = API_PACKAGE + ".DelegateToCreator";
    static final String RESOURCE_NAME = "META-INF/entitlement-delegation";
    static final String GENERATED_CLASS_SUFFIX = "$Delegating";

    private final List<String> resourceLines = new ArrayList<>();
    private final Set<TypeElement> creatorClasses = new LinkedHashSet<>();
    private final Map<TypeElement, TypeElement> superclasses = new LinkedHashMap<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            checkNotSubclassed();
            writeResource();
            return false;
        }
        recordSubclasses(roundEnv.getRootElements());
        Map<TypeElement, List<ExecutableElement>> creatorMethodsByClass = new LinkedHashMap<>();
        for (TypeElement annotation : annotations) {
            boolean creator = annotation.getQualifiedName().contentEquals(DELEGATE_TO_CREATOR);
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                ExecutableElement method = (ExecutableElement) element;
                if (creator) {
                    if (hasAnnotation(method, DELEGATE_TO_CALLER)) {
                        error(method, "a method cannot be both @DelegateToCaller and @DelegateToCreator");
                    } else if (isValidCreatorMethod(method)) {
                        creatorMethodsByClass.computeIfAbsent((TypeElement) method.getEnclosingElement(), k -> new ArrayList<>())
                            .add(method);
                    }
                    addResourceLine("CREATOR", method);
                } else if (hasAnnotation(method, DELEGATE_TO_CREATOR) == false) {
                    if (method.getModifiers().contains(Modifier.ABSTRACT)) {
                        error(method, "@DelegateToCaller methods must have a body");
                    }
                    addResourceLine("CALLER", method);
                }
            }
        }
        creatorMethodsByClass.forEach((type, methods) -> {
            if (isValidCreatorClass(type)) {
                creatorClasses.add(type);
                writeDelegatingClass(type, methods);
            }
        });
        return false;
    }

    private boolean isValidCreatorMethod(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.FINAL)) {
            error(method, "@DelegateToCreator methods must be overridable: not private, static nor final");
            return false;
        }
        if (modifiers.contains(Modifier.ABSTRACT)) {
            error(method, "@DelegateToCreator methods must have a body");
            return false;
        }
        List<? extends TypeMirror> checkedExceptions = checkedExceptions(method);
        if (checkedExceptions.size() > 1) {
            error(method, "@DelegateToCreator methods can declare at most one checked exception");
            return false;
        }
        TypeMirror exception = processingEnv.getElementUtils().getTypeElement(Exception.class.getName()).asType();
        if (checkedExceptions.stream().anyMatch(type -> processingEnv.getTypeUtils().isSubtype(type, exception) == false)) {
            error(method, "@DelegateToCreator methods can only declare checked exceptions that extend Exception");
            return false;
        }
        return true;
    }

    private boolean isValidCreatorClass(TypeElement type) {
        boolean valid = true;
        if (type.getKind() != ElementKind.CLASS || type.getNestingKind() != NestingKind.TOP_LEVEL) {
            error(
                type,
                "@DelegateToCreator methods must be declared in a top-level class, not in an interface, enum, record or nested class"
            );
            valid = false;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT) == false) {
            error(type, "classes with @DelegateToCreator methods must be abstract, so that they are instantiated through their factory");
            valid = false;
        }
        if (type.getTypeParameters().isEmpty() == false) {
            error(type, "classes with @DelegateToCreator methods cannot be generic");
            valid = false;
        }
        List<ExecutableElement> constructors = constructors(type);
        for (ExecutableElement constructor : constructors) {
            Set<Modifier> modifiers = constructor.getModifiers();
            if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED) || modifiers.contains(Modifier.PRIVATE)) {
                error(
                    constructor,
                    "constructors of classes with @DelegateToCreator methods must be package-private, so that only the generated"
                        + " subclass extends them"
                );
                valid = false;
            }
            if (checkedExceptions(constructor).isEmpty() == false) {
                error(constructor, "constructors of classes with @DelegateToCreator methods cannot declare checked exceptions");
                valid = false;
            }
        }
        return valid;
    }

    private void recordSubclasses(Set<? extends Element> rootElements) {
        for (Element element : rootElements) {
            if (element instanceof TypeElement type) {
                recordSubclass(type);
            }
        }
    }

    private void recordSubclass(TypeElement type) {
        if (type.getSuperclass() instanceof DeclaredType superclass) {
            superclasses.put(type, (TypeElement) superclass.asElement());
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                recordSubclass(nested);
            }
        }
    }

    private void checkNotSubclassed() {
        for (TypeElement creatorClass : creatorClasses) {
            String generatedName = creatorClass.getQualifiedName() + GENERATED_CLASS_SUFFIX;
            superclasses.forEach((subclass, superclass) -> {
                if (superclass.equals(creatorClass) && subclass.getQualifiedName().contentEquals(generatedName) == false) {
                    error(
                        subclass,
                        "classes with @DelegateToCreator methods cannot be extended; [" + creatorClass.getQualifiedName() + "] is"
                    );
                }
            });
        }
    }

    private void addResourceLine(String mode, ExecutableElement method) {
        TypeElement declaringClass = (TypeElement) method.getEnclosingElement();
        resourceLines.add(
            String.join(
                " ",
                mode,
                binaryName(topLevelClass(declaringClass)),
                binaryName(declaringClass),
                method.getSimpleName().toString(),
                descriptor(method)
            )
        );
    }

    private void writeResource() {
        if (resourceLines.isEmpty()) {
            return;
        }
        try (Writer writer = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_NAME).openWriter()) {
            for (String line : resourceLines.stream().sorted().toList()) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + RESOURCE_NAME, e);
        }
    }

    private void writeDelegatingClass(TypeElement base, List<ExecutableElement> methods) {
        String packageName = processingEnv.getElementUtils().getPackageOf(base).getQualifiedName().toString();
        String baseName = base.getSimpleName().toString();
        String generatedName = baseName + GENERATED_CLASS_SUFFIX;
        StringBuilder source = new StringBuilder();
        if (packageName.isEmpty() == false) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("final class ").append(generatedName).append(" extends ").append(baseName).append(" {\n");
        source.append("    private final ")
            .append(API_PACKAGE)
            .append(".EntitlementIdentity entitlementCreator = ")
            .append(API_PACKAGE)
            .append(".Delegation.capture();\n");
        for (ExecutableElement constructor : constructors(base)) {
            source.append("\n    ").append(generatedName).append('(').append(parameters(constructor)).append(") {\n");
            source.append("        super(").append(arguments(constructor)).append(");\n");
            source.append("    }\n");
        }
        for (ExecutableElement method : methods) {
            appendOverride(source, method);
        }
        source.append("}\n");
        try (Writer writer = processingEnv.getFiler().createSourceFile(packageName + "." + generatedName, base).openWriter()) {
            writer.write(source.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the delegating class of [" + base.getQualifiedName() + "]", e);
        }
    }

    private static void appendOverride(StringBuilder source, ExecutableElement method) {
        boolean isVoid = method.getReturnType().getKind() == TypeKind.VOID;
        source.append("\n    @Override\n    ");
        for (Modifier modifier : method.getModifiers()) {
            if (modifier == Modifier.PUBLIC || modifier == Modifier.PROTECTED) {
                source.append(modifier).append(' ');
            }
        }
        if (method.getTypeParameters().isEmpty() == false) {
            source.append('<')
                .append(
                    method.getTypeParameters().stream().map(EntitlementDelegationProcessor::typeParameter).collect(Collectors.joining(", "))
                )
                .append("> ");
        }
        source.append(method.getReturnType()).append(' ').append(method.getSimpleName()).append('(').append(parameters(method)).append(')');
        if (method.getThrownTypes().isEmpty() == false) {
            source.append(" throws ").append(method.getThrownTypes().stream().map(TypeMirror::toString).collect(Collectors.joining(", ")));
        }
        source.append(" {\n        ")
            .append(isVoid ? "" : "return ")
            .append(API_PACKAGE)
            .append(isVoid ? ".Delegation.run(" : ".Delegation.call(")
            .append("this.entitlementCreator, () -> super.")
            .append(method.getSimpleName())
            .append('(')
            .append(arguments(method))
            .append("));\n    }\n");
    }

    private static String typeParameter(TypeParameterElement parameter) {
        List<? extends TypeMirror> bounds = parameter.getBounds();
        boolean onlyObject = bounds.size() == 1 && bounds.get(0).toString().equals("java.lang.Object");
        if (bounds.isEmpty() || onlyObject) {
            return parameter.getSimpleName().toString();
        }
        return parameter.getSimpleName() + " extends " + bounds.stream().map(TypeMirror::toString).collect(Collectors.joining(" & "));
    }

    private static String parameters(ExecutableElement executable) {
        List<? extends VariableElement> parameters = executable.getParameters();
        List<String> rendered = new ArrayList<>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement parameter = parameters.get(i);
            String type = parameter.asType().toString();
            if (executable.isVarArgs() && i == parameters.size() - 1) {
                type = ((ArrayType) parameter.asType()).getComponentType() + "...";
            }
            rendered.add(type + " " + parameter.getSimpleName());
        }
        return String.join(", ", rendered);
    }

    private static String arguments(ExecutableElement executable) {
        return executable.getParameters().stream().map(parameter -> parameter.getSimpleName().toString()).collect(Collectors.joining(", "));
    }

    private static List<ExecutableElement> constructors(TypeElement type) {
        return type.getEnclosedElements()
            .stream()
            .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
            .map(ExecutableElement.class::cast)
            .toList();
    }

    private List<? extends TypeMirror> checkedExceptions(ExecutableElement executable) {
        var types = processingEnv.getTypeUtils();
        var elements = processingEnv.getElementUtils();
        TypeMirror runtimeException = elements.getTypeElement(RuntimeException.class.getName()).asType();
        TypeMirror error = elements.getTypeElement(Error.class.getName()).asType();
        return executable.getThrownTypes()
            .stream()
            .filter(type -> types.isSubtype(type, runtimeException) == false && types.isSubtype(type, error) == false)
            .toList();
    }

    private static TypeElement topLevelClass(TypeElement type) {
        TypeElement topLevel = type;
        while (topLevel.getEnclosingElement() instanceof TypeElement enclosing) {
            topLevel = enclosing;
        }
        return topLevel;
    }

    private String binaryName(TypeElement type) {
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    private String descriptor(ExecutableElement method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (VariableElement parameter : method.getParameters()) {
            appendDescriptor(descriptor, parameter.asType());
        }
        descriptor.append(')');
        appendDescriptor(descriptor, method.getReturnType());
        return descriptor.toString();
    }

    private void appendDescriptor(StringBuilder descriptor, TypeMirror type) {
        TypeMirror erased = processingEnv.getTypeUtils().erasure(type);
        switch (erased.getKind()) {
            case BOOLEAN -> descriptor.append('Z');
            case BYTE -> descriptor.append('B');
            case CHAR -> descriptor.append('C');
            case SHORT -> descriptor.append('S');
            case INT -> descriptor.append('I');
            case LONG -> descriptor.append('J');
            case FLOAT -> descriptor.append('F');
            case DOUBLE -> descriptor.append('D');
            case VOID -> descriptor.append('V');
            case ARRAY -> {
                descriptor.append('[');
                appendDescriptor(descriptor, ((ArrayType) erased).getComponentType());
            }
            case DECLARED -> descriptor.append('L')
                .append(binaryName((TypeElement) ((DeclaredType) erased).asElement()).replace('.', '/'))
                .append(';');
            default -> throw new IllegalArgumentException("Unexpected type in a method signature: " + erased);
        }
    }

    private static boolean hasAnnotation(Element element, String annotationName) {
        return element.getAnnotationMirrors()
            .stream()
            .anyMatch(mirror -> ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotationName));
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
