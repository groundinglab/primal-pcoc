package net.primal.android.pcoc.hike;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Section 9 JSON. One value, no BOM, duplicate member names are not a successful read.
 * Integers are not parsed as binary64.
 */
final class IndexJson {

    static final class Beacon {
        final byte[] natural;
        final long height;
        final Long timestampUnixMs;

        Beacon(byte[] natural, long height, Long timestampUnixMs) {
            this.natural = natural;
            this.height = height;
            this.timestampUnixMs = timestampUnixMs;
        }
    }

    private static final BigInteger MAX_U64 = new BigInteger("18446744073709551615");

    private final String text;
    private int index;

    private IndexJson(String text) {
        this.text = text;
    }

    static Beacon beacon(String body) {
        Value top = parse(body);
        if (top == null || top.kind != Kind.OBJECT) {
            return null;
        }
        Value header = top.object.get("header");
        if (header == null || header.kind != Kind.OBJECT) {
            return null;
        }
        Value hash = header.object.get("hash");
        Value height = header.object.get("height");
        if (hash == null || hash.kind != Kind.STRING || height == null || !height.fitsU64) {
            return null;
        }
        byte[] display = hex32(hash.text);
        if (display == null) {
            return null;
        }
        Long timestamp = null;
        Value stamp = header.object.get("timestamp_unix_ms");
        if (stamp != null && stamp.fitsU64) {
            timestamp = Long.valueOf(stamp.bits);
        }
        return new Beacon(reverse(display), height.bits, timestamp);
    }

    /**
     * Mainnet proxy. {@code network} must be {@code main}.
     * The LAN {@code synthetic} acceptance in {@code proxy_matches} is not this phone.
     */
    static boolean proxyMain(String document, PathBody.Locators locators) {
        Value top = parse(document);
        if (top == null || top.kind != Kind.OBJECT) {
            return false;
        }
        Value network = top.object.get("network");
        if (network == null || network.kind != Kind.STRING || !"main".equals(network.text)) {
            return false;
        }
        Value plant = top.object.get("plant");
        if (plant == null || plant.kind != Kind.OBJECT) {
            return false;
        }
        byte[] root = hex32(stringField(plant, "root"));
        byte[] txidDisplay = hex32(stringField(plant, "txid"));
        byte[] blockDisplay = hex32(stringField(plant, "block_hash"));
        Integer vout = u32(plant.object.get("vout"));
        Integer blockHeight = u32(plant.object.get("block_height"));
        if (root == null || txidDisplay == null || blockDisplay == null || vout == null || blockHeight == null) {
            return false;
        }
        return java.util.Arrays.equals(root, locators.root)
            && java.util.Arrays.equals(reverse(txidDisplay), locators.txid)
            && vout.intValue() == locators.vout
            && java.util.Arrays.equals(reverse(blockDisplay), locators.blockHash)
            && blockHeight.intValue() == locators.blockHeight;
    }

    static byte[] hex32(String spelling) {
        if (spelling == null || spelling.length() != 64) {
            return null;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int high = digit(spelling.charAt(i * 2));
            int low = digit(spelling.charAt(i * 2 + 1));
            if (high < 0 || low < 0) {
                return null;
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    static byte[] reverse(byte[] natural) {
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = natural[31 - i];
        }
        return out;
    }

    private static String stringField(Value object, String key) {
        Value value = object.object.get(key);
        if (value == null || value.kind != Kind.STRING) {
            return null;
        }
        return value.text;
    }

    private static Integer u32(Value value) {
        if (value == null || !value.fitsU64 || Long.compareUnsigned(value.bits, 0xffff_ffffL) > 0) {
            return null;
        }
        return Integer.valueOf((int) value.bits);
    }

    private static int digit(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    private static Value parse(String body) {
        if (body == null || body.isEmpty() || body.charAt(0) == '\uFEFF') {
            return null;
        }
        IndexJson parser = new IndexJson(body);
        try {
            Value value = parser.value();
            parser.skipWs();
            if (parser.index != parser.text.length()) {
                return null;
            }
            return value;
        } catch (Parse error) {
            return null;
        }
    }

    private Value value() throws Parse {
        skipWs();
        if (index >= text.length()) {
            throw new Parse();
        }
        char character = text.charAt(index);
        if (character == '{') {
            return object();
        }
        if (character == '[') {
            return array();
        }
        if (character == '"') {
            return new Value(Kind.STRING, string(), false, false, 0L, null, null);
        }
        if (character == 't' || character == 'f') {
            return bool();
        }
        if (character == 'n') {
            literal("null");
            return new Value(Kind.NULL, null, false, false, 0L, null, null);
        }
        if (character == '-' || (character >= '0' && character <= '9')) {
            return number();
        }
        throw new Parse();
    }

    private Value object() throws Parse {
        expect('{');
        Map<String, Value> fields = new LinkedHashMap<String, Value>();
        skipWs();
        if (peek('}')) {
            index++;
            return new Value(Kind.OBJECT, null, false, false, 0L, fields, null);
        }
        while (true) {
            skipWs();
            if (index >= text.length() || text.charAt(index) != '"') {
                throw new Parse();
            }
            String key = string();
            skipWs();
            expect(':');
            Value child = value();
            if (fields.containsKey(key)) {
                throw new Parse();
            }
            fields.put(key, child);
            skipWs();
            if (peek('}')) {
                index++;
                break;
            }
            expect(',');
        }
        return new Value(Kind.OBJECT, null, false, false, 0L, fields, null);
    }

    private Value array() throws Parse {
        expect('[');
        List<Value> items = new ArrayList<Value>();
        skipWs();
        if (peek(']')) {
            index++;
            return new Value(Kind.ARRAY, null, false, false, 0L, null, items);
        }
        while (true) {
            items.add(value());
            skipWs();
            if (peek(']')) {
                index++;
                break;
            }
            expect(',');
        }
        return new Value(Kind.ARRAY, null, false, false, 0L, null, items);
    }

    private Value bool() throws Parse {
        if (text.startsWith("true", index)) {
            index += 4;
            return new Value(Kind.BOOL, null, true, false, 0L, null, null);
        }
        if (text.startsWith("false", index)) {
            index += 5;
            return new Value(Kind.BOOL, null, false, false, 0L, null, null);
        }
        throw new Parse();
    }

    private Value number() throws Parse {
        int start = index;
        if (peek('-')) {
            index++;
        }
        if (index >= text.length() || !digitAt(index)) {
            throw new Parse();
        }
        if (text.charAt(index) == '0') {
            index++;
            if (index < text.length() && digitAt(index)) {
                throw new Parse();
            }
        } else {
            while (index < text.length() && digitAt(index)) {
                index++;
            }
        }
        boolean real = false;
        if (peek('.')) {
            real = true;
            index++;
            int fraction = index;
            while (index < text.length() && digitAt(index)) {
                index++;
            }
            if (index == fraction) {
                throw new Parse();
            }
        }
        if (peek('e') || peek('E')) {
            real = true;
            index++;
            if (peek('+') || peek('-')) {
                index++;
            }
            int exponent = index;
            while (index < text.length() && digitAt(index)) {
                index++;
            }
            if (index == exponent) {
                throw new Parse();
            }
        }
        String token = text.substring(start, index);
        if (real || token.charAt(0) == '-') {
            return new Value(real ? Kind.REAL : Kind.INTEGER, token, false, false, 0L, null, null);
        }
        BigInteger magnitude = new BigInteger(token);
        if (magnitude.signum() < 0 || magnitude.compareTo(MAX_U64) > 0) {
            return new Value(Kind.INTEGER, token, false, false, 0L, null, null);
        }
        return new Value(Kind.INTEGER, token, false, true, magnitude.longValue(), null, null);
    }

    private String string() throws Parse {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (index < text.length()) {
            char character = text.charAt(index++);
            if (character == '"') {
                return out.toString();
            }
            if (character == '\\') {
                if (index >= text.length()) {
                    throw new Parse();
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(escaped);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            throw new Parse();
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int nibble = digit(text.charAt(index++));
                            if (nibble < 0) {
                                throw new Parse();
                            }
                            code = (code << 4) | nibble;
                        }
                        out.append((char) code);
                        break;
                    default:
                        throw new Parse();
                }
            } else if (character < 0x20) {
                throw new Parse();
            } else {
                out.append(character);
            }
        }
        throw new Parse();
    }

    private void literal(String expected) throws Parse {
        if (!text.startsWith(expected, index)) {
            throw new Parse();
        }
        index += expected.length();
    }

    private void expect(char character) throws Parse {
        if (index >= text.length() || text.charAt(index) != character) {
            throw new Parse();
        }
        index++;
    }

    private boolean peek(char character) {
        return index < text.length() && text.charAt(index) == character;
    }

    private boolean digitAt(int at) {
        char character = text.charAt(at);
        return character >= '0' && character <= '9';
    }

    private void skipWs() {
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                return;
            }
            index++;
        }
    }

    private enum Kind {
        OBJECT,
        ARRAY,
        STRING,
        INTEGER,
        REAL,
        BOOL,
        NULL
    }

    private static final class Value {
        final Kind kind;
        final String text;
        final boolean bool;
        final boolean fitsU64;
        final long bits;
        final Map<String, Value> object;
        final List<Value> array;

        Value(
            Kind kind,
            String text,
            boolean bool,
            boolean fitsU64,
            long bits,
            Map<String, Value> object,
            List<Value> array
        ) {
            this.kind = kind;
            this.text = text;
            this.bool = bool;
            this.fitsU64 = fitsU64;
            this.bits = bits;
            this.object = object;
            this.array = array;
        }
    }

    private static final class Parse extends Exception {
    }
}
