/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.xcontent.provider.json;

import org.elasticsearch.xcontent.XContentLocation;
import org.elasticsearch.xcontent.XContentParseException;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.support.AbstractXContentParser;
import org.simdjson.JsonValue;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;

class SimdJsonXContentParser extends AbstractXContentParser {

    private JsonValue currentValue;
    private Token currentToken;
    private CharSequence currentName;
    private final Stack<Continuation> stack = new Stack<>();

    private Continuation continuation;

    SimdJsonXContentParser(XContentParserConfiguration config, JsonValue jsonValue) {
        super(config.registry(), config.deprecationHandler(), config.restApiVersion());

        this.continuation = () -> {
            currentValue = jsonValue;
            currentToken = getTokenType(jsonValue);
            return bind(this::eof);
        };
    }

    private Continuation bind(Continuation next) {
        if (currentToken == Token.START_OBJECT) {
            stack.push(next);
            return new InObject(currentValue.objectIterator());
        } else if (currentToken == Token.START_ARRAY) {
            stack.push(next);
            return new InArray(currentValue.arrayIterator());
        } else {
            return next;
        }
    }

    @Override
    public XContentType contentType() {
        return XContentType.JSON;
    }

    @Override
    public Token nextToken() throws IOException {
        continuation = continuation.run();
        return currentToken;
    }

    interface Continuation {
        Continuation run();
    }

    private Continuation eof() {
        currentToken = null;
        currentValue = null;
        return this::eof;
    }

    private static Token getTokenType(JsonValue jsonValue) {
        if (jsonValue == null) return null;
        if (jsonValue.isArray()) return Token.START_ARRAY;
        if (jsonValue.isObject()) return Token.START_OBJECT;
        if (jsonValue.isBoolean()) return Token.VALUE_BOOLEAN;
        if (jsonValue.isLong() || jsonValue.isDouble()) return Token.VALUE_NUMBER;
        if (jsonValue.isString()) return Token.VALUE_STRING;
        if (jsonValue.isNull()) return Token.VALUE_NULL;

        // Token.VALUE_EMBEDDED_OBJECT;
        throw new IllegalStateException("No matching token for json_token [" + jsonValue + "]");
    }

    @Override
    protected boolean doBooleanValue() throws IOException {
        if (currentValue.isBoolean()) {
            return currentValue.asBoolean();
        }
        throw new XContentParseException(currentValue.asString() + " is not a valid boolean value");
    }

    @Override
    protected short doShortValue() throws IOException {
        return (short) currentValue.asLong();
    }

    @Override
    protected int doIntValue() throws IOException {
        return (int) currentValue.asLong();
    }

    @Override
    protected long doLongValue() throws IOException {
        return currentValue.asLong();
    }

    @Override
    protected float doFloatValue() throws IOException {
        return (float) currentValue.asDouble();
    }

    @Override
    protected double doDoubleValue() throws IOException {
        return currentValue.asDouble();
    }

    @Override
    public void allowDuplicateKeys(boolean allowDuplicateKeys) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void skipChildren() throws IOException {
        this.continuation = stack.pop();
    }

    @Override
    public Token currentToken() {
        return currentToken;
    }

    @Override
    public String currentName() throws IOException {
        // TODO: probably this can be optimized/made better
        return String.valueOf(currentName);
    }

    @Override
    public String text() throws IOException {
        if (currentToken().isValue() == false) {
            throwOnNoText();
        }
        return currentValue.asString();
    }

    private void throwOnNoText() {
        throw new IllegalArgumentException("Expected text at " + getTokenLocation() + " but found " + currentToken());
    }

    @Override
    public CharBuffer charBuffer() throws IOException {
        return CharBuffer.wrap(currentValue.asString());
    }

    @Override
    public Object objectText() throws IOException {
        if (currentValue.isString()) {
            return text();
        } else if (currentValue.isLong()) {
            return currentValue.asLong();
        } else if (currentValue.isDouble()) {
            return currentValue.asDouble();
        } else if (currentValue.isBoolean()) {
            return currentValue.asBoolean();
        } else if (currentValue.isNull()) {
            return null;
        } else {
            return text();
        }
    }

    @Override
    public Object objectBytes() throws IOException {
        if (currentValue.isString()) {
            return charBuffer();
        } else if (currentValue.isLong()) {
            return currentValue.asLong();
        } else if (currentValue.isDouble()) {
            return currentValue.asDouble();
        } else if (currentValue.isBoolean()) {
            return currentValue.asBoolean();
        } else if (currentValue.isNull()) {
            return null;
        } else {
            return charBuffer();
        }
    }

    @Override
    public boolean hasTextCharacters() {
        return false;
    }

    @Override
    public char[] textCharacters() throws IOException {
        return currentValue.asString().toCharArray();
    }

    @Override
    public int textLength() throws IOException {
        return currentValue.asString().length();
    }

    @Override
    public int textOffset() throws IOException {
        return 0;
    }

    @Override
    public Number numberValue() throws IOException {
        if (currentValue.isLong()) {
            var longValue = currentValue.asLong();
            // TODO can probably be optimize with some bitwise operation
            // TODO but probably is better to fix the tests here?
            if (longValue < Integer.MAX_VALUE && longValue > Integer.MIN_VALUE) {
                return (int) longValue;
            }
        } else if (currentValue.isDouble()) {
            return currentValue.asDouble();
        }
        throw new XContentParseException("the current token is not a number");
    }

    @Override
    public NumberType numberType() throws IOException {
        if (currentValue.isLong()) {
            return NumberType.LONG;
        } else if (currentValue.isDouble()) {
            return NumberType.DOUBLE;
        }
        throw new XContentParseException("the current token is not a number");
    }

    @Override
    public byte[] binaryValue() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public XContentLocation getTokenLocation() {
        return new XContentLocation(0, 0);
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public void close() throws IOException {}

    private class InObject implements Continuation {
        private final Iterator<Map.Entry<String, JsonValue>> iterator;
        private boolean inValue = false;

        InObject(Iterator<Map.Entry<String, JsonValue>> iterator) {
            this.iterator = iterator;
        }

        @Override
        public Continuation run() {
            if (inValue) {
                currentToken = getTokenType(currentValue);
                inValue = false;
                return bind(this);
            }
            if (iterator.hasNext()) {
                var field = iterator.next();
                currentToken = Token.FIELD_NAME;
                currentName = field.getKey();
                currentValue = field.getValue();
                inValue = true;
                return bind(this);
            } else {
                currentToken = Token.END_OBJECT;
                return stack.pop();
            }
        }
    }

    private class InArray implements Continuation {
        private final Iterator<JsonValue> iterator;

        InArray(Iterator<JsonValue> iterator) {
            this.iterator = iterator;
        }

        @Override
        public Continuation run() {
            if (iterator.hasNext()) {
                currentValue = iterator.next();
                currentToken = getTokenType(currentValue);
                return bind(this);
            } else {
                currentToken = Token.END_ARRAY;
                return stack.pop();
            }
        }
    }
}
