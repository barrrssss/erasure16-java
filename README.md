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

Current package version: **0.1.1**. It implements specification version **0.1.1**.
See [`CHANGELOG.md`](CHANGELOG.md) for release history.

After the first Maven Central release, depend on it with:

```kotlin
implementation("io.github.barrrssss:erasure16:0.1.1")
```

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

// Optional progress for long decodes (drive a UI progress bar; throw to cancel):
rs.reconstructData(shards, shardPresent, (done, total) -> updateBar(done, total));

byte[][] shards = ReedSolomon16.split(data, dataShardCount, shardSize);
byte[] data = ReedSolomon16.join(dataShards, outputLength);
```

`Gf16` exposes the field primitives `mul`, `div`, `inv`, `exp`, `log`. Invalid
parameters, too-few-present shards, and bad shard lengths throw `Erasure16Exception`.

Reconstruction solves only the `E×E` system for the `E` missing data shards rather than
inverting the full `K×K` matrix — byte-identical output, far less memory (`~E·K` vs `K²`),
and time that scales with the loss rather than the whole encoded set. See
[`docs/decoder-optimization.md`](docs/decoder-optimization.md) for the math, correctness
argument, measured results, and the `ProgressListener` design.

## Build and test

The build requires **JDK 17 or newer** because Gradle 9 runs on Java 17+. Published
classes remain **Java 11 compatible**: every Java compile task uses `--release 11`,
which checks both class-file level and Java 11 API availability. Uses the Gradle wrapper.

```sh
git submodule update --init   # pull the conformance vectors
./gradlew build               # compile + run all tests
./gradlew test                # FieldTest + ReedSolomonTest + ConformanceTest
```

`ConformanceTest` is the cross-language gate: it parses
`external/erasure16-spec/vectors/MANIFEST.json` and asserts byte-exact reproduction of
every field, encode, and reconstruct vector (and verifies the SHA-256 of each blob).

## Maven Central publication

The Kotlin DSL build configures the Central Portal target, GPG signing, sources and
Javadoc JARs, and the required POM metadata. Before the first release, register the
`io.github.barrrssss` namespace in Central Portal and configure a Portal user token
plus a published GPG key outside the repository, following the
[publishing plugin guide](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

Stage a signed deployment manually with:

```sh
./gradlew clean build publishToMavenCentral
```

Then inspect and publish the deployment in Central Portal. Credentials and private
keys must stay in the user Gradle configuration or environment; this repository does
not define CI.

## Status

Pre-1.0 release candidate. The library is intentionally general-purpose and defines
only shard-level coding behavior.

## License

[Apache License 2.0](LICENSE), matching the specification and .NET implementation.
