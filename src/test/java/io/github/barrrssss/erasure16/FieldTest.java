package io.github.barrrssss.erasure16;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Self-contained correctness checks for GF(2^16) arithmetic (SPEC.md section 1).
 * These do not consult the conformance vectors; {@link ConformanceTest} cross-checks
 * specific sampled values against the .NET-generated manifest.
 */
class FieldTest {

    /**
     * The reduction polynomial 0x1100B is primitive, so the exp table built from
     * alpha = 2 must have full period 65535: every value in 1..65535 appears exactly
     * once and the field closes back on 1 (i.e. exp(0) == 1, exp(65535) == 1).
     */
    @Test
    void fullPeriodSelfCheck() {
        boolean[] seen = new boolean[Gf16.FIELD_SIZE];
        int prev = 1; // alpha^0
        for (int i = 0; i < Gf16.ORDER; i++) {
            int v = Gf16.exp(i);
            assertTrue(v >= 1 && v <= 65535, "exp(" + i + ") = " + v + " out of range 1..65535");
            assertTrue(!seen[v], "value " + v + " repeats in exp table at exponent " + i);
            seen[v] = true;

            // log is the inverse of exp on the multiplicative group.
            assertEquals(i, Gf16.log(v), "log(exp(" + i + ")) must equal " + i);

            if (i > 0) {
                // Consecutive exp values differ by a factor of alpha = 2.
                assertEquals(v, Gf16.mul(prev, 2), "exp(" + i + ") must be exp(" + (i - 1) + ") * 2");
            }
            prev = v;
        }

        for (int v = 1; v < Gf16.FIELD_SIZE; v++) {
            assertTrue(seen[v], "value " + v + " missing from exp table");
        }

        assertEquals(1, Gf16.exp(0), "exp(0) must be 1");
        // The group wraps: alpha^65535 == alpha^0 == 1.
        assertEquals(1, Gf16.exp(Gf16.ORDER), "exp(65535) must wrap to 1");
    }

    /** a * inv(a) == 1 for every non-zero element. */
    @Test
    void inverseLaw() {
        for (int a = 1; a < Gf16.FIELD_SIZE; a++) {
            int inv = Gf16.inv(a);
            assertEquals(1, Gf16.mul(a, inv), "a * inv(a) must be 1 for a = " + a);
        }
    }

    /** div(a, b) == mul(a, inv(b)); and (a*b)/b == a. */
    @Test
    void divisionLaw() {
        int[] samples = {1, 2, 3, 255, 256, 4660, 22136, 32768, 43981, 65534, 65535};
        for (int a : samples) {
            for (int b : samples) {
                assertEquals(Gf16.mul(a, Gf16.inv(b)), Gf16.div(a, b),
                        "div(" + a + "," + b + ") must equal mul(a, inv(b))");
                // Round trip: (a*b)/b == a.
                int prod = Gf16.mul(a, b);
                assertEquals(a, Gf16.div(prod, b), "(a*b)/b must equal a for a=" + a + ", b=" + b);
            }
        }
    }

    /** Multiplication is commutative and absorbs zero; 1 is the identity. */
    @Test
    void multiplicationBasics() {
        int[] samples = {0, 1, 2, 3, 255, 256, 4660, 22136, 32768, 43981, 65534, 65535};
        for (int a : samples) {
            assertEquals(0, Gf16.mul(a, 0), "a * 0 must be 0");
            assertEquals(0, Gf16.mul(0, a), "0 * a must be 0");
            assertEquals(a, Gf16.mul(a, 1), "a * 1 must be a");
            assertEquals(a, Gf16.mul(1, a), "1 * a must be a");
            for (int b : samples) {
                assertEquals(Gf16.mul(a, b), Gf16.mul(b, a), "mul must be commutative");
            }
        }
    }

    /** Multiplication is associative across a sample of elements. */
    @Test
    void multiplicationAssociative() {
        int[] samples = {1, 2, 3, 257, 4660, 32768, 43981, 65535};
        for (int a : samples) {
            for (int b : samples) {
                for (int c : samples) {
                    assertEquals(Gf16.mul(Gf16.mul(a, b), c), Gf16.mul(a, Gf16.mul(b, c)),
                            "mul must be associative for " + a + "," + b + "," + c);
                }
            }
        }
    }

    /** Multiplication distributes over addition (XOR). */
    @Test
    void distributiveLaw() {
        int[] samples = {1, 2, 3, 257, 4660, 32768, 43981, 65535};
        for (int a : samples) {
            for (int b : samples) {
                for (int c : samples) {
                    int left = Gf16.mul(a, b ^ c);
                    int right = Gf16.mul(a, b) ^ Gf16.mul(a, c);
                    assertEquals(left, right, "mul must distribute over XOR for " + a + "," + b + "," + c);
                }
            }
        }
    }

    /** inv, div, and log of zero are undefined and must throw. */
    @Test
    void zeroErrors() {
        assertThrows(Erasure16Exception.class, () -> Gf16.inv(0));
        assertThrows(Erasure16Exception.class, () -> Gf16.div(1, 0));
        assertThrows(Erasure16Exception.class, () -> Gf16.log(0));
        // div(0, b) is well-defined and zero.
        assertEquals(0, Gf16.div(0, 5));
    }
}
