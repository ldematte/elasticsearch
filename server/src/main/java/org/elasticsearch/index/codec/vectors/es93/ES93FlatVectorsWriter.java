/*
 * Copyright Elasticsearch B.V., and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * "Elastic License 2.0", the "GNU Affero General Public License v3.0
 * only", and the "Server Side Public License, v 1" (subject to the terms in
 * the LICENSE file).
 */

package org.elasticsearch.index.codec.vectors.es93;

import org.apache.lucene.codecs.hnsw.FlatFieldVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorsScorer;
import org.apache.lucene.codecs.hnsw.FlatVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99FlatVectorsWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Sorter;
import org.apache.lucene.util.hnsw.CloseableRandomVectorScorerSupplier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around {@link Lucene99FlatVectorsWriter} that installs ES's off-heap
 * per-field writer as the storage strategy and tracks the writers in order to
 * release their off-heap stores on {@link #close()}.
 *
 * <p>All write/merge/flush logic is delegated to {@link Lucene99FlatVectorsWriter}.
 */
class ES93FlatVectorsWriter extends FlatVectorsWriter {

    private final Lucene99FlatVectorsWriter delegate;
    private final List<ES93FlatFieldVectorsWriter<?>> fields = new ArrayList<>();

    ES93FlatVectorsWriter(SegmentWriteState state, FlatVectorsScorer scorer) throws IOException {
        super(scorer);
        this.delegate = new Lucene99FlatVectorsWriter(state, scorer, fieldInfo -> {
            var fieldWriter = ES93FlatFieldVectorsWriter.create(fieldInfo);
            fields.add(fieldWriter);
            return fieldWriter;
        });
    }

    @Override
    public FlatFieldVectorsWriter<?> addField(FieldInfo fieldInfo) throws IOException {
        return delegate.addField(fieldInfo);
    }

    @Override
    public void flush(int maxDoc, Sorter.DocMap sortMap) throws IOException {
        delegate.flush(maxDoc, sortMap);
    }

    @Override
    public void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
        delegate.mergeOneField(fieldInfo, mergeState);
    }

    @Override
    public CloseableRandomVectorScorerSupplier mergeOneFieldToIndex(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
        return delegate.mergeOneFieldToIndex(fieldInfo, mergeState);
    }

    @Override
    public void finish() throws IOException {
        delegate.finish();
    }

    @Override
    public void close() throws IOException {
        for (ES93FlatFieldVectorsWriter<?> field : fields) {
            field.closeStore();
        }
        delegate.close();
    }

    @Override
    public long ramBytesUsed() {
        return delegate.ramBytesUsed();
    }
}
