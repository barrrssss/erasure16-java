# Security policy

## Supported versions

This project is pre-1.0 and maintained by one person. Security fixes are made on the
latest released version only; there are no maintained release branches.

## Reporting a vulnerability

Report privately through GitHub's **Report a vulnerability** button on this
repository's Security tab. That opens a private advisory visible only to the
maintainer. Please do not open a public issue for a suspected vulnerability.

Expect an acknowledgement within seven days. Because this is a single-maintainer
project, please allow a reasonable disclosure window rather than a fixed deadline; a
fix and a coordinated advisory will be published together.

## What is in scope

This project defines a systematic Cauchy Reed-Solomon erasure code over GF(2^16). Reports in scope include:

- input that causes unbounded memory or CPU use from parameters a caller may not
  control, for example `K` (data shards), `R` (parity shards), and shard length;
- input that causes an out-of-range access, a crash, or undefined behavior;
- a decode that reports success while returning shard bytes that are not the original
  ones;
- a divergence from the normative specification or the committed conformance vectors
  that a caller could rely on for recovery.

## What is out of scope

**This is an erasure code, not a cryptographic primitive.** It provides no
confidentiality, no integrity, and no authentication. It recovers data lost to
erasures at *known* positions; it does not detect or correct adversarially modified
shards, and a caller that treats a successful decode as proof of authenticity has
found a flaw in its own design rather than in this project.

Callers needing integrity or authenticity must apply their own MAC or signature over
the reconstructed data. Reports amounting to "corrupted input produced corrupted
output" are therefore not vulnerabilities here.
