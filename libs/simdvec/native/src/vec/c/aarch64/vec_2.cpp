/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

// This file contains implementations for vector processing functionalities,
// for the "2nd tier" vector capabilities; in the case of ARM, this second tier
// consist of functions for processors supporting the SVE/SVE2
// instruction set.

// Force the preprocessor to pick up SVE intrinsics, and the compiler to emit SVE code
#ifdef __clang__
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+sve"))), apply_to = function)
#elif __GNUC__
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+sve")
#endif

#include <stddef.h>
#include <arm_sve.h>
#include <math.h>
#include "vec.h"
#include "vec_common.h"
#include "aarch64/aarch64_vec_common.h"

static inline int64_t dot_int1_int4_inner(const int8_t* a, const int8_t* query, const int32_t length) {
    const uint64_t* a0 = (const uint64_t*)a;
    const uint64_t* query_j0 = (const uint64_t*)query;
    const uint64_t* query_j1 = (const uint64_t*)(query + length);
    const uint64_t* query_j2 = (const uint64_t*)(query + 2 * length);
    const uint64_t* query_j3 = (const uint64_t*)(query + 3 * length);

    const int sizeof_sv = svcntd() * sizeof(int64_t);
    const svbool_t all_vec = svptrue_b64();

    int r = 0;

    // Init accumulator(s) with 0
    svuint64_t acc0 = svdup_n_u64(0);
    svuint64_t acc1 = svdup_n_u64(0);
    svuint64_t acc2 = svdup_n_u64(0);
    svuint64_t acc3 = svdup_n_u64(0);

    int upperBound = length & ~(sizeof_sv - 1);
    for (; r < upperBound; r += sizeof_sv) {
        svuint64_t value = svld1_u64(all_vec, a0 + r);

        svuint64_t q0_vec = svld1_u64(all_vec, query_j0 + r);
        svuint64_t q1_vec = svld1_u64(all_vec, query_j1 + r);
        svuint64_t q2_vec = svld1_u64(all_vec, query_j2 + r);
        svuint64_t q3_vec = svld1_u64(all_vec, query_j3 + r);

        acc0 = svadd_u64_z(all_vec, acc0, svcnt_u64_x(all_vec, svand_u64_m(all_vec, value, q0_vec)));
        acc1 = svadd_u64_z(all_vec, acc1, svcnt_u64_x(all_vec, svand_u64_m(all_vec, value, q1_vec)));
        acc2 = svadd_u64_z(all_vec, acc2, svcnt_u64_x(all_vec, svand_u64_m(all_vec, value, q2_vec)));
        acc3 = svadd_u64_z(all_vec, acc3, svcnt_u64_x(all_vec, svand_u64_m(all_vec, value, q3_vec)));
    }

    int64_t subRet0 = svaddv_u64(all_vec, acc0);
    int64_t subRet1 = svaddv_u64(all_vec, acc1);
    int64_t subRet2 = svaddv_u64(all_vec, acc2);
    int64_t subRet3 = svaddv_u64(all_vec, acc3);

    for (; r < length; r++) {
        int8_t value = *(a + r);
        int8_t q0 = *(query + r);
        subRet0 += __builtin_popcount(q0 & value & 0xFF);
        int8_t q1 = *(query + r + length);
        subRet1 += __builtin_popcount(q1 & value & 0xFF);
        int8_t q2 = *(query + r + 2 * length);
        subRet2 += __builtin_popcount(q2 & value & 0xFF);
        int8_t q3 = *(query + r + 3 * length);
        subRet3 += __builtin_popcount(q3 & value & 0xFF);
    }

    return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
}

EXPORT int64_t vec_dot_int1_int4_2(const int8_t* a, const int8_t* query, const int32_t length) {
    return dot_int1_int4_inner(a, query, length);
}

template <int64_t(*mapper)(const int32_t, const int32_t*)>
static inline void dot_int1_int4_inner_bulk(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results
) {
    const int chunk_size = svcntd() * sizeof(int64_t);

    const uint64_t* query_j0 = (const uint64_t*)query;
    const uint64_t* query_j1 = (const uint64_t*)(query + length);
    const uint64_t* query_j2 = (const uint64_t*)(query + 2 * length);
    const uint64_t* query_j3 = (const uint64_t*)(query + 3 * length);

    const svbool_t all_vec = svptrue_b64();

    int c = 0;

    for (; c + 3 < count; c += 4) {
        const uint64_t* a0 = (const uint64_t*)(a + mapper(c, offsets) * pitch);
        const uint64_t* a1 = (const uint64_t*)(a + mapper(c + 1, offsets) * pitch);
        const uint64_t* a2 = (const uint64_t*)(a + mapper(c + 2, offsets) * pitch);
        const uint64_t* a3 = (const uint64_t*)(a + mapper(c + 3, offsets) * pitch);

        int64_t subRet0_0 = 0;
        int64_t subRet1_0 = 0;
        int64_t subRet2_0 = 0;
        int64_t subRet3_0 = 0;

        int64_t subRet0_1 = 0;
        int64_t subRet1_1 = 0;
        int64_t subRet2_1 = 0;
        int64_t subRet3_1 = 0;

        int64_t subRet0_2 = 0;
        int64_t subRet1_2 = 0;
        int64_t subRet2_2 = 0;
        int64_t subRet3_2 = 0;

        int64_t subRet0_3 = 0;
        int64_t subRet1_3 = 0;
        int64_t subRet2_3 = 0;
        int64_t subRet3_3 = 0;

        int r = 0;
        if (length >= chunk_size) {

            svuint64_t acc0_0 = svdup_n_u64(0);
            svuint64_t acc1_0 = svdup_n_u64(0);
            svuint64_t acc2_0 = svdup_n_u64(0);
            svuint64_t acc3_0 = svdup_n_u64(0);

            svuint64_t acc0_1 = svdup_n_u64(0);
            svuint64_t acc1_1 = svdup_n_u64(0);
            svuint64_t acc2_1 = svdup_n_u64(0);
            svuint64_t acc3_1 = svdup_n_u64(0);

            svuint64_t acc0_2 = svdup_n_u64(0);
            svuint64_t acc1_2 = svdup_n_u64(0);
            svuint64_t acc2_2 = svdup_n_u64(0);
            svuint64_t acc3_2 = svdup_n_u64(0);

            svuint64_t acc0_3 = svdup_n_u64(0);
            svuint64_t acc1_3 = svdup_n_u64(0);
            svuint64_t acc2_3 = svdup_n_u64(0);
            svuint64_t acc3_3 = svdup_n_u64(0);

            int upperBound = length & ~(chunk_size - 1);
            for (; r < upperBound; r += chunk_size) {
                const svuint64_t q0 = svld1_u64(all_vec, query_j0 + r);
                const svuint64_t q1 = svld1_u64(all_vec, query_j1 + r);
                const svuint64_t q2 = svld1_u64(all_vec, query_j2 + r);
                const svuint64_t q3 = svld1_u64(all_vec, query_j3 + r);

                const svuint64_t v0 = svld1_u64(all_vec, a0 + r);
                const svuint64_t v1 = svld1_u64(all_vec, a1 + r);
                const svuint64_t v2 = svld1_u64(all_vec, a2 + r);
                const svuint64_t v3 = svld1_u64(all_vec, a3 + r);

                acc0_0 = svadd_u64_z(all_vec, acc0_0, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v0, q0)));
                acc1_0 = svadd_u64_z(all_vec, acc1_0, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v0, q1)));
                acc2_0 = svadd_u64_z(all_vec, acc2_0, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v0, q2)));
                acc3_0 = svadd_u64_z(all_vec, acc3_0, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v0, q3)));

                acc0_1 = svadd_u64_z(all_vec, acc0_1, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v1, q0)));
                acc1_1 = svadd_u64_z(all_vec, acc1_1, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v1, q1)));
                acc2_1 = svadd_u64_z(all_vec, acc2_1, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v1, q2)));
                acc3_1 = svadd_u64_z(all_vec, acc3_1, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v1, q3)));

                acc0_2 = svadd_u64_z(all_vec, acc0_2, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v2, q0)));
                acc1_2 = svadd_u64_z(all_vec, acc1_2, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v2, q1)));
                acc2_2 = svadd_u64_z(all_vec, acc2_2, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v2, q2)));
                acc3_2 = svadd_u64_z(all_vec, acc3_2, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v2, q3)));

                acc0_3 = svadd_u64_z(all_vec, acc0_3, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v3, q0)));
                acc1_3 = svadd_u64_z(all_vec, acc1_3, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v3, q1)));
                acc2_3 = svadd_u64_z(all_vec, acc2_3, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v3, q2)));
                acc3_3 = svadd_u64_z(all_vec, acc3_3, svcnt_u64_x(all_vec, svand_u64_m(all_vec, v3, q3)));
            }

            subRet0_0 += svaddv_u64(all_vec, acc0_0);
            subRet1_0 += svaddv_u64(all_vec, acc1_0);
            subRet2_0 += svaddv_u64(all_vec, acc2_0);
            subRet3_0 += svaddv_u64(all_vec, acc3_0);

            subRet0_1 += svaddv_u64(all_vec, acc0_1);
            subRet1_1 += svaddv_u64(all_vec, acc1_1);
            subRet2_1 += svaddv_u64(all_vec, acc2_1);
            subRet3_1 += svaddv_u64(all_vec, acc3_1);

            subRet0_2 += svaddv_u64(all_vec, acc0_2);
            subRet1_2 += svaddv_u64(all_vec, acc1_2);
            subRet2_2 += svaddv_u64(all_vec, acc2_2);
            subRet3_2 += svaddv_u64(all_vec, acc3_2);

            subRet0_3 += svaddv_u64(all_vec, acc0_3);
            subRet1_3 += svaddv_u64(all_vec, acc1_3);
            subRet2_3 += svaddv_u64(all_vec, acc2_3);
            subRet3_3 += svaddv_u64(all_vec, acc3_3);
        }
        for (; r < length; r++) {
            int64_t v0 = *((int64_t*)(a0 + r));
            int64_t v1 = *((int64_t*)(a1 + r));
            int64_t v2 = *((int64_t*)(a2 + r));
            int64_t v3 = *((int64_t*)(a3 + r));

            int64_t q0 = *((int64_t*)(query_j0 + r));
            int64_t q1 = *((int64_t*)(query_j1 + r));
            int64_t q2 = *((int64_t*)(query_j2 + r));
            int64_t q3 = *((int64_t*)(query_j3 + r));

            subRet0_0 += __builtin_popcount(q0 & v0 & 0xFF);
            subRet1_0 += __builtin_popcount(q1 & v0 & 0xFF);
            subRet2_0 += __builtin_popcount(q2 & v0 & 0xFF);
            subRet3_0 += __builtin_popcount(q3 & v0 & 0xFF);

            subRet0_1 += __builtin_popcount(q0 & v1 & 0xFF);
            subRet1_1 += __builtin_popcount(q1 & v1 & 0xFF);
            subRet2_1 += __builtin_popcount(q2 & v1 & 0xFF);
            subRet3_1 += __builtin_popcount(q3 & v1 & 0xFF);

            subRet0_2 += __builtin_popcount(q0 & v2 & 0xFF);
            subRet1_2 += __builtin_popcount(q1 & v2 & 0xFF);
            subRet2_2 += __builtin_popcount(q2 & v2 & 0xFF);
            subRet3_2 += __builtin_popcount(q3 & v2 & 0xFF);

            subRet0_3 += __builtin_popcount(q0 & v3 & 0xFF);
            subRet1_3 += __builtin_popcount(q1 & v3 & 0xFF);
            subRet2_3 += __builtin_popcount(q2 & v3 & 0xFF);
            subRet3_3 += __builtin_popcount(q3 & v3 & 0xFF);
        }
        results[c] = subRet0_0 + (subRet1_0 << 1) + (subRet2_0 << 2) + (subRet3_0 << 3);
        results[c + 1] = subRet0_1 + (subRet1_1 << 1) + (subRet2_1 << 2) + (subRet3_1 << 3);
        results[c + 2] = subRet0_2 + (subRet1_2 << 1) + (subRet2_2 << 2) + (subRet3_2 << 3);
        results[c + 3] = subRet0_3 + (subRet1_3 << 1) + (subRet2_3 << 2) + (subRet3_3 << 3);
    }

    for (; c < count; c++) {
        const int8_t* a0 = a + mapper(c, offsets) * pitch;
        results[c] = (f32_t)dot_int1_int4_inner(a0, query, length);
    }
}

EXPORT void vec_dot_int1_int4_bulk_2(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    dot_int1_int4_inner_bulk<identity_mapper>(a, query, length, length, NULL, count, results);
}

EXPORT void vec_dot_int1_int4_bulk_offsets_2(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results) {
    dot_int1_int4_inner_bulk<array_mapper>(a, query, length, pitch, offsets, count, results);
}


#ifdef __clang__
#pragma clang attribute pop
#elif __GNUC__
#pragma GCC pop_options
#endif
