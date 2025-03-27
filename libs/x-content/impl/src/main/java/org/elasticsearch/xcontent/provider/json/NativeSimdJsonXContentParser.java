/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.xcontent.provider.json;

import org.elasticsearch.nativeaccess.lib.LoaderHelper;
import org.elasticsearch.xcontent.XContentLocation;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.support.AbstractXContentParser;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

import static org.elasticsearch.nativeaccess.jdk.LinkerHelper.downcallHandle;

class NativeSimdJsonXContentParser extends AbstractXContentParser {

    // TODO: separate (this goes into native/NativeAccess)

    static final MethodHandle createFactory$mh;
    static final MethodHandle deleteFactory$mh;

    static final MethodHandle createParser$mh;
    static final MethodHandle deleteParser$mh;

    static final MethodHandle nextToken$mh;
    static final MethodHandle currentName$mh;

    static final MethodHandle longValue$mh;
    static final MethodHandle booleanValue$mh;
    static final MethodHandle doubleValue$mh;
    static final MethodHandle stringValue$mh;

    /*
     * void* create_parser_factory();
     * void delete_parser_factory(void* factory);
     *
     * void* create_parser(void* factory, std::string& data);
     * void delete_parser(void* state);
     *
     * int next_token(void* parser);
     * const char* current_name(void* current_state, int32_t* size)
     *
     * int64_t long_value(void* parser);
     * int32_t boolean_value(void* parser);
     * double double_value(void* parser);
     * const char* string_value(void* current_state, int32_t* size);
     */

    static {
        LoaderHelper.loadLibrary("simdjson-bridge");
        createFactory$mh = downcallHandle("create_parser_factory", FunctionDescriptor.of(ValueLayout.ADDRESS));
        deleteFactory$mh = downcallHandle("delete_parser_factory", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        createParser$mh = downcallHandle(
            "create_parser",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );
        deleteParser$mh = downcallHandle("delete_parser", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        nextToken$mh = downcallHandle("next_token", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        currentName$mh = downcallHandle(
            "current_name",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        longValue$mh = downcallHandle("long_value", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        booleanValue$mh = downcallHandle("boolean_value", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        doubleValue$mh = downcallHandle("double_value", FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS));
        stringValue$mh = downcallHandle(
            "string_value",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
    }

    private MemorySegment parserPointer;
    private final Arena arena;
    private Token currentToken;

    private final MemorySegment sizeBuffer;

    private NativeSimdJsonXContentParser(XContentParserConfiguration config, MemorySegment parserPointer, Arena arena) {
        super(config.registry(), config.deprecationHandler(), config.restApiVersion());
        this.parserPointer = parserPointer;
        this.arena = arena;
        this.sizeBuffer = arena.allocate(ValueLayout.JAVA_INT.byteSize(), ValueLayout.JAVA_INT.byteAlignment());
    }

    public static NativeSimdJsonXContentParser create(XContentParserConfiguration config, byte[] jsonContent, int offset, int length) {
        try {
            MemorySegment factoryPointer = (MemorySegment) createFactory$mh.invokeExact();
            // SIMDJSON_PADDING = 64;
            var arena = Arena.ofShared();
            MemorySegment nativeBuffer = arena.allocate(length + 64, 256);
            MemorySegment.copy(jsonContent, offset, nativeBuffer, ValueLayout.JAVA_BYTE, 0, length);
            MemorySegment parserPointer = (MemorySegment) createParser$mh.invokeExact(factoryPointer, nativeBuffer, length, length + 64);
            return new NativeSimdJsonXContentParser(config, parserPointer, arena);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public XContentType contentType() {
        return XContentType.JSON;
    }

    private static final Token[] TOKENS = {
        Token.START_OBJECT,
        Token.END_OBJECT,
        Token.START_ARRAY,
        Token.END_ARRAY,
        Token.FIELD_NAME,
        Token.VALUE_STRING,
        Token.VALUE_NUMBER,
        Token.VALUE_BOOLEAN,
        Token.VALUE_EMBEDDED_OBJECT,
        Token.VALUE_NULL };

    @Override
    public Token nextToken() throws IOException {
        try {
            var token = (int) nextToken$mh.invokeExact(parserPointer);
            if (token == -1) {
                currentToken = null;
            } else {
                currentToken = TOKENS[token];
            }
            return currentToken;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected boolean doBooleanValue() throws IOException {
        try {
            var result = (int) booleanValue$mh.invokeExact(parserPointer);
            return result != 0;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected short doShortValue() throws IOException {
        try {
            return (short) longValue$mh.invokeExact(parserPointer);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected int doIntValue() throws IOException {
        try {
            return (int) longValue$mh.invokeExact(parserPointer);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected long doLongValue() throws IOException {
        try {
            return (long) longValue$mh.invokeExact(parserPointer);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected float doFloatValue() throws IOException {
        try {
            return (float) doubleValue$mh.invokeExact(parserPointer);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected double doDoubleValue() throws IOException {
        try {
            return (double) doubleValue$mh.invokeExact(parserPointer);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void allowDuplicateKeys(boolean allowDuplicateKeys) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void skipChildren() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Token currentToken() {
        return currentToken;
    }

    @Override
    public String currentName() throws IOException {
        try {
            var segment = (MemorySegment) currentName$mh.invokeExact(parserPointer, sizeBuffer);
            var size = sizeBuffer.get(ValueLayout.JAVA_INT, 0);
            byte[] bytes = segment.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
            return new String(bytes, 0, size, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String text() throws IOException {
        try {
            var segment = (MemorySegment) stringValue$mh.invokeExact(parserPointer, sizeBuffer);
            var size = sizeBuffer.get(ValueLayout.JAVA_INT, 0);
            byte[] bytes = segment.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
            return new String(bytes, 0, size, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void throwOnNoText() {
        throw new IllegalArgumentException("Expected text at " + getTokenLocation() + " but found " + currentToken());
    }

    @Override
    public CharBuffer charBuffer() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object objectText() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object objectBytes() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasTextCharacters() {
        return false;
    }

    @Override
    public char[] textCharacters() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int textLength() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int textOffset() throws IOException {
        return 0;
    }

    @Override
    public Number numberValue() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public NumberType numberType() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] binaryValue() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public XContentLocation getTokenLocation() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isClosed() {
        return parserPointer == MemorySegment.NULL;
    }

    @Override
    public void close() throws IOException {
        arena.close();
        var currentParser = parserPointer;
        try {
            deleteParser$mh.invokeExact(parserPointer);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            parserPointer = MemorySegment.NULL;
        }
    }
}
