/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.io.Closeable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap float vector storage backed by a single contiguous {@link MemorySegment}.
 * The buffer doubles in capacity when full (realloc-style), using a separate
 * {@link Arena} per buffer so old allocations can be freed after copy.
 * The contiguous layout enables fast bulk scoring via direct ordinal offsets.
 */
public class OffHeapFloatVectorStore implements Closeable {

    private static final Logger logger = LogManager.getLogger(OffHeapFloatVectorStore.class);
    static final int INITIAL_CAPACITY = 64;

    private final int dim;
    private final long vectorByteSize;
    private Arena bufferArena;
    private MemorySegment buffer;
    private int capacity;
    private int count;
    private int resizeCount;
    private long totalBytesCopied;

    public OffHeapFloatVectorStore(int dim) {
        this.dim = dim;
        this.vectorByteSize = (long) dim * Float.BYTES;
        this.bufferArena = Arena.ofShared();
        this.capacity = INITIAL_CAPACITY;
        this.buffer = bufferArena.allocate(capacity * vectorByteSize);
    }

    public void addVector(float[] vector) {
        if (count == capacity) {
            grow();
        }
        MemorySegment.copy(vector, 0, buffer, ValueLayout.JAVA_FLOAT, count * vectorByteSize, dim);
        count++;
    }

    private void grow() {
        int oldCapacity = capacity;
        int newCapacity = oldCapacity * 2;
        long bytesToCopy = (long) count * vectorByteSize;

        Arena newArena = Arena.ofShared();
        MemorySegment newBuffer = newArena.allocate(newCapacity * vectorByteSize);
        MemorySegment.copy(buffer, 0, newBuffer, 0, bytesToCopy);

        Arena oldArena = bufferArena;
        bufferArena = newArena;
        buffer = newBuffer;
        capacity = newCapacity;
        oldArena.close();

        resizeCount++;
        totalBytesCopied += bytesToCopy;
        logger.info("resized: [{}] -> [{}] vectors, copied [{}] bytes", oldCapacity, newCapacity, bytesToCopy);
    }

    public float[] getVector(int i) {
        return getVectorSegment(i).toArray(ValueLayout.JAVA_FLOAT);
    }

    public MemorySegment getVectorSegment(int i) {
        return buffer.asSlice((long) i * vectorByteSize, vectorByteSize);
    }

    /** Returns the contiguous buffer backing all stored vectors. */
    public MemorySegment getBufferSegment() {
        return buffer;
    }

    public int size() {
        return count;
    }

    /**
     * Divides each vector's components by the corresponding magnitude.
     * Mutates the off-heap segments in place.
     *
     * @param magnitudes array containing magnitude values
     * @param offset     start index in the magnitudes array
     * @param length     number of vectors to normalize (must equal {@link #size()})
     */
    public void normalizeByMagnitudes(float[] magnitudes, int offset, int length) {
        assert length == count;
        for (int i = 0; i < length; i++) {
            MemorySegment seg = getVectorSegment(i);
            float magnitude = magnitudes[offset + i];
            for (int j = 0; j < dim; j++) {
                float val = seg.getAtIndex(ValueLayout.JAVA_FLOAT, j);
                seg.setAtIndex(ValueLayout.JAVA_FLOAT, j, val / magnitude);
            }
        }
    }

    @Override
    public void close() {
        logger.info(
            "closed: [{}] vectors stored, capacity [{}], [{}] resizes, [{}] bytes allocated, [{}] bytes copied total",
            count,
            capacity,
            resizeCount,
            capacity * vectorByteSize,
            totalBytesCopied
        );
        bufferArena.close();
    }
}
