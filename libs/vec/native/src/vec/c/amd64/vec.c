

#include <emmintrin.h>
#include <immintrin.h>
#include <vec.h>

#ifndef STRIDE_BYTES_LEN
#define STRIDE_BYTES_LEN 32
#endif

// Horizontally add 8 singed 32-bit integers
static inline int hsum_i32_8(const __m256i a) {
    const __m128i sum128 = _mm_add_epi32(_mm256_castsi256_si128(a), _mm256_extractf128_si256(a, 1));
    const __m128i hi64 = _mm_unpackhi_epi64(sum128, sum128);
    const __m128i sum64 = _mm_add_epi32(hi64, sum128);
    const __m128i hi32  = _mm_shuffle_epi32(sum64, _MM_SHUFFLE(2, 3, 0, 1));
    return _mm_cvtsi128_si32(_mm_add_epi32(sum64, hi32));
}

EXPORT int stride() {
    return STRIDE_BYTES_LEN;
}

EXPORT int dot8s(const void* a, const void* b, int dims) {

	// Init accumulator(s) with 0
	__m256i acc1 = _mm256_setzero_si256();

	for(int i = 0; i < dims; i += STRIDE_BYTES_LEN) {

		// Load 32 packed 8-bit integers
		__m256i va = _mm256_loadu_si256(a + i);
		__m256i vb = _mm256_loadu_si256(b + i);

		// Multiply groups of 4 adjacent pairs of 8-bit integers in va, vb
		// -> 4 intermediate 16-bit results.
		// Then accumulate (+=) and store /add
		#if __AVXVNNIINT8__
	    acc1 = _mm256_dpbssd_epi32 (acc1, va, vb);
		#else
		// If _mm256_dpbssd_epi32 is not available, the older instructions support only unsigned for the first operand.
		// Work around this with -a * b --> a * -b
		// Get absolute values of va vector
    	const __m256i abs_va = _mm256_sign_epi8(va, va);

		// Negate vb when va is negative (flip the sign of the vb vector on va negative values)
    	const __m256i signed_vb = _mm256_sign_epi8(vb, va);

		#if __AVXVNNI__
    	acc1 = _mm256_dpbusd_epi32(acc1, abs_va, signed_vb);
		#else
		// Perform multiplication and create 16-bit values
		// Vertically multiply each unsigned 8-bit integer from abs_va with the corresponding
		// signed 8-bit integer from signed_vb, producing intermediate signed 16-bit integers.
		// Horizontally add adjacent pairs of intermediate signed 16-bit integers, and pack the results.
    	const __m256i dot = _mm256_maddubs_epi16(abs_va, signed_vb);
		const __m256i ones = _mm256_set1_epi16(1);
    	acc1 = _mm256_add_epi32(_mm256_madd_epi16(ones, dot), acc1);
		#endif
		#endif
	}
	return hsum_i32_8(acc1);
}

int dot8s_vec2(const void* a, const void* b, int dims) {

	// Init accumulator(s) with 0
	__m256i acc1 = _mm256_setzero_si256();
	__m256i acc2 = _mm256_setzero_si256();

	for(int i = 0; i < dims; i += 64) {

		// Load 32 packed 8-bit integers
		__m256i va1 = _mm256_loadu_si256(a + i);
		__m256i vb1 = _mm256_loadu_si256(b + i);
		__m256i va2 = _mm256_loadu_si256(a + i + 32);
		__m256i vb2 = _mm256_loadu_si256(b + i + 32);

		// AVX2 instructions need the first operand to be unsigned

		// Work around this with -a * b --> a * -b
        // Get absolute values of va vector
    	const __m256i abs_va1 = _mm256_sign_epi8(va1, va1);
    	const __m256i abs_va2 = _mm256_sign_epi8(va2, va2);

		// Negate vb when va is negative (flip the sign of the vb vector on va negative values)
    	vb1 = _mm256_sign_epi8(vb1, va1);
    	vb2 = _mm256_sign_epi8(vb2, va2);

		// Perform multiplication and create 16-bit values
		// Vertically multiply each unsigned 8-bit integer from abs_va with the corresponding
		// signed 8-bit integer from signed_vb, producing intermediate signed 16-bit integers.
		// Horizontally add adjacent pairs of intermediate signed 16-bit integers, and pack the results.
    	va1 = _mm256_maddubs_epi16(abs_va1, vb1);
    	va2 = _mm256_maddubs_epi16(abs_va2, vb2);
		const __m256i ones = _mm256_set1_epi16(1);
    	acc1 = _mm256_add_epi32(_mm256_madd_epi16(ones, va1), acc1);
    	acc2 = _mm256_add_epi32(_mm256_madd_epi16(ones, va2), acc2);
	}

	// reduce (accumulate all?)
	const __m256i acc12 = _mm256_add_epi32(acc1, acc2);
	return hsum_i32_8(acc12);
}

