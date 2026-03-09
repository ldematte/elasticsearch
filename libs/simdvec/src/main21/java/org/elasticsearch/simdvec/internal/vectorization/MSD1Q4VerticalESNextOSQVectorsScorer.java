/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.simdvec.internal.vectorization;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.nativeaccess.NativeAccess;
import org.elasticsearch.simdvec.ESNextOSQVectorsScorer;
import org.elasticsearch.simdvec.internal.IndexInputUtils;
import org.elasticsearch.simdvec.internal.Similarities;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.elasticsearch.simdvec.internal.vectorization.MemorySegmentESNextOSQVectorsScorer.MemorySegmentScorer.FOUR_BIT_SCALE;
import static org.elasticsearch.simdvec.internal.vectorization.MemorySegmentESNextOSQVectorsScorer.MemorySegmentScorer.ONE_BIT_SCALE;

/**
 * Native-only scorer for the vertical (columnar) D1Q4 data layout.
 * Requires native vector similarity functions; throws if not available.
 */
public final class MSD1Q4VerticalESNextOSQVectorsScorer extends ESNextOSQVectorsScorer {

    private static final boolean SUPPORTS_HEAP_SEGMENTS = Runtime.version().feature() >= 22;

    private byte[] scratch;

    public MSD1Q4VerticalESNextOSQVectorsScorer(IndexInput in, int dimensions, int dataLength, int bulkSize) {
        super(in, (byte) 4, (byte) 1, dimensions, dataLength, bulkSize);
        if (NativeAccess.instance().getVectorSimilarityFunctions().isEmpty()) {
            throw new IllegalStateException("Native vector similarity functions are required for the vertical D1Q4 scorer");
        }
        IndexInputUtils.checkInputType(in);
    }

    private byte[] getScratch(int len) {
        if (scratch == null || scratch.length < len) {
            scratch = new byte[len];
        }
        return scratch;
    }

    @Override
    public float scoreBulk(
        byte[] q,
        float queryLowerInterval,
        float queryUpperInterval,
        int queryComponentSum,
        float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction,
        float centroidDp,
        float[] scores,
        int bulkSize
    ) throws IOException {
        if (SUPPORTS_HEAP_SEGMENTS) {
            var querySegment = MemorySegment.ofArray(q);
            var scoresSegment = MemorySegment.ofArray(scores);
            nativeQuantizeScoreBulk(querySegment, bulkSize, scoresSegment);
            return nativeApplyCorrectionsBulk(
                queryLowerInterval,
                queryUpperInterval,
                queryComponentSum,
                queryAdditionalCorrection,
                similarityFunction,
                centroidDp,
                scoresSegment,
                bulkSize
            );
        } else {
            try (var arena = Arena.ofConfined()) {
                var querySegment = arena.allocate(q.length, 32);
                var scoresSegment = arena.allocate((long) scores.length * Float.BYTES, 32);
                MemorySegment.copy(q, 0, querySegment, ValueLayout.JAVA_BYTE, 0, q.length);
                nativeQuantizeScoreBulk(querySegment, bulkSize, scoresSegment);
                var maxScore = nativeApplyCorrectionsBulk(
                    queryLowerInterval,
                    queryUpperInterval,
                    queryComponentSum,
                    queryAdditionalCorrection,
                    similarityFunction,
                    centroidDp,
                    scoresSegment,
                    bulkSize
                );
                MemorySegment.copy(scoresSegment, ValueLayout.JAVA_FLOAT, 0, scores, 0, scores.length);
                return maxScore;
            }
        }
    }

    private void nativeQuantizeScoreBulk(MemorySegment querySegment, int count, MemorySegment scoresSegment) throws IOException {
        var datasetLengthInBytes = (long) length * count;
        IndexInputUtils.withSlice(in, datasetLengthInBytes, this::getScratch, datasetSegment -> {
            Similarities.dotProductD1Q4VerticalBulk(datasetSegment, querySegment, length, count, scoresSegment);
            return null;
        });
    }

    private float nativeApplyCorrectionsBulk(
        float queryLowerInterval,
        float queryUpperInterval,
        int queryComponentSum,
        float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction,
        float centroidDp,
        MemorySegment scoresSegment,
        int bulkSize
    ) throws IOException {
        return IndexInputUtils.withSlice(
            in,
            16L * bulkSize,
            this::getScratch,
            seg -> ScoreCorrections.nativeApplyCorrectionsBulk(
                similarityFunction,
                seg,
                bulkSize,
                dimensions,
                queryLowerInterval,
                queryUpperInterval,
                queryComponentSum,
                queryAdditionalCorrection,
                FOUR_BIT_SCALE,
                ONE_BIT_SCALE,
                centroidDp,
                scoresSegment
            )
        );
    }
}
