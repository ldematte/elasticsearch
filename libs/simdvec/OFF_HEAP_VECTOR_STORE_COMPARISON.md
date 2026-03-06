# Off-Heap Vector Store: Paged vs Contiguous Allocation

## Context

During HNSW graph construction, vectors are stored off-heap in `MemorySegment`s
to enable native SIMD scoring. The original implementation allocated one
`MemorySegment` per vector. We explored two improved allocation strategies
to reduce overhead and improve locality.

Both approaches build on the same foundation: `OffHeapFloatVectorStore` /
`OffHeapByteVectorStore` in `libs/simdvec`, used by `ES93FlatFieldVectorsWriter`
during indexing. The scorer in `VectorScorerFactoryImpl` extracts the store
via reflection and wraps it in a `MemorySegmentAccessor` adapter.

## Option 1: Paged Allocation

**Branch:** `segments-adapter`

Vectors are packed into fixed-size pages of 64 vectors each. Each page is a
single `MemorySegment` allocated from a shared `Arena`. The last page may be
partially filled.

### How it works

- `addVector()` appends into the current page; allocates a new page when full.
- `getVectorSegment(i)` returns `pages.get(i / 64).asSlice(offset, vectorByteSize)`.
- The scorer uses the **sparse bulk scoring path**: for each `bulkScore` call,
  a temporary pointer array is allocated, filled with one address per ordinal
  (from `asSlice()`), and passed to the native `vec_sqrf32_bulk_sparse` function.
- `entireSegmentOrNull()` returns `null` (vectors span multiple pages).

### Memory profile (200k vectors, 960-dim float32)

- Useful data: 768 MB
- Overhead: ~245 KB (unused slots in last page)
- Peak memory: ~768 MB
- Per-call overhead: Arena allocation + pointer array + `asSlice()` per ordinal

### Trade-offs

- (+) Minimal memory overhead (~0.03%)
- (+) No data copying during indexing
- (-) Cannot use the fast contiguous bulk scoring path
- (-) Per-call pointer array allocation and `MemorySegment.asSlice()` objects

## Option 2: Contiguous Realloc

**Branch:** `segments-adapter-contiguous`

A single contiguous `MemorySegment` buffer that doubles in capacity when full.
Each buffer is allocated in its own `Arena`, so old buffers can be freed after
copying.

### How it works

- `addVector()` appends at `count * vectorByteSize`; if `count == capacity`,
  allocates a 2x buffer in a new Arena, copies, closes the old Arena, swaps.
- `getVectorSegment(i)` returns `buffer.asSlice(i * vectorByteSize, vectorByteSize)`.
- `getBufferSegment()` returns the raw contiguous buffer.
- The scorer uses the **fast contiguous bulk scoring path**:
  `entireSegmentOrNull()` returns the buffer, and the native function accesses
  vectors via `ordinal * vectorPitch` offsets. No pointer array needed.

### Why realloc is safe

Growth only happens inside `addVector()`, before the HNSW graph builder accesses
data for the newly added vector. The indexing path is single-threaded per field
writer instance (`DocumentsWriterPerThread`), so no concurrent reads can see
a partially-copied or freed buffer. No `MemorySegment` slices are cached across
`addValue()` calls.

### Memory profile (200k vectors, 960-dim float32)

- Useful data: 768 MB
- Final buffer: 1,007 MB (262,144 capacity × 3,840 bytes)
- Overhead at close: 239 MB (31% of final buffer, from unused capacity after last doubling)
- Peak memory during last resize: ~1.5 GB (old 503 MB + new 1,007 MB)
- 12 resize events, ~960 MB total bytes copied (amortized O(1) per vector)
- Copy cost: ~60-100 ms total at ~15 GB/s memcpy throughput (negligible vs indexing time)

### Trade-offs

- (+) Enables fast contiguous bulk scoring (no pointer array, no sparse indirection)
- (+) Better spatial locality (all vectors in one contiguous region)
- (-) ~31% memory overhead from unused capacity after last doubling
- (-) ~2x peak memory during the final resize event

## Benchmark Results

**Dataset:** GIST-1M, 200k docs, 960-dim float32, euclidean, HNSW(m=16, efC=200), BQ.
**Hardware:** Apple Silicon (M-series), single indexing thread.

| Approach               | Indexing time | vs original | Memory overhead |
|------------------------|-------------|-------------|-----------------|
| Per-vector (original)  | 162 s       | baseline    | ~0%             |
| Paged (64)             | 155.8 s     | -3.8%       | ~0%             |
| Contiguous realloc     | 146.3 s     | -9.7%       | ~31%            |

All three produce identical recall (0.36 @ numCandidates=100, 0.37 @ numCandidates=500).

### PAGE_SIZE sensitivity (paged approach only)

| PAGE_SIZE | Indexing time |
|-----------|-------------|
| 32        | 161.4 s     |
| 64        | 155.8 s     |
| 128       | 155.0 s     |

PAGE_SIZE=64 was chosen as the default: nearly identical to 128, and at 3072-dim
vectors (e.g. text-embedding-3-large) a page of 64 is ~768 KB, which stays
within the 1 MB per-core L2 cache of Graviton 2 and AMD EPYC.

## Open Questions

- How do these approaches compare in higher-level Elasticsearch benchmarks
  (Rally, multi-field, concurrent indexing)?
- Is the ~31% memory overhead of the contiguous approach acceptable for
  production workloads? Could a larger initial capacity or capped growth
  factor reduce waste?
- Should the choice be configurable, or should we pick one based on further
  benchmarking?
