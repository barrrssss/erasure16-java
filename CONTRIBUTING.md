# Contributing

Thanks for your interest. This is a small, deliberately narrow library, so a little
context will save you time.

## The specification is normative, not this code

Codec behavior is defined by [`SPEC.md`](https://github.com/barrrssss/erasure16-spec/blob/main/SPEC.md) in the
`erasure16-spec` repository, and the conformance vectors committed there are the cross-runtime
oracle. This repository is one of two implementations that must agree with it byte for
byte.

That means:

- **A change to codec behavior starts in the specification repository**, not here. A
  pull request that changes encoded bytes, matrix or graph construction, or decode
  semantics without a corresponding specification change cannot be merged, however
  reasonable the change is on its own.
- **The conformance vectors are not editable from here.** They are produced by a single
  authorized generator; a hand-edited vector silently destroys the property that makes
  the two runtimes comparable.
- The Java and .NET implementations move together. A behavior change lands in
  both, or in neither.

Bug fixes, performance work, documentation, and test coverage that leave the encoded
bytes identical are welcome here directly, and are the easiest kind of contribution to
accept.

## Before opening a pull request

- Run the full test suite, including the conformance tests, which read the
  specification submodule. Clone with `--recurse-submodules`, or run
  `git submodule update --init` first.
- Keep the public API additive. This library is pre-1.0, but consumers pin it.
- Do not add runtime dependencies. Both implementations are deliberately
  dependency-free, and a dependency in one runtime that the other cannot match breaks
  the cross-runtime contract.
- Explain the *why* in the commit message. What the diff does is visible; what it is
  for usually is not.

## Reporting a problem

Use the issue templates. For a suspected security issue, follow
[`SECURITY.md`](SECURITY.md) instead of opening a public issue.

## License

Contributions are accepted under the [Apache License 2.0](LICENSE), the license this
project is released under.
