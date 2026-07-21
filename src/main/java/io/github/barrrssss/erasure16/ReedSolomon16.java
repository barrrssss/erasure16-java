package io.github.barrrssss.erasure16;

/**
 * Systematic Reed-Solomon erasure coder over GF(2^16) with a Cauchy generator
 * matrix (SPEC.md). Given {@code K} equal-length data shards it produces {@code R}
 * parity shards such that the original data can be reconstructed from any {@code K}
 * of the {@code K + R} shards.
 *
 * <p>The coder is deliberately abstract: shard byte arrays in, shard byte arrays out.
 * It performs no I/O, compression, or encryption. Shards are read as 16-bit field
 * elements in big-endian: {@code word[w] = (shard[2w] << 8) | shard[2w+1]}.
 */
public final class ReedSolomon16 {

    /** The largest legal value of {@code K + R} (the field has 65536 elements). */
    public static final int MAX_TOTAL_SHARDS = 65536;

    private final int dataShardCount;
    private final int parityShardCount;
    private final int totalShardCount;

    private ReedSolomon16(int dataShardCount, int parityShardCount) {
        this.dataShardCount = dataShardCount;
        this.parityShardCount = parityShardCount;
        this.totalShardCount = dataShardCount + parityShardCount;
    }

    /** Number of data shards, {@code K}. */
    public int dataShardCount() {
        return dataShardCount;
    }

    /** Number of parity shards, {@code R}. */
    public int parityShardCount() {
        return parityShardCount;
    }

    /** Total number of shards, {@code N = K + R}. */
    public int totalShardCount() {
        return totalShardCount;
    }

    /**
     * Creates a coder for {@code dataShardCount} ({@code K}) data shards and
     * {@code parityShardCount} ({@code R}) parity shards. Requires {@code K >= 1},
     * {@code R >= 0}, and {@code K + R <= 65536}.
     *
     * @throws Erasure16Exception if the parameters are out of range.
     */
    public static ReedSolomon16 create(int dataShardCount, int parityShardCount) {
        if (dataShardCount < 1) {
            throw new Erasure16Exception(
                    "dataShardCount (K) must be >= 1; got " + dataShardCount + ".");
        }

        if (parityShardCount < 0) {
            throw new Erasure16Exception(
                    "parityShardCount (R) must be >= 0; got " + parityShardCount + ".");
        }

        // Compute K + R in a way that cannot overflow for any int inputs.
        long total = (long) dataShardCount + parityShardCount;
        if (total > MAX_TOTAL_SHARDS) {
            throw new Erasure16Exception(
                    "dataShardCount + parityShardCount (K + R) must be <= " + MAX_TOTAL_SHARDS
                            + "; got " + total + ".");
        }

        return new ReedSolomon16(dataShardCount, parityShardCount);
    }

    /**
     * Encodes in place. {@code shards} must have {@code K + R} entries of equal, even,
     * positive length; slots {@code [0..K)} hold the caller-supplied data and slots
     * {@code [K..K+R)} are overwritten with the computed parity. With {@code R == 0}
     * this only validates the data shards.
     *
     * @throws Erasure16Exception if the shard count, lengths, or parities are invalid.
     */
    public void encode(byte[][] shards) {
        int shardLength = validateShardArray(shards);
        int words = shardLength / 2;

        for (int p = 0; p < parityShardCount; p++) {
            int[] row = CauchyMatrix.generatorRow(dataShardCount + p, dataShardCount, parityShardCount);
            byte[] parity = shards[dataShardCount + p];

            for (int w = 0; w < words; w++) {
                int acc = 0;
                int bi = w * 2;
                for (int j = 0; j < dataShardCount; j++) {
                    byte[] data = shards[j];
                    int value = ((data[bi] & 0xFF) << 8) | (data[bi + 1] & 0xFF);
                    acc ^= Gf16.mul(row[j], value);
                }

                parity[bi] = (byte) (acc >>> 8);
                parity[bi + 1] = (byte) (acc & 0xFF);
            }
        }
    }

    /**
     * Reconstructs every missing shard in place. {@code shardPresent} marks which of
     * the {@code K + R} shards currently hold valid bytes; at least {@code K} must be
     * present. Present shards are read for their length; missing shard slots are
     * (re)allocated and filled. After this returns, all {@code K + R} shards are
     * present.
     *
     * @throws Erasure16Exception if fewer than {@code K} shards are present, or
     *     lengths are invalid.
     */
    public void reconstruct(byte[][] shards, boolean[] shardPresent) {
        reconstructInternal(shards, shardPresent, false, RecoveryAlgorithm.SUBMATRIX, null);
    }

    /**
     * Reconstructs every missing shard in place (see
     * {@link #reconstruct(byte[][], boolean[])}), reporting incremental progress to
     * {@code listener} while the erasure math runs so a caller can drive a real progress
     * bar. {@code listener} may be {@code null} (equivalent to the two-argument form).
     *
     * @throws Erasure16Exception if fewer than {@code K} shards are present, or lengths
     *     are invalid.
     */
    public void reconstruct(byte[][] shards, boolean[] shardPresent, ProgressListener listener) {
        reconstructInternal(shards, shardPresent, false, RecoveryAlgorithm.SUBMATRIX, listener);
    }

    /**
     * Reconstructs only the missing data shards (slots {@code [0..K)}) in place,
     * leaving any missing parity shards untouched. At least {@code K} shards must be
     * present.
     *
     * @throws Erasure16Exception if fewer than {@code K} shards are present, or
     *     lengths are invalid.
     */
    public void reconstructData(byte[][] shards, boolean[] shardPresent) {
        reconstructInternal(shards, shardPresent, true, RecoveryAlgorithm.SUBMATRIX, null);
    }

    /**
     * Reconstructs only the missing data shards in place (see
     * {@link #reconstructData(byte[][], boolean[])}), reporting incremental progress to
     * {@code listener} while the erasure math runs. {@code listener} may be {@code null}
     * (equivalent to the two-argument form).
     *
     * @throws Erasure16Exception if fewer than {@code K} shards are present, or lengths
     *     are invalid.
     */
    public void reconstructData(byte[][] shards, boolean[] shardPresent, ProgressListener listener) {
        reconstructInternal(shards, shardPresent, true, RecoveryAlgorithm.SUBMATRIX, listener);
    }

    // Package-private seam: force a specific recovery algorithm. Production always uses
    // SUBMATRIX (the two- and three-argument public methods); the differential tests and
    // the benchmark use this to drive the legacy FULL_MATRIX path for comparison.
    void reconstruct(byte[][] shards, boolean[] shardPresent, RecoveryAlgorithm algorithm) {
        reconstructInternal(shards, shardPresent, false, algorithm, null);
    }

    void reconstructData(byte[][] shards, boolean[] shardPresent, RecoveryAlgorithm algorithm) {
        reconstructInternal(shards, shardPresent, true, algorithm, null);
    }

    /**
     * Selects how {@link #reconstructData}/{@link #reconstruct} recover missing <em>data</em>
     * shards. Both produce byte-identical output (the recovered data is the unique solution
     * of the erasure system); they differ only in cost.
     */
    enum RecoveryAlgorithm {
        /**
         * Legacy path: invert the full {@code K x K} reconstruction matrix
         * ({@code O(K^3)} time, {@code O(K^2)} memory), independent of how many shards
         * were lost. Retained as the differential-test oracle and benchmark baseline.
         */
        FULL_MATRIX,

        /**
         * Default path: solve only the {@code E x E} system for the {@code E} missing data
         * shards ({@code O(E^3 + E*K*words)} time, {@code O(E*K)} memory). Equal to
         * {@code FULL_MATRIX} only in the degenerate case {@code E == K} (all data lost).
         */
        SUBMATRIX
    }

    /**
     * Returns {@code true} if all {@code K + R} shards are mutually consistent — i.e.
     * re-encoding the data shards reproduces the existing parity shards exactly.
     * Expects a fully populated shard array of equal, even, positive lengths.
     *
     * @throws Erasure16Exception if the shard array is structurally invalid.
     */
    public boolean verify(byte[][] shards) {
        int shardLength = validateShardArray(shards);
        int words = shardLength / 2;

        for (int p = 0; p < parityShardCount; p++) {
            int[] row = CauchyMatrix.generatorRow(dataShardCount + p, dataShardCount, parityShardCount);
            byte[] parity = shards[dataShardCount + p];

            for (int w = 0; w < words; w++) {
                int acc = 0;
                int bi = w * 2;
                for (int j = 0; j < dataShardCount; j++) {
                    byte[] data = shards[j];
                    int value = ((data[bi] & 0xFF) << 8) | (data[bi + 1] & 0xFF);
                    acc ^= Gf16.mul(row[j], value);
                }

                byte hi = (byte) (acc >>> 8);
                byte lo = (byte) (acc & 0xFF);
                if (parity[bi] != hi || parity[bi + 1] != lo) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Splits {@code data} into {@code dataShardCount} zero-padded shards of
     * {@code shardSize} bytes each (SPEC.md section 7). {@code shardSize} must be even
     * and positive, and {@code data.length <= dataShardCount * shardSize}.
     *
     * @throws Erasure16Exception if the arguments are out of range.
     */
    public static byte[][] split(byte[] data, int dataShardCount, int shardSize) {
        if (data == null) {
            throw new Erasure16Exception("split: data must not be null.");
        }

        if (dataShardCount < 1) {
            throw new Erasure16Exception("split: dataShardCount (K) must be >= 1; got " + dataShardCount + ".");
        }

        if (shardSize <= 0 || (shardSize & 1) != 0) {
            throw new Erasure16Exception("split: shardSize must be even and > 0; got " + shardSize + ".");
        }

        long capacity = (long) dataShardCount * shardSize;
        if (data.length > capacity) {
            throw new Erasure16Exception(
                    "split: data length " + data.length + " exceeds capacity " + capacity
                            + " (dataShardCount * shardSize).");
        }

        byte[][] shards = new byte[dataShardCount][];
        for (int i = 0; i < dataShardCount; i++) {
            byte[] shard = new byte[shardSize];
            int offset = i * shardSize;
            int remaining = data.length - offset;
            if (remaining > 0) {
                int copy = Math.min(remaining, shardSize);
                System.arraycopy(data, offset, shard, 0, copy);
            }

            shards[i] = shard;
        }

        return shards;
    }

    /**
     * Concatenates {@code dataShards} in order and returns the first
     * {@code outputLength} bytes (SPEC.md section 7).
     *
     * @throws Erasure16Exception if {@code outputLength} is negative or exceeds the
     *     concatenated length.
     */
    public static byte[] join(byte[][] dataShards, int outputLength) {
        if (dataShards == null) {
            throw new Erasure16Exception("join: dataShards must not be null.");
        }

        if (outputLength < 0) {
            throw new Erasure16Exception("join: outputLength must be >= 0; got " + outputLength + ".");
        }

        long total = 0;
        for (int i = 0; i < dataShards.length; i++) {
            if (dataShards[i] == null) {
                throw new Erasure16Exception("join: dataShards[" + i + "] must not be null.");
            }

            total += dataShards[i].length;
        }

        if (outputLength > total) {
            throw new Erasure16Exception(
                    "join: outputLength " + outputLength + " exceeds concatenated length " + total + ".");
        }

        byte[] output = new byte[outputLength];
        int written = 0;
        for (int i = 0; i < dataShards.length && written < outputLength; i++) {
            byte[] shard = dataShards[i];
            int copy = shard.length;
            if (written + copy > outputLength) {
                copy = outputLength - written;
            }

            System.arraycopy(shard, 0, output, written, copy);
            written += copy;
        }

        return output;
    }

    private void reconstructInternal(byte[][] shards, boolean[] shardPresent, boolean dataOnly,
            RecoveryAlgorithm algorithm, ProgressListener listener) {
        if (shards == null) {
            throw new Erasure16Exception("reconstruct: shards must not be null.");
        }

        if (shardPresent == null) {
            throw new Erasure16Exception("reconstruct: shardPresent must not be null.");
        }

        if (shards.length != totalShardCount) {
            throw new Erasure16Exception(
                    "reconstruct: expected " + totalShardCount + " shards (K + R); got " + shards.length + ".");
        }

        if (shardPresent.length != totalShardCount) {
            throw new Erasure16Exception(
                    "reconstruct: shardPresent must have " + totalShardCount + " entries; got "
                            + shardPresent.length + ".");
        }

        // Determine the shard length from the present shards and validate them.
        int shardLength = -1;
        int presentCount = 0;
        for (int i = 0; i < totalShardCount; i++) {
            if (!shardPresent[i]) {
                continue;
            }

            byte[] shard = shards[i];
            if (shard == null) {
                throw new Erasure16Exception(
                        "reconstruct: shard " + i + " is marked present but is null.");
            }

            if (shardLength < 0) {
                shardLength = shard.length;
                if (shardLength <= 0 || (shardLength & 1) != 0) {
                    throw new Erasure16Exception(
                            "reconstruct: shard length must be even and > 0; got " + shardLength + ".");
                }
            } else if (shard.length != shardLength) {
                throw new Erasure16Exception(
                        "reconstruct: present shards have differing lengths (" + shardLength + " vs "
                                + shard.length + ").");
            }

            presentCount++;
        }

        if (presentCount < dataShardCount) {
            throw new Erasure16Exception(
                    "reconstruct: need at least " + dataShardCount + " present shards (K); got "
                            + presentCount + ".");
        }

        int words = shardLength / 2;

        // Are all K data shards present? If so reconstruction of data is trivial
        // (systematic code); only missing parity needs recomputing.
        boolean allDataPresent = true;
        for (int j = 0; j < dataShardCount; j++) {
            if (!shardPresent[j]) {
                allDataPresent = false;
                break;
            }
        }

        if (!allDataPresent) {
            if (algorithm == RecoveryAlgorithm.FULL_MATRIX) {
                recoverDataFullMatrix(shards, shardPresent, words, shardLength);
            } else {
                recoverDataSubmatrix(shards, shardPresent, words, shardLength, listener);
            }
        } else if (listener != null) {
            // No data missing: nothing to solve. Report a single completed tick.
            listener.onProgress(1, 1);
        }

        // At this point all K data shards are present (either originally or just
        // recovered). If requested, also fill any missing parity shards by re-encoding.
        if (!dataOnly) {
            for (int p = 0; p < parityShardCount; p++) {
                int idx = dataShardCount + p;
                if (shardPresent[idx]) {
                    continue;
                }

                int[] row = CauchyMatrix.generatorRow(idx, dataShardCount, parityShardCount);
                byte[] parity = ensureShard(shards, idx, shardLength);

                for (int w = 0; w < words; w++) {
                    int acc = 0;
                    int bi = w * 2;
                    for (int j = 0; j < dataShardCount; j++) {
                        byte[] data = shards[j];
                        int value = ((data[bi] & 0xFF) << 8) | (data[bi + 1] & 0xFF);
                        acc ^= Gf16.mul(row[j], value);
                    }

                    parity[bi] = (byte) (acc >>> 8);
                    parity[bi + 1] = (byte) (acc & 0xFF);
                }

                shardPresent[idx] = true;
            }
        }
    }

    /**
     * Legacy recovery: select K present shards, invert their full {@code K x K} generator
     * submatrix, and apply it to the selected shard words. Cost is {@code O(K^3)} time and
     * {@code O(K^2)} memory regardless of how few shards were lost. Retained as the
     * differential-test oracle and benchmark baseline for {@link #recoverDataSubmatrix};
     * see {@link RecoveryAlgorithm}. Assumes validation has already run and that at least
     * one data shard is missing.
     */
    private void recoverDataFullMatrix(byte[][] shards, boolean[] shardPresent, int words, int shardLength) {
        int k = dataShardCount;

        // Select the first K present shards (any K distinct rows are invertible).
        int[] selected = new int[k];
        int count = 0;
        for (int i = 0; i < totalShardCount && count < k; i++) {
            if (shardPresent[i]) {
                selected[count++] = i;
            }
        }

        // M is the K x K matrix of the selected shards' generator rows; data = M^-1 * selected.
        int[][] m = CauchyMatrix.buildSquare(selected, k, parityShardCount);
        int[][] mInv = CauchyMatrix.invert(m, k);

        // Snapshot the selected shards' bytes (they are all present and validated).
        byte[][] selectedShards = new byte[k][];
        for (int i = 0; i < k; i++) {
            selectedShards[i] = shards[selected[i]];
        }

        // Which data shards are missing and therefore need writing?
        boolean[] missingData = new boolean[k];
        for (int j = 0; j < k; j++) {
            if (!shardPresent[j]) {
                missingData[j] = true;
                ensureShard(shards, j, shardLength);
            }
        }

        for (int w = 0; w < words; w++) {
            int bi = w * 2;

            // Gather the selected shards' word values once per word index.
            int[] sel = new int[k];
            for (int i = 0; i < k; i++) {
                byte[] s = selectedShards[i];
                sel[i] = ((s[bi] & 0xFF) << 8) | (s[bi + 1] & 0xFF);
            }

            for (int row = 0; row < k; row++) {
                if (!missingData[row]) {
                    continue; // Present data shards are already correct (systematic).
                }

                int[] invRow = mInv[row];
                int acc = 0;
                for (int i = 0; i < k; i++) {
                    acc ^= Gf16.mul(invRow[i], sel[i]);
                }

                byte[] dataShard = shards[row];
                dataShard[bi] = (byte) (acc >>> 8);
                dataShard[bi + 1] = (byte) (acc & 0xFF);
            }
        }

        // Mark the recovered data shards present.
        for (int j = 0; j < k; j++) {
            if (missingData[j]) {
                shardPresent[j] = true;
            }
        }
    }

    /**
     * Optimized recovery: solve only the {@code E x E} system for the {@code E} missing
     * data shards, instead of inverting the full {@code K x K} reconstruction matrix.
     *
     * <p>Choose {@code E} present parity shards as equations. Each parity value is the
     * generator-weighted sum over all {@code K} data shards; subtracting the (known)
     * contribution of the {@code K - E} present data shards leaves an {@code E x E} Cauchy
     * system in the {@code E} unknown (missing) data words. Cost is {@code O(E^3)} to
     * invert plus {@code O(E*K*words)} to apply, and {@code O(E*K)} memory — versus the
     * legacy {@code O(K^3)} time and {@code O(K^2)} memory of
     * {@link #recoverDataFullMatrix}. The recovered bytes are identical (the erasure
     * solution is unique); this only avoids work proportional to the shards that were
     * <em>not</em> lost. It reduces to the legacy cost exactly when {@code E == K} (all
     * data lost). Assumes validation has run and at least one data shard is missing.
     */
    private void recoverDataSubmatrix(byte[][] shards, boolean[] shardPresent, int words, int shardLength,
            ProgressListener listener) {
        final int k = dataShardCount;
        final int r = parityShardCount;

        // Partition data columns into present / missing.
        int[] missing = new int[k];
        int[] presentData = new int[k];
        int e = 0;
        int presentDataCount = 0;
        for (int j = 0; j < k; j++) {
            if (shardPresent[j]) {
                presentData[presentDataCount++] = j;
            } else {
                missing[e++] = j;
            }
        }
        // e >= 1: the caller only invokes this when at least one data shard is missing.

        // Choose E present parity shards to supply E independent equations. There are
        // always at least E of them (presentCount >= K and only K - E data shards are present).
        int[] parityP = new int[e];
        int eq = 0;
        for (int p = 0; p < r && eq < e; p++) {
            if (shardPresent[k + p]) {
                parityP[eq++] = p;
            }
        }
        if (eq < e) {
            throw new Erasure16Exception(
                    "reconstruct: only " + eq + " present parity shards to recover " + e
                            + " missing data shards.");
        }

        // A[i][c] = coeff(parity p_i, missing data col c): the E x E system to invert.
        // B[i][c] = coeff(parity p_i, present data col c): folds the known data out of the RHS.
        int[][] a = new int[e][e];
        int[][] b = new int[e][presentDataCount];
        for (int i = 0; i < e; i++) {
            int p = parityP[i];
            int[] arow = a[i];
            for (int c = 0; c < e; c++) {
                arow[c] = CauchyMatrix.parityCoeff(p, missing[c], r);
            }
            int[] brow = b[i];
            for (int c = 0; c < presentDataCount; c++) {
                brow[c] = CauchyMatrix.parityCoeff(p, presentData[c], r);
            }
        }

        // Progress model (tracked only when a listener is attached): estimated field-op
        // cost across the build, invert, and per-word phases, so the reported fraction is
        // roughly proportional to real work no matter which phase dominates.
        final long costBuild = (long) e * k;              // ~E*K coefficient lookups (A + B)
        final long costInvertPerCol = 2L * e * e;         // ~2E^2 per Gauss-Jordan column
        final long costInvert = costInvertPerCol * e;     // ~2E^3 total
        final long costPerWord = (long) e * k;            // knownSum E*(K-E) + solve E^2 = E*K
        final Progress progress = (listener == null) ? null
                : new Progress(listener, costBuild + costInvert + (long) words * costPerWord);
        if (progress != null) {
            progress.set(costBuild);
        }

        int[][] aInv = CauchyMatrix.invert(a, e, progress == null ? null
                : col -> progress.set(costBuild + (col + 1L) * costInvertPerCol));

        // Ensure the missing data shards have buffers to receive the solution.
        for (int c = 0; c < e; c++) {
            ensureShard(shards, missing[c], shardLength);
        }

        // Resolve the shard byte arrays once (outside the per-word loop) so the hot loop
        // indexes flat locals rather than double-dereferencing through index arrays.
        byte[][] presentDataShards = new byte[presentDataCount][];
        for (int c = 0; c < presentDataCount; c++) {
            presentDataShards[c] = shards[presentData[c]];
        }
        byte[][] parityShards = new byte[e][];
        byte[][] missingShards = new byte[e][];
        for (int i = 0; i < e; i++) {
            parityShards[i] = shards[k + parityP[i]];
            missingShards[i] = shards[missing[i]];
        }

        final long afterInvert = costBuild + costInvert;
        int[] pd = new int[presentDataCount]; // present-data words, gathered once per word
        int[] rhs = new int[e];
        for (int w = 0; w < words; w++) {
            int bi = w * 2;

            // Gather each present-data word once (it feeds every equation).
            for (int c = 0; c < presentDataCount; c++) {
                byte[] d = presentDataShards[c];
                pd[c] = ((d[bi] & 0xFF) << 8) | (d[bi + 1] & 0xFF);
            }

            // rhs_i = parity_{p_i}[w] XOR (sum over present data of B[i][c] * pd[c]).
            for (int i = 0; i < e; i++) {
                int[] brow = b[i];
                int pv = readWord(parityShards[i], bi);
                rhs[i] = pv ^ dot(brow, pd, presentDataCount);
            }

            // missing_c[w] = sum over i of aInv[c][i] * rhs_i.
            for (int c = 0; c < e; c++) {
                int acc = dot(aInv[c], rhs, e);
                byte[] out = missingShards[c];
                out[bi] = (byte) (acc >>> 8);
                out[bi + 1] = (byte) (acc & 0xFF);
            }

            if (progress != null) {
                progress.set(afterInvert + (w + 1L) * costPerWord);
            }
        }

        // Mark the recovered data shards present.
        for (int c = 0; c < e; c++) {
            shardPresent[missing[c]] = true;
        }

        if (progress != null) {
            progress.done();
        }
    }

    /**
     * Throttled progress relay: forwards at most ~200 {@link ProgressListener#onProgress}
     * calls over one decode, clamps to {@code [0, total]}, and guarantees one final
     * exact-total tick via {@link #done()}.
     */
    private static final class Progress {
        private final ProgressListener listener;
        private final long total;
        private final long minStep;
        private long lastEmitted = -1;

        Progress(ProgressListener listener, long total) {
            this.listener = listener;
            this.total = Math.max(1, total);
            this.minStep = Math.max(1, this.total / 200);
        }

        void set(long completed) {
            long clamped = Math.min(Math.max(completed, 0), total);
            if (clamped < total && clamped - lastEmitted >= minStep) {
                lastEmitted = clamped;
                listener.onProgress(clamped, total);
            }
        }

        void done() {
            lastEmitted = total;
            listener.onProgress(total, total);
        }
    }

    /**
     * GF(2^16) dot product: XOR over {@code i in [0, len)} of {@code mul(x[i], y[i])}.
     * Four independent accumulators keep the XOR-reduction from being a single serial
     * dependency chain; XOR is associative and commutative, so splitting and recombining is
     * bit-identical to a straight left fold. (Measured effect is small — GF(2^16) decode is
     * dominated by the multiply's exp/log table-load traffic rather than the reduction
     * latency — but the shape is clean and does no harm.)
     */
    private static int dot(int[] x, int[] y, int len) {
        int a0 = 0;
        int a1 = 0;
        int a2 = 0;
        int a3 = 0;
        int i = 0;
        int limit = len - 3;
        for (; i < limit; i += 4) {
            a0 ^= Gf16.mul(x[i], y[i]);
            a1 ^= Gf16.mul(x[i + 1], y[i + 1]);
            a2 ^= Gf16.mul(x[i + 2], y[i + 2]);
            a3 ^= Gf16.mul(x[i + 3], y[i + 3]);
        }
        int acc = a0 ^ a1 ^ a2 ^ a3;
        for (; i < len; i++) {
            acc ^= Gf16.mul(x[i], y[i]);
        }
        return acc;
    }

    /** Reads a big-endian 16-bit word from {@code shard} at byte offset {@code bi}. */
    private static int readWord(byte[] shard, int bi) {
        return ((shard[bi] & 0xFF) << 8) | (shard[bi + 1] & 0xFF);
    }

    private static byte[] ensureShard(byte[][] shards, int index, int shardLength) {
        byte[] shard = shards[index];
        if (shard == null || shard.length != shardLength) {
            shard = new byte[shardLength];
            shards[index] = shard;
        }

        return shard;
    }

    /**
     * Validates a fully populated shard array (used by encode/verify): exactly
     * {@code K + R} non-null shards of equal, even, positive length. Returns the common
     * shard length.
     */
    private int validateShardArray(byte[][] shards) {
        if (shards == null) {
            throw new Erasure16Exception("shards must not be null.");
        }

        if (shards.length != totalShardCount) {
            throw new Erasure16Exception(
                    "expected " + totalShardCount + " shards (K + R); got " + shards.length + ".");
        }

        int shardLength = -1;
        for (int i = 0; i < totalShardCount; i++) {
            byte[] shard = shards[i];
            if (shard == null) {
                throw new Erasure16Exception("shard " + i + " must not be null.");
            }

            if (shardLength < 0) {
                shardLength = shard.length;
                if (shardLength <= 0 || (shardLength & 1) != 0) {
                    throw new Erasure16Exception(
                            "shard length must be even and > 0; got " + shardLength + ".");
                }
            } else if (shard.length != shardLength) {
                throw new Erasure16Exception(
                        "all shards must have equal length; shard " + i + " has length " + shard.length
                                + " but expected " + shardLength + ".");
            }
        }

        return shardLength;
    }
}
