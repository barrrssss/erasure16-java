package io.github.barrrssss.erasure16;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Differential correctness gate for the optimized {@code SUBMATRIX} decoder: across a
 * broad matrix of {@code (K, R)} shapes and loss patterns, it must produce
 * <em>byte-identical</em> output to both the legacy {@code FULL_MATRIX} decoder and the
 * original encoded shards. This is what backs "the optimization cannot change recovered
 * bytes" — the recovered data is the unique solution of the erasure system, so any two
 * correct decoders agree exactly.
 *
 * <p>Emphasis on the loss shapes that actually exercise the data-recovery solve: the
 * {@code E = R} worst case (all redundancy spent on lost data), partial data loss
 * ({@code E < R}), the {@code E = K} degenerate case (all data lost, where the two
 * algorithms provably coincide), and mixed data/parity loss.
 */
class DecoderEquivalenceTest {

    private static final long SEED = 0xC0FFEEL;

    private static byte[][] randomEncoded(int k, int r, int shardLen, Random rnd) {
        byte[][] shards = new byte[k + r][];
        for (int i = 0; i < k; i++) {
            byte[] s = new byte[shardLen];
            rnd.nextBytes(s);
            shards[i] = s;
        }
        for (int i = k; i < k + r; i++) {
            shards[i] = new byte[shardLen];
        }
        ReedSolomon16.create(k, r).encode(shards);
        return shards;
    }

    private static byte[][] copyDroppingIndices(byte[][] original, int[] drop, boolean[] presentOut) {
        int n = original.length;
        byte[][] shards = new byte[n][];
        Arrays.fill(presentOut, true);
        for (int i = 0; i < n; i++) {
            shards[i] = original[i].clone();
        }
        for (int idx : drop) {
            shards[idx] = null;
            presentOut[idx] = false;
        }
        return shards;
    }

    /**
     * Runs both decoders on the same loss and asserts both equal the original encoding
     * (and therefore each other), byte-for-byte, for both {@code reconstructData} and the
     * full {@code reconstruct}.
     */
    private static void assertEquivalent(int k, int r, int shardLen, int[] drop, Random rnd) {
        byte[][] original = randomEncoded(k, r, shardLen, rnd);
        String tag = "K=" + k + " R=" + r + " len=" + shardLen + " drop=" + Arrays.toString(drop);

        // reconstructData: SUBMATRIX (public default) vs FULL_MATRIX (legacy oracle).
        boolean[] presentSub = new boolean[k + r];
        byte[][] sub = copyDroppingIndices(original, drop, presentSub);
        ReedSolomon16.create(k, r).reconstructData(sub, presentSub); // default = SUBMATRIX

        boolean[] presentFull = new boolean[k + r];
        byte[][] full = copyDroppingIndices(original, drop, presentFull);
        ReedSolomon16.create(k, r).reconstructData(full, presentFull, ReedSolomon16.RecoveryAlgorithm.FULL_MATRIX);

        for (int j = 0; j < k; j++) {
            assertArrayEquals(original[j], sub[j], "SUBMATRIX data shard " + j + " (" + tag + ")");
            assertArrayEquals(original[j], full[j], "FULL_MATRIX data shard " + j + " (" + tag + ")");
            assertArrayEquals(full[j], sub[j], "SUBMATRIX vs FULL_MATRIX data shard " + j + " (" + tag + ")");
        }

        // Full reconstruct (also refills parity): SUBMATRIX default must reproduce all N.
        boolean[] presentAll = new boolean[k + r];
        byte[][] all = copyDroppingIndices(original, drop, presentAll);
        ReedSolomon16 rs = ReedSolomon16.create(k, r);
        rs.reconstruct(all, presentAll);
        for (int i = 0; i < k + r; i++) {
            assertTrue(presentAll[i], "shard " + i + " present after reconstruct (" + tag + ")");
            assertArrayEquals(original[i], all[i], "reconstruct shard " + i + " (" + tag + ")");
        }
        assertTrue(rs.verify(all), "verify after reconstruct (" + tag + ")");
    }

    @Test
    void submatrixMatchesFullMatrixAcrossShapesAndLosses() {
        Random rnd = new Random(SEED);
        // Shapes span small edges through K in the hundreds (kept modest so the legacy
        // O(K^3) oracle stays fast); includes R > K and balanced R = K.
        int[][] shapes = {
            {1, 1}, {2, 3}, {3, 2}, {4, 2}, {5, 5}, {6, 1},
            {2, 6}, {3, 9}, // R > K
            {8, 4}, {16, 8}, {32, 32}, {64, 16}, {128, 32}, {256, 64}, {300, 90},
        };

        for (int[] shape : shapes) {
            int k = shape[0];
            int r = shape[1];
            int shardLen = 2 + 2 * (rnd.nextInt(20)); // even, 2..40

            // E = 1 (minimum solve), if any data loss is possible.
            if (r >= 1 && k >= 1) {
                assertEquivalent(k, r, shardLen, new int[] {0}, rnd);
            }

            int maxDataLoss = Math.min(r, k);
            if (maxDataLoss >= 1) {
                // E = R worst case: drop the last min(R,K) data shards.
                assertEquivalent(k, r, shardLen, lastDataIndices(k, maxDataLoss), rnd);
                // E ~ R/2 partial data loss.
                int half = Math.max(1, maxDataLoss / 2);
                assertEquivalent(k, r, shardLen, lastDataIndices(k, half), rnd);
            }

            if (r >= 1) {
                // Mixed data+parity loss totalling exactly R (maximum recoverable).
                assertEquivalent(k, r, shardLen, mixedDrop(k, r, rnd), rnd);
                // Parity-only loss (no data missing -> trivial path in both).
                assertEquivalent(k, r, shardLen, firstParityIndices(k, r), rnd);
            }
        }
    }

    /** The E == K corner: every data shard lost, rebuilt entirely from parity. */
    @Test
    void submatrixEqualsFullMatrixWhenAllDataLost() {
        Random rnd = new Random(SEED + 1);
        int[][] shapes = {{1, 1}, {4, 4}, {32, 32}, {128, 128}, {256, 256}};
        for (int[] shape : shapes) {
            int k = shape[0];
            int r = shape[1];
            assertTrue(r >= k, "need R >= K to lose all data");
            assertEquivalent(k, r, 16, firstDataIndices(k), rnd);
        }
    }

    /** A larger K to exercise the real recovery path once (small shardLen keeps it quick). */
    @Test
    void submatrixMatchesAtLargerK() {
        Random rnd = new Random(SEED + 2);
        int k = 1024;
        int r = 160;
        // Drop 160 data shards (E = R) with a short symbol so the legacy K^3 inversion
        // (done once by the oracle) stays within a second or two.
        assertEquivalent(k, r, 8, lastDataIndices(k, r), rnd);
    }

    // ---- drop-index helpers ----

    private static int[] firstDataIndices(int k) {
        int[] out = new int[k];
        for (int i = 0; i < k; i++) {
            out[i] = i;
        }
        return out;
    }

    private static int[] lastDataIndices(int k, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = k - 1 - i;
        }
        return out;
    }

    private static int[] firstParityIndices(int k, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = k + i;
        }
        return out;
    }

    /** Exactly R distinct indices, favouring data shards first, then parity. */
    private static int[] mixedDrop(int k, int r, Random rnd) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < k + r; i++) {
            pool.add(i);
        }
        // Partial Fisher-Yates to pick R distinct indices, but bias the first pick toward
        // a data shard so a solve is actually forced when k >= 1.
        int firstDataPick = rnd.nextInt(k);
        pool.remove(Integer.valueOf(firstDataPick));
        int[] out = new int[r];
        out[0] = firstDataPick;
        for (int i = 1; i < r; i++) {
            int j = rnd.nextInt(pool.size());
            out[i] = pool.remove(j);
        }
        return out;
    }
}
