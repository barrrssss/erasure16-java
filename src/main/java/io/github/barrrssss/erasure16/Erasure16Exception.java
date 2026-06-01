package io.github.barrrssss.erasure16;

/**
 * Thrown for invalid parameters or malformed input to the {@link ReedSolomon16}
 * erasure coder: bad {@code K}/{@code R} values, mismatched or odd shard lengths,
 * or fewer than {@code K} present shards during reconstruction.
 *
 * <p>This mirrors {@code Erasure16Exception} in the reference .NET implementation
 * and SPEC.md (constraints in sections 3, 6, 7).
 */
public final class Erasure16Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Erasure16Exception(String message) {
        super(message);
    }

    public Erasure16Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
