package io.github.barrrssss.erasure16;

/**
 * Arithmetic over the Galois field GF(2^16).
 *
 * <p>Elements are integers in {@code 0..65535}; bit {@code i} is the coefficient of
 * {@code x^i} (so element {@code 2} == {@code x}). Addition and subtraction are bitwise
 * XOR. Multiplication is polynomial multiplication modulo the primitive reduction
 * polynomial {@code 0x1100B} (x^16 + x^12 + x^3 + x + 1), implemented with exp/log
 * tables built from the generator alpha = 2. See SPEC.md section 1.
 *
 * <p>Field values are carried as {@code int} restricted to the range {@code 0..65535};
 * Java has no unsigned 16-bit type, but the arithmetic is byte-identical to the
 * reference {@code ushort} implementation. All public methods are static.
 */
public final class Gf16 {

    /** Number of field elements: 2^16. */
    public static final int FIELD_SIZE = 65536;

    /** Multiplicative group order: 2^16 - 1. The exp table has this period. */
    public static final int ORDER = 65535;

    /** Primitive reduction polynomial x^16 + x^12 + x^3 + x + 1, as a 17-bit value. */
    public static final int REDUCTION_POLYNOMIAL = 0x1100B;

    // expTable[i] = alpha^i for i in 0..65534 (alpha = 2). Length 65535.
    private static final char[] EXP_TABLE;

    // logTable[expTable[i]] = i. logTable[0] is an undefined sentinel (0) that is
    // never read. Length 65536.
    private static final char[] LOG_TABLE;

    static {
        EXP_TABLE = new char[ORDER];
        LOG_TABLE = new char[FIELD_SIZE];

        // Build tables exactly per SPEC.md section 1:
        //   x = 1
        //   for i in 0..65534: exp[i] = x; log[x] = i; x <<= 1; if (x & 0x10000) x ^= 0x1100B
        int x = 1;
        for (int i = 0; i < ORDER; i++) {
            EXP_TABLE[i] = (char) x;
            LOG_TABLE[x] = (char) i;

            x <<= 1;
            if ((x & 0x10000) != 0) {
                x ^= REDUCTION_POLYNOMIAL; // 17-bit reduction; result is 16-bit
            }
        }

        LOG_TABLE[0] = 0; // undefined sentinel; never read

        assertFullPeriod();
    }

    private Gf16() {
        // Static utility class; not instantiable.
    }

    /**
     * Verifies that 0x1100B is primitive: every value in 1..65535 appears exactly
     * once in the exp table (full period 65535) and the field closes back on 1.
     * Throws {@link Erasure16Exception} if the field is malformed.
     */
    private static void assertFullPeriod() {
        boolean[] seen = new boolean[FIELD_SIZE];
        for (int i = 0; i < ORDER; i++) {
            int v = EXP_TABLE[i];
            if (v == 0) {
                throw new Erasure16Exception(
                        "GF(2^16) table build failed: exp[" + i + "] == 0 (zero is not in the multiplicative group).");
            }

            if (seen[v]) {
                throw new Erasure16Exception(
                        "GF(2^16) reduction polynomial 0x1100B is not primitive: value " + v
                                + " repeats in the exp table before period 65535.");
            }

            seen[v] = true;
        }

        // All of 1..65535 must have been produced exactly once.
        for (int v = 1; v < FIELD_SIZE; v++) {
            if (!seen[v]) {
                throw new Erasure16Exception(
                        "GF(2^16) exp table does not cover the full multiplicative group: value " + v + " is missing.");
            }
        }

        // The group must wrap: alpha^Order == 1, i.e. exp[0] == 1.
        if (EXP_TABLE[0] != 1) {
            throw new Erasure16Exception("GF(2^16) table build failed: exp[0] != 1.");
        }
    }

    /**
     * Field multiplication. Returns 0 if either operand is 0, otherwise
     * {@code exp[(log[a] + log[b]) mod 65535]}. Operands are masked to 16 bits.
     */
    public static int mul(int a, int b) {
        a &= 0xFFFF;
        b &= 0xFFFF;
        if (a == 0 || b == 0) {
            return 0;
        }

        int sum = LOG_TABLE[a] + LOG_TABLE[b];
        if (sum >= ORDER) {
            sum -= ORDER; // sum is in 0..2*(Order-1); a single subtraction suffices for mod.
        }

        return EXP_TABLE[sum];
    }

    /**
     * Multiplicative inverse: {@code exp[(65535 - log[a]) mod 65535]} for {@code a != 0}.
     *
     * @throws Erasure16Exception if {@code a} is 0.
     */
    public static int inv(int a) {
        a &= 0xFFFF;
        if (a == 0) {
            throw new Erasure16Exception("GF(2^16): zero has no multiplicative inverse.");
        }

        int e = (ORDER - LOG_TABLE[a]) % ORDER;
        return EXP_TABLE[e];
    }

    /**
     * Field division {@code a / b == mul(a, inv(b))}.
     *
     * @throws Erasure16Exception if {@code b} is 0.
     */
    public static int div(int a, int b) {
        a &= 0xFFFF;
        b &= 0xFFFF;
        if (b == 0) {
            throw new Erasure16Exception("GF(2^16): division by zero.");
        }

        if (a == 0) {
            return 0;
        }

        int diff = LOG_TABLE[a] - LOG_TABLE[b];
        if (diff < 0) {
            diff += ORDER;
        }

        return EXP_TABLE[diff];
    }

    /**
     * Returns {@code alpha^e} (the exp table lookup). The exponent is reduced
     * modulo the group order 65535.
     */
    public static int exp(int e) {
        e %= ORDER;
        if (e < 0) {
            e += ORDER;
        }

        return EXP_TABLE[e];
    }

    /**
     * Returns the discrete logarithm of {@code a} base alpha (the log table lookup).
     * {@code log[0]} is an undefined sentinel.
     *
     * @throws Erasure16Exception if {@code a} is 0.
     */
    public static int log(int a) {
        a &= 0xFFFF;
        if (a == 0) {
            throw new Erasure16Exception("GF(2^16): logarithm of zero is undefined.");
        }

        return LOG_TABLE[a];
    }
}
