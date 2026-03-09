# OSQ Vector Scoring: Data Layout and Architecture

## Overview

This document describes the current data layout and scoring architecture for Optimized Scalar Quantized (OSQ) vectors, focusing on the 1-bit index / 4-bit query case (D1Q4).

## Key Source Files

- `benchmarks/.../VectorScorerOSQBenchmark.java` - benchmark harness, shows how data is prepared and scored
- `libs/simdvec/.../ESNextOSQVectorsScorer.java` - base scorer (scalar fallback)
- `libs/simdvec/.../MemorySegmentESNextOSQVectorsScorer.java` - vectorized scorer dispatcher
- `libs/simdvec/.../MSBitToInt4ESNextOSQVectorsScorer.java` - D1Q4 Panama/native scorer
- `libs/simdvec/.../ScoreCorrections.java` - native correction application
- `libs/simdvec/.../Similarities.java` - native dot-product method handles
- `libs/simdvec/testFixtures/.../VectorScorerTestUtils.java` - data generation and on-disk write helpers
- `server/.../ESNextDiskBBQVectorsFormat.java` - `QuantEncoding` enum, packing and length calculations

## Quantization Basics

### 1-Bit Index Vector (packAsBinary)

Each dimension is quantized to a single bit (0 or 1). Dimensions are packed MSB-first into bytes: 8 dimensions per byte.

For `dims` dimensions:
- `length = ceil(dims / 8)` bytes per vector
- Discretized dimensions: `(dims + 7) / 8 * 8` (rounded up to multiple of 8)

Example with dims=16 and values `[1, 0, 1, 1, 0, 0, 1, 0, ...]`:
```
Byte 0: d0 d1 d2 d3 d4 d5 d6 d7   (MSB to LSB)
Byte 1: d8 d9 d10 d11 d12 d13 d14 d15
```

### 4-Bit Query Vector (transposeHalfByte)

Each dimension is quantized to 4 bits (values 0-15). The 4-bit values are then **bit-transposed**: each of the 4 bit positions is extracted from all dimensions and grouped into a contiguous "bit-plane."

Each bit-plane has `L = ceil(dims/8)` bytes, packed MSB-first (same layout as a 1-bit vector). Total query size: `4 * L` bytes.

Concrete example with dims=16, L=2 bytes per plane (8 bytes total):

Say `d0=13 (0b1101)`, `d1=5 (0b0101)`, etc.

```
q[0]           = bit0 of d0..d7   (MSB-first)   ─┐ bit-plane 0 (weight 1)
q[1]           = bit0 of d8..d15                  ┘

q[L]   = q[2]  = bit1 of d0..d7                 ─┐ bit-plane 1 (weight 2)
q[L+1] = q[3]  = bit1 of d8..d15                 ┘

q[2L]  = q[4]  = bit2 of d0..d7                 ─┐ bit-plane 2 (weight 4)
q[2L+1]= q[5]  = bit2 of d8..d15                 ┘

q[3L]  = q[6]  = bit3 of d0..d7                 ─┐ bit-plane 3 (weight 8)
q[3L+1]= q[7]  = bit3 of d8..d15                 ┘
```

This "bit-transposed" layout reshapes a `dims x 4-bits` matrix from row-major (per-dimension) into column-major (per-bit-position).

## On-Disk Bulk Layout (BULK_SIZE = 32)

Data is written in bulks of 32 vectors by `writeBulkOSQVectorData`. Each bulk has two regions: quantized vectors followed by correction metadata.

### Vectors (horizontal / row-major)

All 32 vectors are written sequentially, each vector's packed bytes contiguous:

```
Offset 0:              v0[byte0] v0[byte1] ... v0[byte_{L-1}]
Offset L:              v1[byte0] v1[byte1] ... v1[byte_{L-1}]
...
Offset 31*L:           v31[byte0] v31[byte1] ... v31[byte_{L-1}]
```

Total: `32 * L` bytes for the vector region.

### Corrections (column-major per field)

After the 32 quantized vectors, 4 correction arrays are written, each with one value per vector:

```
lowerIntervals[0..31]          (32 floats = 128 bytes)
upperIntervals[0..31]          (32 floats = 128 bytes)
componentSums[0..31]           (32 ints   = 128 bytes)
additionalCorrections[0..31]   (32 floats = 128 bytes)
```

Total corrections: `16 * BULK_SIZE = 512` bytes.

### Complete Bulk Layout

```
┌──────────────────────────────────────────────────────────────────┐
│ v0 [L bytes] │ v1 [L bytes] │ ... │ v31 [L bytes]              │  32*L bytes
├──────────────────────────────────────────────────────────────────┤
│ lowerIntervals[0..31]    (32 floats)                            │  128 bytes
│ upperIntervals[0..31]    (32 floats)                            │  128 bytes
│ componentSums[0..31]     (32 ints)                              │  128 bytes
│ additionalCorrections[0..31] (32 floats)                        │  128 bytes
└──────────────────────────────────────────────────────────────────┘
```

## Scoring Algorithm

### Quantized Dot Product (D1Q4)

The quantized dot product between a 1-bit document vector and a 4-bit (bit-transposed) query exploits the bit-plane layout. Each bit-plane of the query is ANDed against the document vector, popcounted, and weighted by its bit position:

```
score = popcount(doc & q[0..L-1])       * 1      // bit-plane 0
      + popcount(doc & q[L..2L-1])      * 2      // bit-plane 1
      + popcount(doc & q[2L..3L-1])     * 4      // bit-plane 2
      + popcount(doc & q[3L..4L-1])     * 8      // bit-plane 3
```

This is mathematically equivalent to `sum_i(doc_bit[i] * query_4bit_value[i])` for all dimensions, but computed via bitwise operations and popcount.

### Score Correction

After computing the quantized dot product (`qcDist`), corrections reconstruct an approximate real-valued score:

```
score = ax * ay * dims
      + lx * ay * docComponentSum
      + ax * ly * queryComponentSum
      + lx * ly * qcDist
```

Where:
- `ax = lowerInterval[doc]` (per-document)
- `lx = (upperInterval[doc] - ax) * indexBitScale` (per-document; indexBitScale = 1.0 for 1-bit)
- `ay = queryLowerInterval` (per-query)
- `ly = (queryUpperInterval - ay) * queryBitScale` (per-query; queryBitScale = 1/15 for 4-bit)
- `docComponentSum` = sum of quantized document components (per-document)
- `queryComponentSum` = sum of quantized query components (per-query)

Then a similarity-specific transform is applied:

- **Euclidean**: `score = max(1 / (1 + additionalCorrection + queryAdditionalCorrection - 2 * score), 0)`
- **Max Inner Product**: `score = scaleMaxInnerProductScore(score + queryAdditionalCorrection + additionalCorrection - centroidDp)`
- **Cosine/Dot Product**: `score = max((1 + score + queryAdditionalCorrection + additionalCorrection - centroidDp) / 2, 0)`

## Native scoreBulk Flow

The native `scoreBulk` path in `MSBitToInt4ESNextOSQVectorsScorer` performs two sequential I/O + compute steps:

1. **Quantized scoring**: Read `L * 32` bytes (all 32 packed vectors) -> call `dotProductD1Q4Bulk` -> produces 32 raw quantized scores in a float array
2. **Correction application**: Read `16 * 32` bytes (all corrections) -> call `nativeApplyCorrectionsBulk` -> produces 32 final similarity scores

The corrections memory segment is accessed with offsets:
- `offset + j * 4`: lowerIntervals
- `offset + 4 * bulkSize + j * 4`: upperIntervals
- `offset + 8 * bulkSize + j * 4`: componentSums
- `offset + 12 * bulkSize + j * 4`: additionalCorrections

## Implementation Dispatch

`MemorySegmentESNextOSQVectorsScorer` selects the concrete scorer based on query/index bit widths:
- 4-bit query, 1-bit index -> `MSBitToInt4ESNextOSQVectorsScorer`
- 4-bit query, 2-bit index -> `MSDibitToInt4ESNextOSQVectorsScorer`
- 4-bit query, 4-bit index -> `MSInt4SymmetricESNextOSQVectorsScorer`
- 7-bit query, 7-bit index -> `MSD7Q7ESNextOSQVectorsScorer`

Within each scorer, the `scoreBulk` method tries (in order):
1. Native (via `NativeAccess` JNI/FFM) if available
2. Panama Vector API (256-bit or 128-bit) if fast integer vectors are supported
3. Falls back to the scalar base class `ESNextOSQVectorsScorer`

---

## Proposed Alternative: Vertical (Columnar) Vector Layout

### Motivation

The current horizontal layout processes one vector at a time, scoring it against all 4 query bit-planes. This means:
- The inner SIMD loop operates across a single vector's L bytes
- A horizontal reduce (`reduceLanes(ADD)`) is needed per vector to collapse SIMD lanes into a scalar result
- A scalar tail is needed per vector if L is not a multiple of the SIMD register width in bytes

With BULK_SIZE=32 matching the 256-bit SIMD register width (32 bytes), we can instead process all 32 vectors simultaneously by transposing the storage.

### Transposed Layout

Instead of storing each vector's bytes contiguously (row-major), store the same byte position across all 32 vectors together (column-major):

```
Current (horizontal):              Proposed (vertical/columnar):
v0[b0] v0[b1] ... v0[bL-1]        v0[b0] v1[b0] ... v31[b0]     = 32 bytes
v1[b0] v1[b1] ... v1[bL-1]        v0[b1] v1[b1] ... v31[b1]     = 32 bytes
...                                ...
v31[b0] v31[b1] ... v31[bL-1]     v0[bL-1] v1[bL-1] ... v31[bL-1] = 32 bytes
```

Total is still `32 * L` bytes, just rearranged. Corrections remain unchanged (already column-major).

### Transposed Scoring Kernel (D1Q4)

```
// 4 accumulators, each holding 32 partial scores (one per document)
acc[0..3] = zero (32 lanes each)

for each byte position j = 0..L-1:
    doc_bytes = SIMD_load(column[j])           // 32 doc bytes in one 256-bit load
    for each bit-plane p = 0..3:
        q_byte = broadcast(query_plane_p[j])   // 1 query byte -> 32 lanes
        acc[p] += byte_popcount(doc_bytes & q_byte)

// Final combine: lane-wise (vertical), no horizontal reduce
scores = acc[0]*1 + acc[1]*2 + acc[2]*4 + acc[3]*8   // 32 results in SIMD registers
```

### Performance Advantages

#### No horizontal reduce

Current: each of the 32 vectors needs `reduceLanes(ADD)` on 4 accumulators = **128 horizontal reduce operations**.
Transposed: the final combine is a lane-wise (vertical) multiply-add = **0 horizontal reduces**, just a few SIMD instructions producing all 32 results at once.

#### No scalar tail

Current: if L is not a multiple of the SIMD width in bytes, a scalar tail runs for each vector = **32 tail computations**, each with Long, Int, and Byte remainder loops.
Transposed: each iteration loads exactly 32 bytes (one column = BULK_SIZE), perfectly filling a 256-bit register. The loop runs exactly L times with no remainder = **0 tail computations**.

#### Summary

| | Current (horizontal) | Proposed (vertical) |
|---|---|---|
| Horizontal reduces | 128 (32 vectors x 4 planes) | 0 |
| Scalar tail loops | 32 (once per vector) | 0 |
| Final combine | 32 scalar `a + b*2 + c*4 + d*8` | 1 SIMD lane-wise op |

#### Accumulator overflow handling

Byte-lane popcount produces 0-8 per iteration. Accumulating in byte lanes overflows after 31 iterations (31 x 8 = 248 < 255). For dims=1024, L=128, so the accumulators must be periodically flushed (widened) to 16/32-bit. This is a vertical (lane-wise) add+zero operation, amortized across all 32 vectors simultaneously, and infrequent (~every 31 of L iterations).
