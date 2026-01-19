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
    int64_t subRet0 = 0;
    int64_t subRet1 = 0;
    int64_t subRet2 = 0;
    int64_t subRet3 = 0;

    const uint8_t* query_j0 = (const uint8_t*)query;
    const uint8_t* query_j1 = (const uint8_t*)query + length;
    const uint8_t* query_j2 = (const uint8_t*)query + 2 * length;
    const uint8_t* query_j3 = (const uint8_t*)query + 3 * length;

    const int chunk_size = svcntb();
    const svbool_t all_vec = svptrue_b8();

    int i = 0;
    while (i < length) {
        int cycle = 0;

        svuint8_t qDot0 = svdup_n_u8(0);
        svuint8_t qDot1 = svdup_n_u8(0);
        svuint8_t qDot2 = svdup_n_u8(0);
        svuint8_t qDot3 = svdup_n_u8(0);

        do {
            svbool_t pg_vec = svwhilelt_b8((unsigned int)i, (unsigned int)length);
            svuint8_t a_vec = svld1_u8(pg_vec, (const uint8_t*)a + i);
            svuint8_t q0_vec = svld1_u8(pg_vec, query_j0 + i);
            svuint8_t q1_vec = svld1_u8(pg_vec, query_j1 + i);
            svuint8_t q2_vec = svld1_u8(pg_vec, query_j2 + i);
            svuint8_t q3_vec = svld1_u8(pg_vec, query_j3 + i);

            qDot0 = svadd_u8_z(all_vec, qDot0, svcnt_u8_x(all_vec, svand_u8_m(all_vec, a_vec, q0_vec)));
            qDot1 = svadd_u8_z(all_vec, qDot1, svcnt_u8_x(all_vec, svand_u8_m(all_vec, a_vec, q1_vec)));
            qDot2 = svadd_u8_z(all_vec, qDot2, svcnt_u8_x(all_vec, svand_u8_m(all_vec, a_vec, q2_vec)));
            qDot3 = svadd_u8_z(all_vec, qDot3, svcnt_u8_x(all_vec, svand_u8_m(all_vec, a_vec, q3_vec)));

            i += chunk_size;
            ++cycle;
        } while (i < length && cycle < 31);

        subRet0 += svaddv_u8(all_vec, qDot0);
        subRet1 += svaddv_u8(all_vec, qDot1);
        subRet2 += svaddv_u8(all_vec, qDot2);
        subRet3 += svaddv_u8(all_vec, qDot3);
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
    int c = 0;
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
