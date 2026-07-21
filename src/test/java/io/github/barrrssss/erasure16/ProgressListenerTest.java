package io.github.barrrssss.erasure16;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link ProgressListener} contract on the optimized decode path: progress
 * is monotonic, bounded by {@code [0, total]}, reports a constant positive {@code total},
 * and ends on an exact {@code completed == total} tick. Also checks that throwing from the
 * callback cancels the decode.
 */
class ProgressListenerTest {

    private static final long SEED = 0xBEEFL;

    private static final class Recorder implements ProgressListener {
        final List<long[]> calls = new ArrayList<>();

        @Override
        public void onProgress(long completedUnits, long totalUnits) {
            calls.add(new long[] {completedUnits, totalUnits});
        }
    }

    private static byte[][] encodedWithDataLoss(int k, int r, int shardLen, int dataDrops,
            boolean[] presentOut, Random rnd) {
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

        java.util.Arrays.fill(presentOut, true);
        for (int i = 0; i < dataDrops; i++) {
            shards[i] = null;         // drop data shards 0..dataDrops-1
            presentOut[i] = false;
        }
        return shards;
    }

    @Test
    void progressIsMonotonicBoundedAndEndsAtTotal() {
        Random rnd = new Random(SEED);
        int k = 300;
        int r = 90;
        int shardLen = 256; // enough words that the per-word phase emits several ticks
        boolean[] present = new boolean[k + r];
        byte[][] shards = encodedWithDataLoss(k, r, shardLen, r, present, rnd); // E = R

        Recorder rec = new Recorder();
        ReedSolomon16.create(k, r).reconstructData(shards, present, rec);

        assertFalse(rec.calls.isEmpty(), "listener must be called at least once");

        long total = rec.calls.get(0)[1];
        assertTrue(total > 0, "total must be positive");

        long prev = -1;
        for (long[] call : rec.calls) {
            long completed = call[0];
            long callTotal = call[1];
            assertEquals(total, callTotal, "total must be constant within a decode");
            assertTrue(completed >= 0 && completed <= total, "completed in [0, total]: " + completed);
            assertTrue(completed >= prev, "completed must be monotonic non-decreasing");
            prev = completed;
        }

        long[] last = rec.calls.get(rec.calls.size() - 1);
        assertEquals(total, last[0], "final tick must report completed == total");

        // Throttled: comfortably bounded above (design target ~200 + phase-boundary ticks).
        assertTrue(rec.calls.size() <= 512, "progress callbacks must be throttled, got " + rec.calls.size());
    }

    @Test
    void trivialCasesStillReportCompletion() {
        Random rnd = new Random(SEED + 1);
        int k = 6;
        int r = 4;
        int shardLen = 16;
        // Drop only parity: no data missing -> trivial recover-data path.
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
        boolean[] present = new boolean[k + r];
        java.util.Arrays.fill(present, true);
        present[k] = false;
        shards[k] = null;

        Recorder rec = new Recorder();
        ReedSolomon16.create(k, r).reconstructData(shards, present, rec);

        assertFalse(rec.calls.isEmpty(), "trivial path must still emit a completion tick");
        long[] last = rec.calls.get(rec.calls.size() - 1);
        assertEquals(last[1], last[0], "trivial completion reports completed == total");
    }

    @Test
    void throwingFromListenerCancelsDecode() {
        Random rnd = new Random(SEED + 2);
        int k = 400;
        int r = 200;
        int shardLen = 512;
        boolean[] present = new boolean[k + r];
        byte[][] shards = encodedWithDataLoss(k, r, shardLen, r, present, rnd);

        final class Cancel extends RuntimeException {}

        ProgressListener canceller = (completed, total) -> {
            throw new Cancel();
        };

        assertThrows(Cancel.class,
                () -> ReedSolomon16.create(k, r).reconstructData(shards, present, canceller),
                "throwing from onProgress must propagate out and abort the decode");
    }

    @Test
    void nullListenerBehavesLikeNoArgOverload() {
        Random rnd = new Random(SEED + 3);
        int k = 32;
        int r = 16;
        int shardLen = 64;
        boolean[] present = new boolean[k + r];
        byte[][] shards = encodedWithDataLoss(k, r, shardLen, r, present, rnd);

        // Passing a null listener must be accepted and equivalent to the two-arg form.
        byte[][] expected = new byte[k + r][];
        boolean[] present2 = new boolean[k + r];
        System.arraycopy(present, 0, present2, 0, present.length);
        for (int i = 0; i < shards.length; i++) {
            expected[i] = shards[i] == null ? null : shards[i].clone();
        }

        ReedSolomon16.create(k, r).reconstructData(shards, present, (ProgressListener) null);
        ReedSolomon16.create(k, r).reconstructData(expected, present2);

        for (int j = 0; j < k; j++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected[j], shards[j], "data shard " + j);
        }
        assertSame(Boolean.TRUE, present[0] ? Boolean.TRUE : Boolean.FALSE);
    }
}
