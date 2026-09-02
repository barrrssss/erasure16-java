# Changelog

## 0.1.1 — 2026-09-02

- Codec behavior and public API are unchanged from 0.1.0. This release adopts a
  larger conformance corpus and keeps the Java and .NET package versions aligned;
  no library code changed.
- Adopted specification **0.1.1**. It adds a transport-scale encode and reconstruct case
  at `K = 6410`, `R = 1744`, `shardLen = 8`. The shared vectors topped out at `K = 32`
  before, so this implementation had never been checked against the .NET one at a scale
  where the Cauchy construction is actually stressed. It passes unchanged: no library
  change was needed.
- `ConformanceTest` reads the compact case layout of `SPEC.md` section 8.1 —
  `dataBlob` / `parityBlob` for shards and a `missing` index list in place of a full
  `present` mask — and expects `manifestVersion` 2. A case must carry exactly one of each
  pair; a reader that silently skipped the layout it did not understand would skip the
  only case above `K = 32` and still report a pass.

## 0.1.0 — 2026-08-23

- Initial Maven Central-ready release metadata.
- Systematic Cauchy Reed–Solomon implementation conforming to specification
  version 0.1.0.
- Produces Java 11-compatible classes with no runtime dependencies.
