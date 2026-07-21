# Decoder optimization: E×E submatrix solve + progress signals

Status: implemented in `erasure16-java` (branch `perf/submatrix-decode`). Output-identical
to the previous decoder; **no wire-format, spec, or conformance-vector change**. A parity
`erasure16-dotnet` port is a planned follow-up.

## Summary

The reconstruction path used to invert the full **K×K** generator submatrix whenever any
shard was missing — `O(K³)` time and `O(K²)` memory, regardless of how few shards were
actually lost. That is fine at the GF(256) scale it was inherited from (`K ≤ 255`), but at
GF(2¹⁶) `K` can reach the thousands, where the K×K inversion dominates time and its matrix
allocation is what makes a memory-constrained restorer (a phone) run out of heap.

This change replaces that with the standard erasure-decode optimization: **solve only the
`E×E` system for the `E` missing data shards** (`E` = number of erased data shards). The
recovered bytes are unchanged — the erasure solution is unique — so it is a pure
implementation swap.

Headline effects (measured, GF(2¹⁶), worst case `E = R = 15% of K`, symbol 1024 B):

- **Peak matrix memory: ~17× smaller** — e.g. 48 MB → 2.8 MB at K=2048, 768 MB → 44 MB at
  K=8192. This is the win that removes the phone out-of-memory wall.
- **Wall-clock time: ~1.4–2.1× faster at K=512–2048, and the speedup grows with K** (the
  eliminated K³ inversion grows faster than the residual work), while staying *feasible* at
  sizes where the old path is impractical (K=8192: ~36 s vs an extrapolated ~4–5 min).
- **Recovered output: byte-identical** to the legacy decoder and to the committed
  cross-language conformance vectors.

## Why the previous decoder was expensive

For a missing set, the old `recoverDataFullMatrix` selects any `K` present shards, builds
their `K×K` generator submatrix `M`, inverts it by Gauss–Jordan (`≈ 2K³` field multiplies,
`≈ 3·K²` ints held live), then applies `M⁻¹` to the missing rows. The inversion cost is
independent of how many shards were lost: losing 1 data shard out of 3000 costs the same
full `K×K` inversion as losing all of them.

## The optimization

Let the `K` data shards split into `K − E` **present** and `E` **missing**. Pick `E` present
parity shards as equations (there are always at least `E`: `presentCount ≥ K` and only
`K − E` data shards are present, so `≥ E` parity shards remain). Each parity value is the
generator-weighted sum over **all** `K` data shards:

```
parity_p = Σ_j  G[p][j] · data_j            (j over all K data columns)
```

Split the sum into the known (present) and unknown (missing) data and move the known part to
the right-hand side:

```
Σ_{j∈missing} G[p][j]·data_j  =  parity_p  −  Σ_{j∈present} G[p][j]·data_j   =:  rhs_p
```

Stacking the `E` chosen equations gives an `E×E` system `A · x = rhs`, where
`A[i][c] = G[p_i][missing_c]` and `x` is the `E` missing data words. `A` is a submatrix of the
Cauchy generator (distinct `X = {p_i}`, distinct `Y = {R + missing_c}`, `X ∩ Y = ∅`), hence a
Cauchy matrix and invertible. Solve `x = A⁻¹ · rhs` per word.

Implementation notes (`recoverDataSubmatrix`):

- `A` (E×E) is the system to invert; `B` (E×(K−E)) holds the known-data coefficients so the
  RHS is a matrix–vector product per word rather than repeated `inv()` calls.
- The per-word hot loops pre-gather the present-data words once (they feed every equation)
  and read flat locals, mirroring the legacy layout; the GF dot product uses four
  accumulators to avoid a single serial XOR-reduction chain.
- Cauchy coefficients are read directly via `CauchyMatrix.parityCoeff(p, j, r)`; the full
  generator is never materialized.

### Cost: before vs after

With `E` erased data shards out of `K`, symbol length `S` bytes (`words = S/2`):

| | Time (field muls) | Peak matrix memory |
| --- | --- | --- |
| Legacy `FULL_MATRIX` | `2·K³ + (S/2)·E·K` | `~3·K²` ints (`12·K²` bytes) |
| New `SUBMATRIX` | `2·E³ + (S/2)·E·K` | `~(2·E² + E·(K−E))` ints (`≈ 4·E·K` bytes) |

The two share the `(S/2)·E·K` term (the "touch the surviving data" syndrome/apply work,
irreducible for a dense code); the optimization removes the `K³` inversion, replacing it with
`E³`. They coincide exactly when `E = K` (all data lost — nothing to trim).

## Correctness

The recovered data is the unique solution of the erasure system, so any correct decoder
returns the same bytes. This is enforced three ways:

- **Conformance vectors** (`ConformanceTest`) — the new default `SUBMATRIX` path reproduces
  every committed `reconstruct` vector byte-for-byte against the .NET-generated blobs
  (including K=32/R=8).
- **Differential test** (`DecoderEquivalenceTest`) — across many `(K, R)` shapes and loss
  patterns (E=1, E≈R/2, E=R worst case, mixed data/parity, E=K, and K=1024), `SUBMATRIX`
  output equals both `FULL_MATRIX` and the original encoding, byte-for-byte.
- **Round-trip matrix** (`ReedSolomonTest`) — unchanged, now exercising the new default.

The legacy `FULL_MATRIX` decoder is retained (package-private, via the internal
`RecoveryAlgorithm` seam) purely as the differential-test oracle and benchmark baseline.

## Measured results

`DecoderBenchmark` (opt-in: `./gradlew test --tests '*DecoderBenchmark*' -Derasure16.bench=true`),
worst case `E = R = 15% of K`, symbol 1024 B, best-of-3 after warmup. Times are wall-clock on
the dev machine (JDK 25, amd64) and will vary by hardware; the **memory** columns are the
exact computed peak matrix footprint.

| K | R = E | full (ms) | sub (ms) | speedup | full mem | sub mem |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 512  | 77   | 159.6  | 112.0  | 1.4× | 3.0 MB   | 0.2 MB  |
| 1024 | 154  | 814.0  | 467.6  | 1.7× | 12.0 MB  | 0.7 MB  |
| 2048 | 308  | 4153.9 | 1941.1 | 2.1× | 48.0 MB  | 2.8 MB  |
| 4096 | 615  | —      | 8267.4 | —    | 192.0 MB | 11.1 MB |
| 8192 | 1229 | —      | 36083.8| —    | 768.0 MB | 44.2 MB |

(The K=4096/8192 legacy runs are omitted — the K×K inversion is minutes-long. Extrapolating
the `K³` growth, legacy at K=8192 is ~4–5 min vs the new path's ~36 s.)

### Honest reading of the numbers

- The **time** speedup is real but modest at these sizes (~2×), not the ~40× a naive
  field-multiply count suggests. The reason: the shared `(S/2)·E·K` syndrome term is
  **bandwidth-bound on the GF(2¹⁶) exp/log multiply tables** (two 128 KB tables), and both
  decoders pay it in full. The optimization removes the `K³` inversion *on top of* that shared
  floor, so the speedup is `1 + (inversion)/(shared floor)` — which **grows with K** (roughly
  ~2× at 2048, extrapolating to ~4× at 4096 and ~7× at 8192).
- Multi-accumulator unrolling of the reduction was tried and made no measurable difference,
  confirming the bottleneck is table-load traffic, not reduction latency.
- The **memory** reduction is the durable, size-independent structural win (`K²` → `E·K`):
  ~17× at 15% loss. This is what actually moves the restorable ceiling on a phone, because the
  old failure at large `K` was an out-of-memory crash, not merely a slow decode.

## Progress signals (`ProgressListener`)

Long reconstructs previously looked frozen to a UI. Reconstruction now accepts an optional
`ProgressListener` (public overloads of `reconstruct` / `reconstructData`; `null` = the
old no-op behavior with zero overhead):

```java
rs.reconstructData(shards, present, (done, total) -> updateBar(done, total));
```

Design:

- **Work-unit model.** `total` is an estimate of field-op cost summed across the three
  phases — build (`≈ E·K`), invert (`≈ 2·E³`), and per-word solve (`≈ words·E·K`) — so the
  reported fraction `done/total` is roughly proportional to real work regardless of which
  phase dominates (inversion-heavy at high loss / short symbols; per-word-heavy at long
  symbols). Units are abstract; treat only the ratio as meaningful.
- **Phase coverage.** The `E×E` inversion reports per-column via a `CauchyMatrix.invert`
  callback; the per-word loop reports as words complete. So both the (rare) inversion-bound
  and the (common) per-word-bound cases get smooth updates rather than one long silent gap.
- **Throttled + monotonic.** A small relay caps output at ~200 callbacks per decode, is
  monotonic non-decreasing, clamps to `[0, total]`, and guarantees a final exact
  `done == total` tick. Trivial cases (nothing missing / only parity missing) emit a single
  completion tick.
- **Cancellation.** Throwing an unchecked exception from `onProgress` propagates out and
  aborts the decode (discard the partially-written shard array). This gives a UI a cancel
  button at zero API cost.
- **Threading.** The callback runs inline on the decoding thread — post to the UI thread
  from inside it; do not do work there. Run the decode off the UI thread.

Verified by `ProgressListenerTest` (monotonicity, bounds, constant positive `total`, final
exact tick, trivial-case completion, throwing-cancels, and null-listener equivalence).

Limitation: because the per-word estimate assumes uniform cost, progress is approximately —
not exactly — linear in wall-clock time. That is the right trade for a progress bar.

## Public API changes (all additive, backward-compatible)

- `void reconstruct(shards, present, ProgressListener)` — new overload.
- `void reconstructData(shards, present, ProgressListener)` — new overload.
- `interface ProgressListener` — new public functional interface.
- Existing two-argument methods are unchanged and now use the `SUBMATRIX` path by default.

## Follow-ups

- **`erasure16-dotnet` port** — mirror `recoverDataSubmatrix`, `CauchyMatrix.ParityCoeff`,
  the `Invert` per-column callback, and a `ProgressListener`-equivalent, then re-run the
  shared conformance vectors. (Deferred pending explicit go-ahead; consumer submodule
  bumps follow that.)
- **Optional spec clarification** — `erasure16-spec` SPEC §6 describes the K×K reconstruct as
  the reference; a one-line note that the reconstruct algorithm is *output-normative* (any
  method reproducing the vectors conforms) would make the submatrix path unambiguously
  spec-blessed. Not required — the vectors already define conformance by output bytes.
- **Further speed** — only a structurally different GF multiply (e.g. a clmul/SIMD path, or
  batching to cut table traffic) would move the shared syndrome floor; the current change has
  already removed the `K³`/`K²` overhang.
