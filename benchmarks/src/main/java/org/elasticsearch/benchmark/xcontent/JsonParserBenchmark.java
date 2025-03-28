/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.xcontent;

import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for testing and comparing the performance of JsonXContentParser implementation(s)
 */
@Fork(value = 1, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED" })
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(25000)
@State(Scope.Benchmark)
public class JsonParserBenchmark {

    @Param({ "cluster_stats", "index_stats", "node_stats", "many_string_fields", "simple_structure" })
    private String type;

    private byte[] source;
    private XContentParserConfiguration parserConfig;

    @Setup
    public void setup() throws IOException {
        String sourceFile = switch (type) {
            case "cluster_stats" -> "monitor_cluster_stats.json";
            case "index_stats" -> "monitor_index_stats.json";
            case "node_stats" -> "monitor_node_stats.json";
            case "many_string_fields" -> "twitter.json";
            case "simple_structure" -> "tests.json";
            default -> throw new IllegalArgumentException("Unknown type [" + type + "]");
        };
        source = readSource(sourceFile);
        parserConfig = XContentParserConfiguration.EMPTY;
    }

    /**
     * We look throughout the file for string fields with a particular content.
     * Forces materializing all the string values.
     */
    @Benchmark
    public int readAndSearchString() throws IOException {
        var matches = 0;
        try (XContentParser parser = XContentType.JSON.xContent().createParser(parserConfig, source)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == XContentParser.Token.VALUE_STRING) {
                    var value = parser.text();
                    if (value.equals("foo")) {
                        ++matches;
                    }
                }
            }
        }
        return matches;
    }

    /**
     * We look throughout the file for numeric fields with a particular content.
     * Forces parsing all the numeric values.
     */
    @Benchmark
    public int readAndSearchNumeric() throws IOException {
        var matches = 0;
        try (XContentParser parser = XContentType.JSON.xContent().createParser(parserConfig, source)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == XContentParser.Token.VALUE_NUMBER) {
                    var value = parser.numberValue();
                    switch (parser.numberType()) {
                        case INT:
                            if (value.intValue() == 0) {
                                ++matches;
                            }
                            break;
                        case DOUBLE:
                            if (value.doubleValue() == 0) {
                                ++matches;
                            }
                            break;
                    }
                }
            }
        }
        return matches;
    }

    /**
     * We look throughout the file for a field with a given name.
     * Measures performances for partial deserialization/parsing.
     */
    @Benchmark
    public boolean readAndSearchField() throws IOException {
        try (XContentParser parser = XContentType.JSON.xContent().createParser(parserConfig, source)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == XContentParser.Token.FIELD_NAME) {
                    var fieldName = parser.currentName();
                    if (fieldName.equals("size_in_bytes")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private byte[] readSource(String fileName) throws IOException {
        try (var stream = FilterContentBenchmark.class.getResourceAsStream(fileName)) {
            if (stream == null) {
                return null;
            }
            return stream.readAllBytes();
        }
    }
}
