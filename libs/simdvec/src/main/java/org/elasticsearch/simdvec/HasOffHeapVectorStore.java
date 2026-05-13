/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec;

/**
 * Marker interface implemented by {@code KnnVectorValues} views backed by an
 * off-heap {@link OffHeapFloatVectorStore} or {@link OffHeapByteVectorStore}.
 * Used by the vector scorer factory to bypass on-heap materialization when the
 * underlying storage is already off-heap.
 *
 * @param <S> the concrete off-heap store type ({@link OffHeapFloatVectorStore} or
 *            {@link OffHeapByteVectorStore})
 */
public interface HasOffHeapVectorStore<S> {
    /** Returns the underlying off-heap vector store. */
    S offHeapStore();
}
