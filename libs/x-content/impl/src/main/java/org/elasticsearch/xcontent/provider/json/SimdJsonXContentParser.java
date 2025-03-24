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
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.support.AbstractXContentParser;
import org.simdjson.SimdJsonParser;

import java.io.IOException;
import java.nio.CharBuffer;

public class SimdJsonXContentParser extends AbstractXContentParser {

    SimdJsonParser parser = new SimdJsonParser();

    SimdJsonXContentParser(XContentParserConfiguration config) {
        super(config.registry(), config.deprecationHandler(), config.restApiVersion());

    }

    @Override
    protected boolean doBooleanValue() throws IOException {
        return false;
    }

    @Override
    protected short doShortValue() throws IOException {
        return 0;
    }

    @Override
    protected int doIntValue() throws IOException {
        return 0;
    }

    @Override
    protected long doLongValue() throws IOException {
        return 0;
    }

    @Override
    protected float doFloatValue() throws IOException {
        return 0;
    }

    @Override
    protected double doDoubleValue() throws IOException {
        return 0;
    }

    @Override
    public XContentType contentType() {
        return null;
    }

    @Override
    public void allowDuplicateKeys(boolean allowDuplicateKeys) {

    }

    @Override
    public Token nextToken() throws IOException {
        return null;
    }

    @Override
    public void skipChildren() throws IOException {

    }

    @Override
    public Token currentToken() {
        return null;
    }

    @Override
    public String currentName() throws IOException {
        return "";
    }

    @Override
    public String text() throws IOException {
        return "";
    }

    @Override
    public CharBuffer charBuffer() throws IOException {
        return null;
    }

    @Override
    public Object objectText() throws IOException {
        return null;
    }

    @Override
    public Object objectBytes() throws IOException {
        return null;
    }

    @Override
    public boolean hasTextCharacters() {
        return false;
    }

    @Override
    public char[] textCharacters() throws IOException {
        return new char[0];
    }

    @Override
    public int textLength() throws IOException {
        return 0;
    }

    @Override
    public int textOffset() throws IOException {
        return 0;
    }

    @Override
    public Number numberValue() throws IOException {
        return null;
    }

    @Override
    public NumberType numberType() throws IOException {
        return null;
    }

    @Override
    public byte[] binaryValue() throws IOException {
        return new byte[0];
    }

    @Override
    public XContentLocation getTokenLocation() {
        return null;
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public void close() throws IOException {

    }
}
