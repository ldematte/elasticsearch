/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.simdvec;

import org.apache.lucene.codecs.lucene95.HasIndexSlice;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.core.CheckedFunction;

import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * A read-only {@link IndexInput} view over an off-heap native vector store.
 *
 * <p>The {@link IndexInput} is backed by an {@link OffHeapVectorData}, which exposes a logical space
 * of {@code [0, count * vectorByteSize)}, where {@code count} can grow (e.g. as vectors are appended).
 * Each vector occupies {@code [ord * vectorByteSize, (ord+1) * vectorByteSize)} bytes in this logical space.
 */
public final class OffHeapVectorInput extends IndexInput implements HasIndexSlice, OffHeapVectorData {

    private final List<MemorySegment> pages;
    private final Arena arena;
    private final IntSupplier size;
    private final int pageBytes;
    private final int vectorByteSize;
    private long pos;

    OffHeapVectorInput(String resourceDesc, List<MemorySegment> pages, Arena arena, IntSupplier size, int pageBytes, int vectorByteSize) {
        super(resourceDesc);
        this.pages = pages;
        this.arena = arena;
        this.size = size;
        this.pageBytes = pageBytes;
        this.vectorByteSize = vectorByteSize;
        this.pos = 0;
    }

    /** Clone constructor — shares the same backing store, independent cursor. */
    private OffHeapVectorInput(OffHeapVectorInput other, long pos) {
        super(other.toString());
        this.pages = other.pages;
        this.arena = other.arena;
        this.size = other.size;
        this.pageBytes = other.pageBytes;
        this.vectorByteSize = other.vectorByteSize;
        this.pos = pos;
    }

    // ---- IndexInput basics --------------------------------------------------

    @Override
    public long length() {
        return (long) size.getAsInt() * vectorByteSize;
    }

    @Override
    public long getFilePointer() {
        return pos;
    }

    @Override
    public void seek(long newPos) {
        this.pos = newPos;
    }

    @Override
    public void close() {
        // Store owns the arena lifecycle; the input is a disposable view.
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public OffHeapVectorInput clone() {
        return new OffHeapVectorInput(this, pos);
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) {
        throw new UnsupportedOperationException(
            "OffHeapVectorInput does not support slice(); use resolveAddresses or slice(long,long) instead"
        );
    }

    @Override
    public byte readByte() throws IOException {
        if (pos >= length()) {
            throw new EOFException("read past EOF: pos=" + pos + ", length=" + length());
        }
        int pageIdx = (int) (pos / pageBytes);
        int pageOffset = (int) (pos % pageBytes);
        byte b = pages.get(pageIdx).get(ValueLayout.JAVA_BYTE, pageOffset);
        pos++;
        return b;
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        if (pos + len > length()) {
            throw new EOFException("read past EOF: pos=" + pos + ", len=" + len + ", length=" + length());
        }
        int remaining = len;
        while (remaining > 0) {
            int pageIdx = (int) (pos / pageBytes);
            int pageOffset = (int) (pos % pageBytes);
            int bytesToCopy = Math.min(pageBytes - pageOffset, remaining);
            int elementsToCopy = (int) (bytesToCopy / ValueLayout.JAVA_BYTE.byteSize());
            MemorySegment.copy(pages.get(pageIdx), ValueLayout.JAVA_BYTE, pageOffset, b, offset, elementsToCopy);
            pos += bytesToCopy;
            offset += bytesToCopy;
            remaining -= bytesToCopy;
        }
    }

    @Override
    public IndexInput getSlice() {
        return clone();
    }

    /**
     * Resolves {@code count} byte offsets to raw native addresses with zero per-call allocation.
     * Each offset is mapped to its page via {@code off / pageBytes} and the pageOffset-page position via
     * {@code off % pageBytes}; the page's base address is read once via {@link MemorySegment#address()}.
     *
     * <p>The caller in {@link IndexInputUtils#withSliceAddresses} is responsible for fencing on
     * this input after the native call to prevent the arena from being collected mid-flight.
     */
    @Override
    public void sliceAddresses(long[] offsets, int length, int count, MemorySegment addrsOut) {
        for (int i = 0; i < count; i++) {
            long off = offsets[i];
            int pageIdx = (int) (off / pageBytes);
            long pageOffset = off % pageBytes;
            assert pageOffset + length <= pageBytes
                : "vector straddles a page boundary (off=" + off + ", len=" + length + ", pageBytes=" + pageBytes + ")";
            long addr = pages.get(pageIdx).address() + pageOffset;
            assert ValueLayout.ADDRESS.byteSize() == Long.BYTES;
            addrsOut.setAtIndex(ValueLayout.JAVA_LONG, i, addr);
        }
    }

    /**
     * Executes {@code action} on a read-only view of one vector's bytes from the appropriate page.
     */
    @Override
    public <R> R withSlice(long offset, long length, CheckedFunction<MemorySegment, R, IOException> action) throws IOException {
        int pageIdx = (int) (offset / pageBytes);
        long pageOffset = offset % pageBytes;
        return action.apply(pages.get(pageIdx).asSlice(pageOffset, length).asReadOnly());
    }
}
