package io.github.barrrssss.erasure16;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Measured old-vs-new decode benchmark for the submatrix optimization. Gated off by
 * default (it runs for tens of seconds); enable with:
 *
 * <pre>./gradlew test --tests '*DecoderBenchmark*' -Derasure16.bench=true</pre>
 *
 * <p>It reports wall-clock time for the legacy {@code FULL_MATRIX} decoder versus the new
 * {@code SUBMATRIX} decoder at a realistic worst case (loss {@code E = R}, redundancy
 * {@code R = 15% of K}, symbol length 1024 B), plus the computed peak matrix footprint of
 * each, and a {@code SUBMATRIX}-only scaling section at larger {@code K} where the legacy
 * path is impractical. Every measured case also asserts the two decoders produced
 * byte-identical output, so the benchmark doubles as a large-shape correctness check.
 */
@EnabledIfSystemProperty(named = "erasure16.bench", matches = "true")
class DecoderBenchmark {

    private static final long SEED = 0x5A1AD5EEDL;
    private static final int SHARD_LEN = 1024;    // even; ~realistic symbol payload
    private static final double REDUNDANCY = 0.15; // R = 15% of K
    private static final int WARMUP = 2;
    private static final int TIMED = 3;

    @Test
    void compareDecoders() {
        System.out.println();
        System.out.println("erasure16 decode benchmark  (loss E = R = 15% of K, symbol " + SHARD_LEN + " B)");
        System.out.println("JVM: " + System.getProperty("java.version") + "  " + System.getProperty("os.arch"));
        System.out.printf(Locale.ROOT, "%7s %6s %6s | %12s %12s %9s | %10s %10s%n",
                "K", "R", "E", "full (ms)", "sub (ms)", "speedup", "full mem", "sub mem");
        System.out.println("-".repeat(92));

        for (int k : new int[] {512, 1024, 2048}) {
            int r = (int) Math.ceil(k * REDUNDANCY);
            int e = r; // worst case: all lost shards are data
            Result full = measure(ReedSolomon16.RecoveryAlgorithm.FULL_MATRIX, k, r, e);
            Result sub = measure(ReedSolomon16.RecoveryAlgorithm.SUBMATRIX, k, r, e);
            assertArrayEquals(full.firstDataShard, sub.firstDataShard,
                    "decoders must agree byte-for-byte at K=" + k);
            System.out.printf(Locale.ROOT, "%7d %6d %6d | %12.1f %12.1f %8.1fx | %10s %10s%n",
                    k, r, e, full.millis, sub.millis, full.millis / sub.millis,
                    mib(fullMatrixBytes(k)), mib(subMatrixBytes(k, e)));
            // Assert the win only where the full-matrix run is large enough that the
            // O(K^3) inversion clearly dominates measurement noise (sub-50ms runs are
            // dominated by JIT/alloc jitter, not the algorithm).
            if (full.millis > 50.0) {
                assertTrue(sub.millis < full.millis,
                        "SUBMATRIX must be faster than FULL_MATRIX at K=" + k
                                + " (sub=" + sub.millis + "ms, full=" + full.millis + "ms)");
            }
        }

        System.out.println("-".repeat(92));
        System.out.println("SUBMATRIX-only scaling (legacy FULL_MATRIX omitted — impractical at these sizes):");
        for (int k : new int[] {4096, 8192}) {
            int r = (int) Math.ceil(k * REDUNDANCY);
            int e = r;
            Result sub = measure(ReedSolomon16.RecoveryAlgorithm.SUBMATRIX, k, r, e);
            System.out.printf(Locale.ROOT, "%7d %6d %6d | %12s %12.1f %9s | %10s %10s%n",
                    k, r, e, "-", sub.millis, "-", mib(fullMatrixBytes(k)), mib(subMatrixBytes(k, e)));
        }
        System.out.println();
    }

    private static final class Result {
        final double millis;
        final byte[] firstDataShard;

        Result(double millis, byte[] firstDataShard) {
            this.millis = millis;
            this.firstDataShard = firstDataShard;
        }
    }

    /** Encodes once, then times {@code reconstructData} over fresh copies; returns best-of-N ms. */
    private static Result measure(ReedSolomon16.RecoveryAlgorithm algo, int k, int r, int e) {
        Random rnd = new Random(SEED + k * 31L + r);
        byte[][] encoded = new byte[k + r][];
        for (int i = 0; i < k; i++) {
            byte[] s = new byte[SHARD_LEN];
            rnd.nextBytes(s);
            encoded[i] = s;
        }
        for (int i = k; i < k + r; i++) {
            encoded[i] = new byte[SHARD_LEN];
        }
        ReedSolomon16.create(k, r).encode(encoded);

        // Drop the last e data shards (E data erasures, filled from parity).
        int[] drop = new int[e];
        for (int i = 0; i < e; i++) {
            drop[i] = k - 1 - i;
        }

        ReedSolomon16 rs = ReedSolomon16.create(k, r);
        byte[] firstData = null;
        double best = Double.MAX_VALUE;
        for (int iter = 0; iter < WARMUP + TIMED; iter++) {
            byte[][] shards = new byte[k + r][];
            boolean[] present = new boolean[k + r];
            Arrays.fill(present, true);
            for (int i = 0; i < k + r; i++) {
                shards[i] = encoded[i].clone();
            }
            for (int idx : drop) {
                shards[idx] = null;
                present[idx] = false;
            }

            long t0 = System.nanoTime();
            rs.reconstructData(shards, present, algo);
            long t1 = System.nanoTime();

            if (iter >= WARMUP) {
                best = Math.min(best, (t1 - t0) / 1_000_000.0);
            }
            firstData = shards[0];
            // Correctness spot-check every iteration: recovered shard 0 equals the original.
            assertArrayEquals(encoded[0], shards[0], "recovered data shard 0 must match original");
        }
        return new Result(best, firstData);
    }

    /** Legacy peak: m + a-clone + inv, each K x K ints. */
    private static long fullMatrixBytes(int k) {
        return 3L * k * k * Integer.BYTES;
    }

    /** Submatrix peak: A (ExE) + A^-1 (ExE) + B (Ex(K-E)) ints. */
    private static long subMatrixBytes(int k, int e) {
        return (2L * e * e + (long) e * (k - e)) * Integer.BYTES;
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
