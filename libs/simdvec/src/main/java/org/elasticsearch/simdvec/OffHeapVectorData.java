/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.simdvec;

import org.elasticsearch.core.CheckedFunction;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/**
 * Implemented by off-heap native-memory vector stores that can resolve ordinal-based byte offsets
 * to raw native addresses without per-call allocation.
 *
 * <p>Unlike {@link org.elasticsearch.core.DirectAccessInput}, whose
 * {@code withMemorySegmentSlices} contract requires a {@code MemorySegment[]} array and {@code count}
 * fresh {@link MemorySegment#asSlice} objects per call, this interface writes raw 64-bit pointer
 * values directly into the caller's pre-allocated output buffer — zero per-call allocation on the
 * bulk-sparse hot path during HNSW graph construction.
 *
 * <p>Recognition happens inside {@link IndexInputUtils}: an {@link org.apache.lucene.store.IndexInput}
 * that also implements this interface is routed to the arithmetic resolution path in
 * {@link IndexInputUtils#withSliceAddresses} and {@link IndexInputUtils#withSlice}.
 */
interface OffHeapVectorData {

    /**
     * Resolves {@code count} byte offsets to raw native addresses and writes them into {@code addrsOut},
     * starting at index 0. Each offset is the byte start of one vector: {@code offset = ordinal * vectorByteSize}.
     *
     * <p>Addresses are written as raw 64-bit values via {@link java.lang.foreign.ValueLayout#JAVA_LONG}
     * (pointer-width on 64-bit JVMs), avoiding the {@link MemorySegment#ofAddress} wrapper that
     * {@link java.lang.foreign.ValueLayout#ADDRESS} would allocate.
     *
     * @param offsets  byte offsets for each range (caller-owned, not modified)
     * @param length   byte length of each range (same for all; must not straddle a page boundary)
     * @param count    number of offsets to resolve
     * @param addrsOut output buffer; must have capacity for at least {@code count} pointer-width entries.
     *                 The segment may be larger and may be reused across calls; only entries {@code [0, count)}
     *                 are written by this method.
     */
    void sliceAddresses(long[] offsets, int length, int count, MemorySegment addrsOut);

    /**
     * Returns a read-only {@link MemorySegment} view of the data at {@code offset} with the given
     * {@code length}.
     *
     * <p>The segment is valid for the lifetime of the backing arena.
     *
     * @param offset byte offset within the virtual address space of the store
     * @param length byte length of the slice
     * @return a read-only native segment backed by the store's page
     */
    <R> R withSlice(long offset, long length, CheckedFunction<MemorySegment, R, IOException> action) throws IOException;
}
