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
 * Off-heap byte vector storage backed by a single contiguous {@link MemorySegment}.
 * The buffer doubles in capacity when full (realloc-style), using a separate
 * {@link Arena} per buffer so old allocations can be freed after copy.
 * The contiguous layout enables fast bulk scoring via direct ordinal offsets.
 */
public class OffHeapByteVectorStore implements Closeable {

    private static final Logger logger = LogManager.getLogger(OffHeapByteVectorStore.class);
    static final int INITIAL_CAPACITY = 64;

    private final int dim;
    private Arena bufferArena;
    private MemorySegment buffer;
    private int capacity;
    private int count;
    private int resizeCount;
    private long totalBytesCopied;

    public OffHeapByteVectorStore(int dim) {
        this.dim = dim;
        this.bufferArena = Arena.ofShared();
        this.capacity = INITIAL_CAPACITY;
        this.buffer = bufferArena.allocate((long) capacity * dim);
    }

    public void addVector(byte[] vector) {
        if (count == capacity) {
            grow();
        }
        MemorySegment.copy(vector, 0, buffer, ValueLayout.JAVA_BYTE, (long) count * dim, dim);
        count++;
    }

    private void grow() {
        int oldCapacity = capacity;
        int newCapacity = oldCapacity * 2;
        long bytesToCopy = (long) count * dim;

        Arena newArena = Arena.ofShared();
        MemorySegment newBuffer = newArena.allocate((long) newCapacity * dim);
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

    public byte[] getVector(int i) {
        return getVectorSegment(i).toArray(ValueLayout.JAVA_BYTE);
    }

    public MemorySegment getVectorSegment(int i) {
        return buffer.asSlice((long) i * dim, dim);
    }

    /** Returns the contiguous buffer backing all stored vectors. */
    public MemorySegment getBufferSegment() {
        return buffer;
    }

    public int size() {
        return count;
    }

    @Override
    public void close() {
        logger.info(
            "closed: [{}] vectors stored, capacity [{}], [{}] resizes, [{}] bytes allocated, [{}] bytes copied total",
            count,
            capacity,
            resizeCount,
            (long) capacity * dim,
            totalBytesCopied
        );
        bufferArena.close();
    }
}
