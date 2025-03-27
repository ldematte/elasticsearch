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

class SimdJsonXContentParser extends AbstractXContentParser {

    private final JsonValue jsonValue;
    private Token currentToken;
    private CharSequence currentName;

    SimdJsonXContentParser(XContentParserConfiguration config, JsonValue jsonValue) {
        super(config.registry(), config.deprecationHandler(), config.restApiVersion());
        this.jsonValue = jsonValue;
    }

    @Override
    public XContentType contentType() {
        return XContentType.JSON;
    }

    @Override
    public Token nextToken() throws IOException {
        currentToken = translateToken(jsonValue.next());
        if (currentToken == Token.FIELD_NAME) {
            currentName = jsonValue.asString();
        }
        return currentToken;
    }

    private Token translateToken(char next) {
        // TODO: a 128 elem lookup table
        switch (next) {
            case 'r': throw new IllegalStateException("nextToken not called yet");
            case '[': return Token.START_ARRAY;
            case '{': return Token.START_OBJECT;
            case ']': return Token.END_ARRAY;
            case '}': return Token.END_OBJECT;
            case '"': return Token.VALUE_STRING;
            case 'k': return Token.FIELD_NAME;
            case 'l': return Token.VALUE_NUMBER;
            case 'd': return Token.VALUE_NUMBER;
            case 't': return Token.VALUE_BOOLEAN;
            case 'f': return Token.VALUE_BOOLEAN;
            case 'n': return Token.VALUE_NULL;
        }
        throw new IllegalStateException("Unknown token");
    }

    @Override
    protected boolean doBooleanValue() throws IOException {
        if (jsonValue.isBoolean()) {
            return jsonValue.asBoolean();
        }
        throw new XContentParseException(jsonValue.asString() + " is not a valid boolean value");
    }

    @Override
    protected short doShortValue() throws IOException {
        return (short) jsonValue.asLong();
    }

    @Override
    protected int doIntValue() throws IOException {
        return (int) jsonValue.asLong();
    }

    @Override
    protected long doLongValue() throws IOException {
        return jsonValue.asLong();
    }

    @Override
    protected float doFloatValue() throws IOException {
        return (float) jsonValue.asDouble();
    }

    @Override
    protected double doDoubleValue() throws IOException {
        return jsonValue.asDouble();
    }

    @Override
    public void allowDuplicateKeys(boolean allowDuplicateKeys) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void skipChildren() throws IOException {
        // TODO
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
        return jsonValue.asString();
    }

    private void throwOnNoText() {
        throw new IllegalArgumentException("Expected text at " + getTokenLocation() + " but found " + currentToken());
    }

    @Override
    public CharBuffer charBuffer() throws IOException {
        return CharBuffer.wrap(jsonValue.asString());
    }

    @Override
    public Object objectText() throws IOException {
        if (jsonValue.isString()) {
            return text();
        } else if (jsonValue.isLong()) {
            return jsonValue.asLong();
        } else if (jsonValue.isDouble()) {
            return jsonValue.asDouble();
        } else if (jsonValue.isBoolean()) {
            return jsonValue.asBoolean();
        } else if (jsonValue.isNull()) {
            return null;
        } else {
            return text();
        }
    }

    @Override
    public Object objectBytes() throws IOException {
        if (jsonValue.isString()) {
            return charBuffer();
        } else if (jsonValue.isLong()) {
            return jsonValue.asLong();
        } else if (jsonValue.isDouble()) {
            return jsonValue.asDouble();
        } else if (jsonValue.isBoolean()) {
            return jsonValue.asBoolean();
        } else if (jsonValue.isNull()) {
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
        return jsonValue.asString().toCharArray();
    }

    @Override
    public int textLength() throws IOException {
        return jsonValue.asString().length();
    }

    @Override
    public int textOffset() throws IOException {
        return 0;
    }

    @Override
    public Number numberValue() throws IOException {
        if (jsonValue.isLong()) {
            var longValue = jsonValue.asLong();
            // TODO can probably be optimize with some bitwise operation
            // TODO but probably is better to fix the tests here?
            if (longValue < Integer.MAX_VALUE && longValue > Integer.MIN_VALUE) {
                return (int) longValue;
            }
        } else if (jsonValue.isDouble()) {
            return jsonValue.asDouble();
        }
        throw new XContentParseException("the current token is not a number");
    }

    @Override
    public NumberType numberType() throws IOException {
        if (jsonValue.isLong()) {
            return NumberType.LONG;
        } else if (jsonValue.isDouble()) {
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
}
