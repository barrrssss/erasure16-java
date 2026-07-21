package io.github.barrrssss.erasure16;

/**
 * Optional callback invoked during a long {@link ReedSolomon16#reconstruct} /
 * {@link ReedSolomon16#reconstructData} so a caller (for example a UI) can render a
 * real progress bar instead of an indeterminate spinner.
 *
 * <p>{@code completedUnits} rises monotonically from {@code 0} toward {@code totalUnits}
 * over one reconstruct call. Both are abstract "work units" — an internal estimate of
 * field-multiply cost summed across the decode's phases (building the system, inverting
 * it, and the per-word solve) — so treat the ratio {@code completedUnits / totalUnits}
 * as the fraction complete and do not ascribe an absolute unit to the numbers.
 * {@code totalUnits} is constant within a single call and always {@code > 0} when the
 * callback fires; the final invocation reports {@code completedUnits == totalUnits}.
 *
 * <p>Reconstruction only reports progress when it actually runs the erasure math (at
 * least one <em>data</em> shard was missing). The trivial cases — nothing missing, or
 * only parity missing — complete without meaningful work and simply report a single
 * {@code onProgress(total, total)}.
 *
 * <p>The callback runs inline on the calling thread, so keep it cheap (post to a UI
 * thread rather than doing work here). To <b>cancel</b> a long decode, throw an
 * unchecked exception from {@link #onProgress}: it propagates out of the reconstruct
 * call. The shard array may be left partially reconstructed in that case and should be
 * discarded.
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * Reports incremental decode progress.
     *
     * @param completedUnits work completed so far, in {@code [0, totalUnits]}, monotonic
     *     within one reconstruct call
     * @param totalUnits total work for this reconstruct call; constant per call, {@code > 0}
     */
    void onProgress(long completedUnits, long totalUnits);
}
