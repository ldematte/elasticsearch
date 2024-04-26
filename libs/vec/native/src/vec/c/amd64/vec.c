/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

#include <stddef.h>
#include <stdint.h>
#include "vec.h"

#include <emmintrin.h>
#include <immintrin.h>

#ifndef DOT8_STRIDE_BYTES_LEN
#define DOT8_STRIDE_BYTES_LEN 32
#endif

#ifndef SQR8S_STRIDE_BYTES_LEN
#define SQR8S_STRIDE_BYTES_LEN 16
#endif

#ifdef _MSC_VER
#include <intrin.h>
#elif __GNUC__
#include <x86intrin.h>
#elif __clang__
#include <x86intrin.h>
#endif

/*
  Latest micro-benchmark results:

  024-04-25T10:58:49+02:00
  Running ./dot8
  Run on (12 X 4500 MHz CPU s)
  CPU Caches:
    L1 Data 32 KiB (x6)
    L1 Instruction 32 KiB (x6)
    L2 Unified 256 KiB (x6)
    L3 Unified 12288 KiB (x1)
  Load Average: 0.85, 1.13, 1.30
  ---------------------------------------------------------
  Benchmark               Time             CPU   Iterations
  ---------------------------------------------------------
  BM_dot8_scalar        539 ns          538 ns      1000000
  BM_dot8_vec          20.0 ns         20.0 ns     37549169
  BM_dot8_vec2         19.7 ns         19.7 ns     37993986
  BM_sqr8_scalar        560 ns          560 ns      1236317
  BM_sqr8_vec          36.0 ns         36.0 ns     17278676
  BM_sqr8_vec2         36.3 ns         36.3 ns     17363379
*/


// input:  functionNumber = leaf(eax). Subleaf is always 0
// output: output[0] = eax, output[1] = ebx, output[2] = ecx, output[3] = edx
static inline void cpuid(int output[4], int functionNumber) {
#if defined(__GNUC__) || defined(__clang__)
    // use inline assembly, Gnu/AT&T syntax
    int a, b, c, d;
    __asm("cpuid" : "=a"(a), "=b"(b), "=c"(c), "=d"(d) : "a"(functionNumber), "c"(0) : );
    output[0] = a;
    output[1] = b;
    output[2] = c;
    output[3] = d;

#elif defined (_MSC_VER)
    __cpuidex(output, functionNumber, 0);
#else
   #error Unsupported compiler
#endif
}

// horizontally add 8 int32_t
static inline int hsum_i32_8(const __m256i a) {
    const __m128i sum128 = _mm_add_epi32(_mm256_castsi256_si128(a), _mm256_extractf128_si256(a, 1));
    const __m128i hi64 = _mm_unpackhi_epi64(sum128, sum128);
    const __m128i sum64 = _mm_add_epi32(hi64, sum128);
    const __m128i hi32  = _mm_shuffle_epi32(sum64, _MM_SHUFFLE(2, 3, 0, 1));
    return _mm_cvtsi128_si32(_mm_add_epi32(sum64, hi32));
}

EXPORT int vec_caps() {
    int cpuInfo[4] = {-1};
    // Calling __cpuid with 0x0 as the function_id argument
    // gets the number of the highest valid function ID.
    cpuid(cpuInfo, 0);
    int functionIds = cpuInfo[0];
    if (functionIds >= 7) {
        cpuid(cpuInfo, 7);
        int ebx = cpuInfo[1];
        // AVX2 flag is the 5th bit
        // We assume that all processors that have AVX2 also have FMA3
        return (ebx & (1 << 5)) != 0;
    }
    return 0;
}

EXPORT int dot8s_stride() {
    return DOT8_STRIDE_BYTES_LEN;
}

EXPORT int sqr8s_stride() {
    return SQR8S_STRIDE_BYTES_LEN;
}

EXPORT int32_t dot8s(int8_t* a, int8_t* b, size_t dims) {
	// Init accumulator(s) with 0
	__m256i acc1 = _mm256_setzero_si256();

	for(int i = 0; i < dims; i += DOT8_STRIDE_BYTES_LEN) {

		// Load 32 packed 8-bit integers
		__m256i va = _mm256_loadu_si256(a + i);
		__m256i vb = _mm256_loadu_si256(b + i);

		// Multiply groups of 4 adjacent pairs of 8-bit integers in va, vb
		// -> 4 intermediate 16-bit results.
		// Then accumulate (+=) and store /add

		// Get absolute values of va vector
    	const __m256i abs_va = _mm256_sign_epi8(va, va);

		// Negate vb when va is negative ("sign" the values of the vb vectors)
    	const __m256i signed_vb = _mm256_sign_epi8(vb, va);

		// Perform multiplication and create 16-bit values
		// Vertically multiply each unsigned 8-bit integer from abs_va with the corresponding
		// signed 8-bit integer from signed_vb, producing intermediate signed 16-bit integers.
		// Horizontally add adjacent pairs of intermediate signed 16-bit integers, and pack the results.
    	const __m256i dot = _mm256_maddubs_epi16(abs_va, signed_vb);
		const __m256i ones = _mm256_set1_epi16(1);
    	acc1 = _mm256_add_epi32(_mm256_madd_epi16(ones, dot), acc1);
	}

	// reduce (accumulate all)
	return hsum_i32_8(acc1);
}

EXPORT int32_t sqr8s(int8_t *a, int8_t *b, size_t dims) {
    // Init accumulator(s) with 0
	__m256i acc1 = _mm256_setzero_si256();

	for(int i = 0; i < dims; i += SQR8S_STRIDE_BYTES_LEN) {
        // Load 16 packed 8-bit integers
		__m128i va = _mm_lddqu_si128(a + i);
		__m128i vb = _mm_lddqu_si128(b + i);

        const __m256i dist = _mm256_sub_epi16(_mm256_cvtepi8_epi16(va), _mm256_cvtepi8_epi16(vb));

        const __m256i sqr_add = _mm256_madd_epi16(dist, dist);
    	acc1 = _mm256_add_epi32(sqr_add, acc1);
    }

    // reduce (accumulate all)
	return hsum_i32_8(acc1);
}

