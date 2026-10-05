package net.primal.android.pcoc.hike;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Definite-length CBOR for one hike object. Verification accepts any definite-length
 * encoding of the same values, including non-shortest unsigned integers and any key order.
 */
final class Cbor {

    static final class Fail extends Exception {
        final String reason;

        Fail(String reason) {
            this.reason = reason;
        }
    }

    abstract static class Value {
    }

    static final class Uint extends Value {
        final long value;

        Uint(long value) {
            this.value = value;
        }
    }

    static final class Bytes extends Value {
        final byte[] value;

        Bytes(byte[] value) {
            this.value = value;
        }
    }

    static final class Text extends Value {
        final String value;

        Text(String value) {
            this.value = value;
        }
    }

    static final class Array extends Value {
        final List<Value> items;

        Array(List<Value> items) {
            this.items = items;
        }
    }

    static final class Map extends Value {
        final List<Pair> pairs;

        Map(List<Pair> pairs) {
            this.pairs = pairs;
        }
    }

    static final class Pair {
        final String key;
        final Value value;

        Pair(String key, Value value) {
            this.key = key;
            this.value = value;
        }
    }

    static final class Head {
        final Value value;
        final boolean trailing;

        Head(Value value, boolean trailing) {
            this.value = value;
            this.trailing = trailing;
        }
    }

    private static final class Cur {
        final byte[] data;
        int index;

        Cur(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - index;
        }

        int unsignedByte() throws Fail {
            if (index >= data.length) {
                throw new Fail("truncated");
            }
            return data[index++] & 0xff;
        }

        byte[] take(int length) throws Fail {
            if (length < 0 || remaining() < length) {
                throw new Fail("truncated");
            }
            byte[] out = new byte[length];
            System.arraycopy(data, index, out, 0, length);
            index += length;
            return out;
        }

        long readLen(int additional) throws Fail {
            if (additional <= 23) {
                return additional;
            }
            if (additional == 24) {
                return unsignedByte();
            }
            if (additional == 25) {
                byte[] raw = take(2);
                return ((raw[0] & 0xffL) << 8) | (raw[1] & 0xffL);
            }
            if (additional == 26) {
                byte[] raw = take(4);
                return ((raw[0] & 0xffL) << 24)
                    | ((raw[1] & 0xffL) << 16)
                    | ((raw[2] & 0xffL) << 8)
                    | (raw[3] & 0xffL);
            }
            if (additional == 27) {
                byte[] raw = take(8);
                long value = 0L;
                for (int i = 0; i < 8; i++) {
                    value = (value << 8) | (raw[i] & 0xffL);
                }
                return value;
            }
            throw new Fail("unsupported additional info");
        }

        int count(int additional) throws Fail {
            if (additional == 31) {
                throw new Fail("indefinite length");
            }
            long length = readLen(additional);
            if (Long.compareUnsigned(length, remaining()) > 0) {
                throw new Fail("length exceeds input");
            }
            if (length > Integer.MAX_VALUE) {
                throw new Fail("length");
            }
            return (int) length;
        }

        Value item() throws Fail {
            int head = unsignedByte();
            int major = head >> 5;
            int additional = head & 0x1f;
            switch (major) {
                case 0:
                    return new Uint(readLen(additional));
                case 1:
                    throw new Fail("negative integer");
                case 2:
                    return new Bytes(take(count(additional)));
                case 3:
                    return textItem(additional);
                case 4:
                    return arrayItem(additional);
                case 5:
                    return mapItem(additional);
                case 6:
                    throw new Fail("tag");
                default:
                    throw new Fail("simple or float");
            }
        }

        Value textItem(int additional) throws Fail {
            byte[] raw = take(count(additional));
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return new Text(decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString());
            } catch (CharacterCodingException error) {
                throw new Fail("text is not utf-8");
            }
        }

        Value arrayItem(int additional) throws Fail {
            int count = count(additional);
            List<Value> items = new ArrayList<Value>(Math.min(count, 64));
            for (int i = 0; i < count; i++) {
                items.add(item());
            }
            return new Array(items);
        }

        Value mapItem(int additional) throws Fail {
            int count = count(additional);
            List<Pair> pairs = new ArrayList<Pair>(Math.min(count, 16));
            HashSet<String> seen = new HashSet<String>();
            for (int i = 0; i < count; i++) {
                Value key = item();
                if (!(key instanceof Text)) {
                    throw new Fail("map key is not text");
                }
                String name = ((Text) key).value;
                if (!seen.add(name)) {
                    throw new Fail("duplicate key");
                }
                pairs.add(new Pair(name, item()));
            }
            return new Map(pairs);
        }
    }

    static Head decodeHead(byte[] data) throws Fail {
        if (data.length == 0) {
            throw new Fail("empty");
        }
        Cur cursor = new Cur(data);
        Value value = cursor.item();
        return new Head(value, cursor.index != data.length);
    }

    static byte[] encode(Value value) {
        ByteList out = new ByteList();
        encodeInto(out, value, false);
        return out.toByteArray();
    }

    static byte[] encodeWide(Value value) {
        ByteList out = new ByteList();
        encodeInto(out, value, true);
        return out.toByteArray();
    }

    private static void encodeInto(ByteList out, Value value, boolean wide) {
        if (value instanceof Uint) {
            putUint(out, 0, ((Uint) value).value, wide);
        } else if (value instanceof Bytes) {
            byte[] bytes = ((Bytes) value).value;
            putUint(out, 2, bytes.length, wide);
            out.add(bytes);
        } else if (value instanceof Text) {
            byte[] bytes = ((Text) value).value.getBytes(StandardCharsets.UTF_8);
            putUint(out, 3, bytes.length, wide);
            out.add(bytes);
        } else if (value instanceof Array) {
            List<Value> items = ((Array) value).items;
            putUint(out, 4, items.size(), wide);
            for (int i = 0; i < items.size(); i++) {
                encodeInto(out, items.get(i), wide);
            }
        } else if (value instanceof Map) {
            List<Pair> pairs = ((Map) value).pairs;
            putUint(out, 5, pairs.size(), wide);
            for (int i = 0; i < pairs.size(); i++) {
                byte[] key = pairs.get(i).key.getBytes(StandardCharsets.UTF_8);
                putUint(out, 3, key.length, wide);
                out.add(key);
                encodeInto(out, pairs.get(i).value, wide);
            }
        }
    }

    private static void putUint(ByteList out, int major, long number, boolean wide) {
        if (wide) {
            out.add((byte) ((major << 5) | 27));
            byte[] raw = new byte[8];
            raw[0] = (byte) (number >>> 56);
            raw[1] = (byte) (number >>> 48);
            raw[2] = (byte) (number >>> 40);
            raw[3] = (byte) (number >>> 32);
            raw[4] = (byte) (number >>> 24);
            raw[5] = (byte) (number >>> 16);
            raw[6] = (byte) (number >>> 8);
            raw[7] = (byte) number;
            out.add(raw);
            return;
        }
        if (Long.compareUnsigned(number, 24L) < 0) {
            out.add((byte) ((major << 5) | (int) number));
        } else if (Long.compareUnsigned(number, 256L) < 0) {
            out.add((byte) ((major << 5) | 24));
            out.add((byte) number);
        } else if (Long.compareUnsigned(number, 65536L) < 0) {
            out.add((byte) ((major << 5) | 25));
            out.add((byte) (number >>> 8));
            out.add((byte) number);
        } else if (Long.compareUnsigned(number, 0x1_0000_0000L) < 0) {
            out.add((byte) ((major << 5) | 26));
            out.add((byte) (number >>> 24));
            out.add((byte) (number >>> 16));
            out.add((byte) (number >>> 8));
            out.add((byte) number);
        } else {
            putUint(out, major, number, true);
        }
    }

    /** Small growing buffer so encode does not depend on {@code ByteArrayOutputStream} sync. */
    private static final class ByteList {
        private byte[] data = new byte[256];
        private int size;

        void add(byte value) {
            ensure(1);
            data[size++] = value;
        }

        void add(byte[] value) {
            ensure(value.length);
            System.arraycopy(value, 0, data, size, value.length);
            size += value.length;
        }

        byte[] toByteArray() {
            byte[] out = new byte[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }

        private void ensure(int extra) {
            if (size + extra <= data.length) {
                return;
            }
            int next = data.length;
            while (next < size + extra) {
                next *= 2;
            }
            byte[] grown = new byte[next];
            System.arraycopy(data, 0, grown, 0, size);
            data = grown;
        }
    }

    private Cbor() {
    }
}
