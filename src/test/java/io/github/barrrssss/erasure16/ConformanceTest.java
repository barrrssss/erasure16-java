package io.github.barrrssss.erasure16;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The cross-language conformance gate. Parses
 * {@code external/erasure16-spec/vectors/MANIFEST.json} (pulled in via the git
 * submodule) and asserts that this Java implementation reproduces every field,
 * encode, and reconstruct vector byte-for-byte against the .NET-generated blobs.
 *
 * <p>The vector {@code *.bin} files are raw bytes (marked {@code binary} in the spec
 * repo's {@code .gitattributes}); they are read verbatim. Where the manifest lists a
 * SHA-256 for a file, this test also recomputes and checks it.
 *
 * <p>No external JSON library is used (the library has no runtime deps and the tests
 * use only JUnit); a minimal JSON parser is embedded below.
 */
class ConformanceTest {

    private static final Path VECTORS_DIR =
            Paths.get("external", "erasure16-spec", "vectors").toAbsolutePath();

    private static Map<String, Object> manifest;

    @BeforeAll
    static void loadManifest() throws IOException {
        Path manifestPath = VECTORS_DIR.resolve("MANIFEST.json");
        if (!Files.exists(manifestPath)) {
            fail("Conformance vectors not found at " + manifestPath
                    + ". Did the git submodule (external/erasure16-spec) get initialized?");
        }
        String json = new String(Files.readAllBytes(manifestPath), java.nio.charset.StandardCharsets.UTF_8);
        Object parsed = new Json(json).parse();
        assertTrue(parsed instanceof Map, "MANIFEST.json root must be a JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        manifest = m;
    }

    private static byte[] readVector(String name) {
        try {
            return Files.readAllBytes(VECTORS_DIR.resolve(name));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read vector blob: " + name, e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static int asInt(Object o) {
        return (int) ((Number) o).longValue();
    }

    /**
     * Every blob referenced in the manifest's {@code files} array must have the listed
     * byte length and SHA-256. This anchors the vectors as the byte-exact oracle: if
     * the checkout's bytes drift, every downstream comparison below is suspect.
     */
    @TestFactory
    List<DynamicTest> fileDigests() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object fileObj : asList(manifest.get("files"))) {
            Map<String, Object> file = asMap(fileObj);
            String path = (String) file.get("path");
            int length = asInt(file.get("length"));
            String expectedSha = (String) file.get("sha256");
            tests.add(DynamicTest.dynamicTest("file:" + path, () -> {
                byte[] bytes = readVector(path);
                assertEquals(length, bytes.length, "byte length mismatch for " + path);
                assertEquals(expectedSha, sha256Hex(bytes), "SHA-256 mismatch for " + path);
            }));
        }
        assertTrue(tests.size() > 0, "manifest.files must not be empty");
        return tests;
    }

    /**
     * Field vectors: sampled {@code mul(a,b)} and {@code inv(a)} values must match the
     * manifest. Also confirms the manifest's reduction polynomial and generator agree
     * with this implementation (SPEC.md section 1).
     */
    @TestFactory
    List<DynamicTest> fieldVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object caseObj : asList(manifest.get("field"))) {
            Map<String, Object> c = asMap(caseObj);
            String name = (String) c.get("name");

            tests.add(DynamicTest.dynamicTest("field-params:" + name, () -> {
                assertEquals(Gf16.REDUCTION_POLYNOMIAL, asInt(c.get("reductionPolynomial")),
                        "reduction polynomial must match the manifest");
                // generator alpha = 2 corresponds to exp(1) == 2.
                assertEquals(asInt(c.get("generator")), Gf16.exp(1),
                        "generator (alpha) must match the manifest");
            }));

            for (Object mulObj : asList(c.get("mul"))) {
                Map<String, Object> mul = asMap(mulObj);
                int a = asInt(mul.get("a"));
                int b = asInt(mul.get("b"));
                int product = asInt(mul.get("product"));
                tests.add(DynamicTest.dynamicTest("mul:" + name + ":" + a + "*" + b, () ->
                        assertEquals(product, Gf16.mul(a, b),
                                "mul(" + a + ", " + b + ") must match the manifest")));
            }

            for (Object invObj : asList(c.get("inv"))) {
                Map<String, Object> inv = asMap(invObj);
                int a = asInt(inv.get("a"));
                int expected = asInt(inv.get("inv"));
                tests.add(DynamicTest.dynamicTest("inv:" + name + ":" + a, () ->
                        assertEquals(expected, Gf16.inv(a),
                                "inv(" + a + ") must match the manifest")));
            }
        }
        assertTrue(tests.size() > 0, "manifest.field must not be empty");
        return tests;
    }

    /**
     * Encode vectors: load the K data shards, encode, and assert each produced parity
     * shard equals the expected parity blob byte-for-byte. With R == 0 there is no
     * parity, only validation.
     */
    @TestFactory
    List<DynamicTest> encodeVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object caseObj : asList(manifest.get("encode"))) {
            Map<String, Object> c = asMap(caseObj);
            String name = (String) c.get("name");
            tests.add(DynamicTest.dynamicTest("encode:" + name, () -> {
                int k = asInt(c.get("k"));
                int r = asInt(c.get("r"));
                int shardLen = asInt(c.get("shardLen"));
                List<Object> dataNames = asList(c.get("dataShards"));
                List<Object> parityNames = asList(c.get("parityShards"));

                assertEquals(k, dataNames.size(), "case " + name + ": dataShards count must equal K");
                assertEquals(r, parityNames.size(), "case " + name + ": parityShards count must equal R");

                byte[][] shards = new byte[k + r][];
                for (int i = 0; i < k; i++) {
                    byte[] d = readVector((String) dataNames.get(i));
                    assertEquals(shardLen, d.length, "data shard length must equal shardLen");
                    shards[i] = d;
                }
                for (int i = 0; i < r; i++) {
                    shards[k + i] = new byte[shardLen]; // parity slots to be filled
                }

                ReedSolomon16 rs = ReedSolomon16.create(k, r);
                rs.encode(shards);

                for (int p = 0; p < r; p++) {
                    byte[] expected = readVector((String) parityNames.get(p));
                    byte[] actual = shards[k + p];
                    assertBytesEqual(name + " parity[" + p + "]", expected, actual);
                }

                // A correctly encoded set must also verify.
                assertTrue(rs.verify(shards), "case " + name + ": verify() must hold after encode");
            }));
        }
        assertTrue(tests.size() > 0, "manifest.encode must not be empty");
        return tests;
    }

    /**
     * Reconstruct vectors: start from the present subset (>= K), reconstruct, and assert
     * every slot equals the expected fully-reconstructed blob byte-for-byte.
     */
    @TestFactory
    List<DynamicTest> reconstructVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object caseObj : asList(manifest.get("reconstruct"))) {
            Map<String, Object> c = asMap(caseObj);
            String name = (String) c.get("name");
            tests.add(DynamicTest.dynamicTest("reconstruct:" + name, () -> {
                int k = asInt(c.get("k"));
                int r = asInt(c.get("r"));
                int n = k + r;
                List<Object> presentList = asList(c.get("present"));
                List<Object> expectedNames = asList(c.get("expectedShards"));

                assertEquals(n, presentList.size(), "case " + name + ": present mask must have N entries");
                assertEquals(n, expectedNames.size(), "case " + name + ": expectedShards must have N entries");

                // Load the expected (full) shard set; this is the byte-exact target.
                byte[][] expected = new byte[n][];
                for (int i = 0; i < n; i++) {
                    expected[i] = readVector((String) expectedNames.get(i));
                }

                // Build the input: present shards carry their expected bytes; absent
                // shards are null and marked not-present.
                boolean[] present = new boolean[n];
                int presentCount = 0;
                byte[][] shards = new byte[n][];
                for (int i = 0; i < n; i++) {
                    present[i] = (Boolean) presentList.get(i);
                    if (present[i]) {
                        shards[i] = expected[i].clone();
                        presentCount++;
                    }
                }
                assertTrue(presentCount >= k,
                        "case " + name + ": present count " + presentCount + " must be >= K=" + k);

                ReedSolomon16 rs = ReedSolomon16.create(k, r);
                rs.reconstruct(shards, present);

                for (int i = 0; i < n; i++) {
                    assertTrue(present[i], "case " + name + ": shard " + i + " must be present after reconstruct");
                    assertBytesEqual(name + " shard[" + i + "]", expected[i], shards[i]);
                }
                assertTrue(rs.verify(shards), "case " + name + ": verify() must hold after reconstruct");
            }));
        }
        assertTrue(tests.size() > 0, "manifest.reconstruct must not be empty");
        return tests;
    }

    /**
     * Sanity check that the manifest has the structure we expect, so a malformed or
     * truncated checkout fails loudly here rather than silently skipping cases.
     */
    @Test
    void manifestHasExpectedSections() {
        assertEquals(1, asInt(manifest.get("manifestVersion")), "unexpected manifestVersion");
        assertTrue(manifest.get("field") instanceof List, "field section missing");
        assertTrue(manifest.get("encode") instanceof List, "encode section missing");
        assertTrue(manifest.get("reconstruct") instanceof List, "reconstruct section missing");
        assertTrue(manifest.get("files") instanceof List, "files section missing");
    }

    /**
     * Byte-exact comparison with a precise diagnostic on the first differing offset —
     * a vector mismatch indicates a real cross-language divergence, so we surface the
     * exact byte values.
     */
    private static void assertBytesEqual(String label, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            fail(label + ": length mismatch — expected " + expected.length + " bytes, got " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(label + ": byte mismatch at offset " + i
                        + " — expected 0x" + String.format("%02x", expected[i] & 0xFF)
                        + ", got 0x" + String.format("%02x", actual[i] & 0xFF)
                        + "\n  expected: " + toHex(expected)
                        + "\n  actual:   " + toHex(actual));
            }
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (objects, arrays, strings, numbers, bool, null).
    // Sufficient for MANIFEST.json; avoids any external test dependency.
    // ------------------------------------------------------------------
    private static final class Json {
        private final String s;
        private int pos;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            skipWs();
            Object value = readValue();
            skipWs();
            if (pos != s.length()) {
                throw new IllegalStateException("Trailing content in JSON at offset " + pos);
            }
            return value;
        }

        private Object readValue() {
            skipWs();
            char ch = peek();
            switch (ch) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                case 'f':
                    return readBoolean();
                case 'n':
                    return readNull();
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char ch = next();
                if (ch == '}') {
                    break;
                }
                if (ch != ',') {
                    throw new IllegalStateException("Expected ',' or '}' at offset " + (pos - 1));
                }
            }
            return map;
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWs();
                char ch = next();
                if (ch == ']') {
                    break;
                }
                if (ch != ',') {
                    throw new IllegalStateException("Expected ',' or ']' at offset " + (pos - 1));
                }
            }
            return list;
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char ch = next();
                if (ch == '"') {
                    break;
                }
                if (ch == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw new IllegalStateException("Bad escape \\" + esc + " at offset " + (pos - 1));
                    }
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        private Object readNumber() {
            int start = pos;
            boolean isDouble = false;
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (ch == '-' || ch == '+' || (ch >= '0' && ch <= '9')) {
                    pos++;
                } else if (ch == '.' || ch == 'e' || ch == 'E') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (isDouble) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }

        private Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalStateException("Invalid literal at offset " + pos);
        }

        private Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalStateException("Invalid literal at offset " + pos);
        }

        private void skipWs() {
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (pos >= s.length()) {
                throw new IllegalStateException("Unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (pos >= s.length()) {
                throw new IllegalStateException("Unexpected end of JSON");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            char ch = next();
            if (ch != c) {
                throw new IllegalStateException("Expected '" + c + "' but got '" + ch + "' at offset " + (pos - 1));
            }
        }
    }
}
