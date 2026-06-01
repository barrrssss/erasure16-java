package io.github.barrrssss.erasure16;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and edge-case tests for {@link ReedSolomon16}: encode, knock out shards,
 * reconstruct, and compare against the original across many {@code (K, R, loss)}
 * combinations, plus structural-validation rejection tests.
 */
class ReedSolomonTest {

    private static final long SEED = 0x5DEECE66DL;

    /** Builds K random data shards of the given length and N total slots (parity zeroed). */
    private static byte[][] randomShards(int k, int r, int shardLen, Random rnd) {
        byte[][] shards = new byte[k + r][];
        for (int i = 0; i < k; i++) {
            byte[] s = new byte[shardLen];
            rnd.nextBytes(s);
            shards[i] = s;
        }
        for (int i = k; i < k + r; i++) {
            shards[i] = new byte[shardLen];
        }
        return shards;
    }

    private static byte[][] deepCopy(byte[][] src) {
        byte[][] out = new byte[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] == null ? null : src[i].clone();
        }
        return out;
    }

    /**
     * Core round trip: encode, drop the shards given by {@code dropIndices}, reconstruct,
     * and assert every shard equals the fully-encoded original.
     */
    private static void roundTrip(int k, int r, int shardLen, int[] dropIndices, Random rnd) {
        byte[][] original = randomShards(k, r, shardLen, rnd);
        ReedSolomon16 rs = ReedSolomon16.create(k, r);
        rs.encode(original);

        // A freshly encoded set must verify.
        assertTrue(rs.verify(original), "verify() must be true after encode for K=" + k + " R=" + r);

        // Work on a copy: clear the dropped shards and mark them absent.
        byte[][] shards = deepCopy(original);
        boolean[] present = new boolean[k + r];
        Arrays.fill(present, true);
        for (int idx : dropIndices) {
            shards[idx] = null;
            present[idx] = false;
        }

        rs.reconstruct(shards, present);

        // All shards present and byte-exact with the original encoding.
        for (int i = 0; i < k + r; i++) {
            assertTrue(present[i], "shard " + i + " must be present after reconstruct");
            assertArrayEquals(original[i], shards[i],
                    "shard " + i + " mismatch after reconstruct (K=" + k + " R=" + r
                            + " drop=" + Arrays.toString(dropIndices) + ")");
        }
        assertTrue(rs.verify(shards), "verify() must hold after reconstruct");
    }

    /**
     * Broad matrix: many (K, R) shapes, each with several loss patterns (data-only,
     * parity-only, and mixed), all reconstructing to the original.
     */
    @Test
    void roundTripMatrix() {
        Random rnd = new Random(SEED);
        // (K, R) shapes including edges: R=0, K=1, R>K, balanced, and larger K.
        int[][] shapes = {
            {1, 0}, {1, 1}, {1, 5},
            {2, 0}, {2, 3}, {3, 0}, {3, 2},
            {4, 2}, {5, 5}, {6, 1},
            {2, 6}, {3, 9}, // R > K
            {8, 4}, {10, 6},
            {32, 8}, {32, 32},
            {200, 20}, {200, 56},
        };

        for (int[] shape : shapes) {
            int k = shape[0];
            int r = shape[1];
            int shardLen = 2 + 2 * (rnd.nextInt(8)); // even, >= 2

            // Loss pattern 1: drop nothing (trivial, exercises verify + the all-present path).
            roundTrip(k, r, shardLen, new int[0], rnd);

            if (r == 0) {
                continue; // No recovery possible with zero parity beyond the trivial case.
            }

            // Build candidate loss patterns. We may drop at most R shards.
            // Pattern A: drop only data shards (up to min(R, K)).
            int dropData = Math.min(r, k);
            int[] dropOnlyData = firstN(0, dropData);
            roundTrip(k, r, shardLen, dropOnlyData, rnd);

            // Pattern B: drop only parity shards (up to R).
            int[] dropOnlyParity = firstN(k, r);
            roundTrip(k, r, shardLen, dropOnlyParity, rnd);

            // Pattern C: drop a mix of data and parity, exactly R shards total
            // (the maximum recoverable), chosen pseudo-randomly.
            int[] mixed = randomDrop(k, r, r, rnd);
            roundTrip(k, r, shardLen, mixed, rnd);

            // Pattern D: drop exactly R shards favouring data shards (force a matrix solve).
            int[] dataHeavy = dataHeavyDrop(k, r, rnd);
            roundTrip(k, r, shardLen, dataHeavy, rnd);
        }
    }

    /** Returns {start, start+1, ..., start+count-1}. */
    private static int[] firstN(int start, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = start + i;
        }
        return out;
    }

    /** Picks {@code dropCount} distinct shard indices in [0, K+R) at random. */
    private static int[] randomDrop(int k, int r, int dropCount, Random rnd) {
        int n = k + r;
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        // Fisher-Yates partial shuffle.
        for (int i = 0; i < dropCount; i++) {
            int j = i + rnd.nextInt(n - i);
            int tmp = all.get(i);
            all.set(i, all.get(j));
            all.set(j, tmp);
        }
        int[] out = new int[dropCount];
        for (int i = 0; i < dropCount; i++) {
            out[i] = all.get(i);
        }
        return out;
    }

    /**
     * Drops exactly R shards, preferring data shards first (then parity), which forces
     * the submatrix-inversion recovery path. We can drop at most R total because only
     * R shards are redundant.
     */
    private static int[] dataHeavyDrop(int k, int r, Random rnd) {
        int dropCount = r;
        int[] out = new int[dropCount];
        int idx = 0;
        // Drop data shards from the end of the data range first.
        int dataDrops = Math.min(dropCount, k);
        for (int i = 0; i < dataDrops; i++) {
            out[idx++] = k - 1 - i;
        }
        // Any remaining drops come from parity.
        int parityDrops = dropCount - dataDrops;
        for (int i = 0; i < parityDrops; i++) {
            out[idx++] = k + i;
        }
        return out;
    }

    /** reconstructData recovers only data shards, leaving missing parity untouched. */
    @Test
    void reconstructDataOnlyLeavesParityMissing() {
        Random rnd = new Random(SEED + 1);
        int k = 6;
        int r = 4;
        int shardLen = 16;
        byte[][] original = randomShards(k, r, shardLen, rnd);
        ReedSolomon16 rs = ReedSolomon16.create(k, r);
        rs.encode(original);

        byte[][] shards = deepCopy(original);
        boolean[] present = new boolean[k + r];
        Arrays.fill(present, true);
        // Drop data shard 0 and parity shard K+1.
        shards[0] = null;
        present[0] = false;
        shards[k + 1] = null;
        present[k + 1] = false;

        rs.reconstructData(shards, present);

        // Data shard recovered and present; the dropped parity shard stays absent.
        assertTrue(present[0], "data shard 0 must be recovered");
        assertArrayEquals(original[0], shards[0], "recovered data shard 0 must match");
        assertFalse(present[k + 1], "parity shard must remain missing after reconstructData");
    }

    /** All data present but some parity missing: reconstruct refills parity (trivial path). */
    @Test
    void reconstructRefillsMissingParity() {
        Random rnd = new Random(SEED + 2);
        int k = 4;
        int r = 3;
        int shardLen = 12;
        byte[][] original = randomShards(k, r, shardLen, rnd);
        ReedSolomon16 rs = ReedSolomon16.create(k, r);
        rs.encode(original);

        byte[][] shards = deepCopy(original);
        boolean[] present = new boolean[k + r];
        Arrays.fill(present, true);
        shards[k] = null; // drop one parity shard
        present[k] = false;

        rs.reconstruct(shards, present);
        assertArrayEquals(original[k], shards[k], "parity shard must be recomputed exactly");
        assertTrue(rs.verify(shards));
    }

    // ---- split / join ----

    @Test
    void splitJoinRoundTrip() {
        byte[] data = new byte[37];
        new Random(SEED + 3).nextBytes(data);
        byte[][] shards = ReedSolomon16.split(data, 4, 12); // capacity 48 >= 37
        assertEquals(4, shards.length);
        for (byte[] s : shards) {
            assertEquals(12, s.length, "each shard must be shardSize bytes");
        }
        byte[] joined = ReedSolomon16.join(shards, data.length);
        assertArrayEquals(data, joined, "join(split(data)) must round-trip");
    }

    @Test
    void splitRejectsOversizeData() {
        byte[] data = new byte[49];
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.split(data, 4, 12)); // 49 > 48
    }

    @Test
    void splitRejectsOddOrZeroShardSize() {
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.split(new byte[4], 2, 3));
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.split(new byte[4], 2, 0));
    }

    @Test
    void joinRejectsOversizeOutputLength() {
        byte[][] shards = {new byte[4], new byte[4]};
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.join(shards, 9)); // 9 > 8
    }

    // ---- parameter / structural validation ----

    @Test
    void createRejectsBadParameters() {
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.create(0, 1)); // K < 1
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.create(1, -1)); // R < 0
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.create(65536, 1)); // K+R > 65536
        assertThrows(Erasure16Exception.class, () -> ReedSolomon16.create(40000, 30000)); // K+R > 65536
        // Boundary: K+R == 65536 is allowed.
        ReedSolomon16.create(65535, 1);
        ReedSolomon16.create(65536, 0);
    }

    @Test
    void encodeRejectsOddLength() {
        ReedSolomon16 rs = ReedSolomon16.create(2, 1);
        byte[][] shards = {new byte[3], new byte[3], new byte[3]}; // odd length
        assertThrows(Erasure16Exception.class, () -> rs.encode(shards));
    }

    @Test
    void encodeRejectsZeroLength() {
        ReedSolomon16 rs = ReedSolomon16.create(2, 1);
        byte[][] shards = {new byte[0], new byte[0], new byte[0]};
        assertThrows(Erasure16Exception.class, () -> rs.encode(shards));
    }

    @Test
    void encodeRejectsUnequalLength() {
        ReedSolomon16 rs = ReedSolomon16.create(2, 1);
        byte[][] shards = {new byte[4], new byte[6], new byte[4]};
        assertThrows(Erasure16Exception.class, () -> rs.encode(shards));
    }

    @Test
    void encodeRejectsWrongShardCount() {
        ReedSolomon16 rs = ReedSolomon16.create(2, 1); // expects 3 shards
        byte[][] shards = {new byte[4], new byte[4]};
        assertThrows(Erasure16Exception.class, () -> rs.encode(shards));
    }

    @Test
    void reconstructRejectsTooFewPresent() {
        ReedSolomon16 rs = ReedSolomon16.create(3, 2); // need >= 3 present
        byte[][] shards = new byte[5][];
        boolean[] present = new boolean[5];
        // Only 2 present (< K = 3).
        shards[0] = new byte[8];
        shards[1] = new byte[8];
        present[0] = true;
        present[1] = true;
        assertThrows(Erasure16Exception.class, () -> rs.reconstruct(shards, present));
    }

    @Test
    void reconstructRejectsOddLengthPresent() {
        ReedSolomon16 rs = ReedSolomon16.create(2, 1);
        byte[][] shards = new byte[3][];
        boolean[] present = {true, true, true};
        shards[0] = new byte[3]; // odd
        shards[1] = new byte[3];
        shards[2] = new byte[3];
        assertThrows(Erasure16Exception.class, () -> rs.reconstruct(shards, present));
    }

    @Test
    void verifyDetectsCorruptedParity() {
        Random rnd = new Random(SEED + 4);
        ReedSolomon16 rs = ReedSolomon16.create(3, 2);
        byte[][] shards = randomShards(3, 2, 8, rnd);
        rs.encode(shards);
        assertTrue(rs.verify(shards));
        // Flip a byte in a parity shard.
        shards[3][0] ^= 0xFF;
        assertFalse(rs.verify(shards), "verify must detect corrupted parity");
    }
}
