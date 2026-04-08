/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.benchmark.vector.scorer;

import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.nativeaccess.NativeAccess;
import org.elasticsearch.nativeaccess.VectorSimilarityFunctions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.rethrow;

/**
 * Benchmarks to measure FFM downcall overhead and the cost of different
 * MemorySegment arena types (confined vs shared).
 *
 * Experiments:
 * 1. dotProduct with confined arena segments (current production path)
 * 2. dotProduct with shared arena segments (CAS on each downcall for liveness check)
 * 3. dotProduct with raw long addresses (no segment overhead at all) [not yet possible
 *    through getHandle, but confined vs shared isolates the CAS cost]
 */
@Fork(value = 3, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class FFMOverheadBenchmark {

    static {
        Utils.configureBenchmarkLogging();
    }

    static final ValueLayout.OfFloat LAYOUT_LE_FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @Param({ "1", "768", "1024" })
    public int size;

    private MethodHandle nativeImpl;

    private Arena confinedArena;
    private MemorySegment confinedSegA, confinedSegB;

    private Arena sharedArena;
    private MemorySegment sharedSegA, sharedSegB;

    static final VectorSimilarityFunctions vectorSimilarityFunctions = NativeAccess.instance().getVectorSimilarityFunctions().orElseThrow();

    @Setup(Level.Iteration)
    public void init() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        float[] floatsA = new float[size];
        float[] floatsB = new float[size];
        for (int i = 0; i < size; ++i) {
            floatsA[i] = random.nextFloat();
            floatsB[i] = random.nextFloat();
        }

        nativeImpl = vectorSimilarityFunctions.getHandle(
            VectorSimilarityFunctions.Function.DOT_PRODUCT,
            VectorSimilarityFunctions.DataType.FLOAT32,
            VectorSimilarityFunctions.Operation.SINGLE
        );

        confinedArena = Arena.ofConfined();
        confinedSegA = confinedArena.allocate((long) size * Float.BYTES);
        MemorySegment.copy(floatsA, 0, confinedSegA, LAYOUT_LE_FLOAT, 0L, size);
        confinedSegB = confinedArena.allocate((long) size * Float.BYTES);
        MemorySegment.copy(floatsB, 0, confinedSegB, LAYOUT_LE_FLOAT, 0L, size);

        sharedArena = Arena.ofShared();
        sharedSegA = sharedArena.allocate((long) size * Float.BYTES);
        MemorySegment.copy(floatsA, 0, sharedSegA, LAYOUT_LE_FLOAT, 0L, size);
        sharedSegB = sharedArena.allocate((long) size * Float.BYTES);
        MemorySegment.copy(floatsB, 0, sharedSegB, LAYOUT_LE_FLOAT, 0L, size);
    }

    @TearDown
    public void teardown() {
        confinedArena.close();
        sharedArena.close();
    }

    @Benchmark
    public float confinedSegment() {
        try {
            return (float) nativeImpl.invokeExact(confinedSegA, confinedSegB, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Benchmark
    public float sharedSegment() {
        try {
            return (float) nativeImpl.invokeExact(sharedSegA, sharedSegB, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
