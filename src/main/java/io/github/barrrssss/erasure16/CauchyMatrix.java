package io.github.barrrssss.erasure16;

/**
 * Builds the systematic Cauchy generator matrix described in SPEC.md section 4 and
 * inverts arbitrary K x K submatrices of present rows by Gaussian elimination over
 * GF(2^16) (SPEC.md section 6).
 *
 * <p>The N x K generator matrix G (N = K + R) is:
 * <ul>
 *   <li>rows 0..K-1: the K x K identity (data shards pass through unchanged), and</li>
 *   <li>parity row K+p (p in 0..R-1):
 *       {@code G[K+p][j] = inv(X_p XOR Y_j)}, {@code X_p = p}, {@code Y_j = R + j},
 *       {@code j in 0..K-1}.</li>
 * </ul>
 * X = {0..R-1} and Y = {R..R+K-1} are disjoint, so {@code X_p XOR Y_j} is never 0.
 *
 * <p>Matrix entries are field elements carried as {@code int} in {@code 0..65535}.
 */
final class CauchyMatrix {

    private CauchyMatrix() {
        // Static utility class; not instantiable.
    }

    /**
     * Returns the generator row for shard index {@code row} in {@code 0..K+R-1}:
     * an identity row for a data shard, or a Cauchy row for a parity shard.
     * The returned array has length {@code k}.
     */
    static int[] generatorRow(int row, int k, int r) {
        int[] result = new int[k];

        if (row < k) {
            // Identity row for data shard `row`.
            result[row] = 1;
            return result;
        }

        // Parity row: p = row - k, in 0..R-1.
        int p = row - k;
        int xp = p; // X_p = p
        for (int j = 0; j < k; j++) {
            int yj = r + j; // Y_j = R + j
            result[j] = Gf16.inv(xp ^ yj);
        }

        return result;
    }

    /**
     * Builds the K x K matrix whose rows are the generator rows for the given
     * {@code rowIndices} (shard indices). {@code rowIndices} must have exactly K
     * entries. Returns a row-major K x K array.
     */
    static int[][] buildSquare(int[] rowIndices, int k, int r) {
        int[][] matrix = new int[k][];
        for (int i = 0; i < k; i++) {
            matrix[i] = generatorRow(rowIndices[i], k, r);
        }

        return matrix;
    }

    /**
     * Inverts a square K x K matrix over GF(2^16) by Gauss-Jordan elimination.
     * The input is not mutated. Throws {@link Erasure16Exception} if the matrix is
     * singular (which cannot happen for a Cauchy submatrix of distinct rows, but is
     * guarded defensively).
     */
    static int[][] invert(int[][] matrix, int k) {
        // Work on a copy of the input augmented with the identity on the right.
        int[][] a = new int[k][];
        int[][] inv = new int[k][];
        for (int i = 0; i < k; i++) {
            a[i] = matrix[i].clone();
            inv[i] = new int[k];
            inv[i][i] = 1;
        }

        for (int col = 0; col < k; col++) {
            // Find a pivot row at or below `col` with a non-zero entry in `col`.
            int pivot = -1;
            for (int row = col; row < k; row++) {
                if (a[row][col] != 0) {
                    pivot = row;
                    break;
                }
            }

            if (pivot < 0) {
                throw new Erasure16Exception(
                        "Reconstruction matrix is singular at column " + col
                                + "; the selected shard rows are not invertible.");
            }

            if (pivot != col) {
                int[] tmp = a[pivot];
                a[pivot] = a[col];
                a[col] = tmp;
                int[] tmpInv = inv[pivot];
                inv[pivot] = inv[col];
                inv[col] = tmpInv;
            }

            // Normalize the pivot row so that a[col][col] == 1.
            int pivVal = a[col][col];
            if (pivVal != 1) {
                int pivInv = Gf16.inv(pivVal);
                int[] aRow = a[col];
                int[] iRow = inv[col];
                for (int j = 0; j < k; j++) {
                    aRow[j] = Gf16.mul(aRow[j], pivInv);
                    iRow[j] = Gf16.mul(iRow[j], pivInv);
                }
            }

            // Eliminate `col` from every other row.
            for (int row = 0; row < k; row++) {
                if (row == col) {
                    continue;
                }

                int factor = a[row][col];
                if (factor == 0) {
                    continue;
                }

                int[] aRow = a[row];
                int[] iRow = inv[row];
                int[] aPiv = a[col];
                int[] iPiv = inv[col];
                for (int j = 0; j < k; j++) {
                    // row[j] ^= factor * pivot[j]  (subtraction == XOR in GF(2^m))
                    aRow[j] ^= Gf16.mul(factor, aPiv[j]);
                    iRow[j] ^= Gf16.mul(factor, iPiv[j]);
                }
            }
        }

        return inv;
    }
}
