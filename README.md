# erasure16 (Java)

A general-purpose **systematic Reed–Solomon erasure code over GF(2¹⁶)** using a
**Cauchy** generator matrix. Given `K` equal-length data shards it produces `R`
parity shards such that the original `K` data shards can be reconstructed from
**any `K` of the `K + R` shards**.

This is the Java implementation of the cross-language `erasure16` contract. The
normative specification and the conformance vectors live in the
[`erasure16-spec`](https://github.com/barrrssss/erasure16-spec) repository, vendored
here as the git submodule [`external/erasure16-spec`](external/erasure16-spec). The
library is byte-exact with the reference .NET implementation: every committed
conformance vector is reproduced exactly (see `ConformanceTest`).

The library is deliberately **abstract** — shard byte arrays in, shard byte arrays
out. It performs no I/O, compression, encryption, framing, or container concerns, and
has **no runtime dependencies**.

## Field and format (summary)

- Field GF(2¹⁶), reduction polynomial `0x1100B` (x¹⁶ + x¹² + x³ + x + 1), generator
  α = 2, exp/log tables (full period 65535).
- Shards are read as 16-bit field elements in **big-endian**:
  `word[w] = (shard[2w] << 8) | shard[2w+1]`. Shard length must be **even and > 0**.
- Systematic Cauchy generator: identity rows for data; parity row `p` has
  `G[K+p][j] = inv(X_p XOR Y_j)` with `X_p = p`, `Y_j = R + j`.
- Constraints: `K >= 1`, `R >= 0`, `K + R <= 65536`, equal even shard lengths.

See [`external/erasure16-spec/SPEC.md`](external/erasure16-spec/SPEC.md) for the full
normative contract.

## Public API

```java
ReedSolomon16 rs = ReedSolomon16.create(dataShardCount, parityShardCount);
rs.encode(shards);                          // fills parity slots [K..K+R) in place
rs.reconstruct(shards, shardPresent);       // recovers all missing shards
rs.reconstructData(shards, shardPresent);   // recovers only missing data shards
boolean ok = rs.verify(shards);             // re-encode check

byte[][] shards = ReedSolomon16.split(data, dataShardCount, shardSize);
byte[] data = ReedSolomon16.join(dataShards, outputLength);
```

`Gf16` exposes the field primitives `mul`, `div`, `inv`, `exp`, `log`. Invalid
parameters, too-few-present shards, and bad shard lengths throw `Erasure16Exception`.

## Build and test

Requires JDK 11+ (built at Java language level 11). Uses the Gradle wrapper.

```sh
git submodule update --init   # pull the conformance vectors
./gradlew build               # compile + run all tests
./gradlew test                # FieldTest + ReedSolomonTest + ConformanceTest
```

`ConformanceTest` is the cross-language gate: it parses
`external/erasure16-spec/vectors/MANIFEST.json` and asserts byte-exact reproduction of
every field, encode, and reconstruct vector (and verifies the SHA-256 of each blob).
