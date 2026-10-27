package jp.yuta.slmpmonitor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * スマホ内の仮想デバイスメモリ。
 * PLCがシンプルCPU通信で書込んだ値・読出しに来る値をここに保持する。
 * 初期値はすべて0／OFF（アプリ再起動で消える）。
 */
public final class DeviceMemory {

    private final Map<Long, Short> words = new HashMap<>();
    private final Set<Long> bitsOn = new HashSet<>();

    private static long key(int code, int no) {
        return ((long) code << 32) | (no & 0xFFFFFFFFL);
    }

    private static boolean requireKnown(int code) {
        Boolean b = Device.isBitCode(code);
        if (b == null) throw new IllegalArgumentException("未対応のデバイスコード");
        return b;
    }

    /** ワード単位の読出し。ビットデバイスは16点を1ワードにまとめる（下位ビットが若番）。 */
    public synchronized short[] readWords(int code, int no, int points) {
        boolean bit = requireKnown(code);
        short[] v = new short[points];
        for (int i = 0; i < points; i++) {
            if (bit) {
                int w = 0;
                for (int j = 0; j < 16; j++) if (bitsOn.contains(key(code, no + i * 16 + j))) w |= 1 << j;
                v[i] = (short) w;
            } else {
                Short s = words.get(key(code, no + i));
                v[i] = s == null ? 0 : s;
            }
        }
        return v;
    }

    public synchronized void writeWords(int code, int no, short[] values) {
        boolean bit = requireKnown(code);
        for (int i = 0; i < values.length; i++) {
            if (bit) {
                for (int j = 0; j < 16; j++) setBitLocked(code, no + i * 16 + j, (values[i] & (1 << j)) != 0);
            } else {
                words.put(key(code, no + i), values[i]);
            }
        }
    }

    /** ビット単位の読出し（ビットデバイスのみ） */
    public synchronized boolean[] readBits(int code, int no, int points) {
        if (!requireKnown(code)) throw new IllegalArgumentException("ワードデバイスにビット単位アクセス");
        boolean[] v = new boolean[points];
        for (int i = 0; i < points; i++) v[i] = bitsOn.contains(key(code, no + i));
        return v;
    }

    public synchronized void writeBits(int code, int no, boolean[] values) {
        if (!requireKnown(code)) throw new IllegalArgumentException("ワードデバイスにビット単位アクセス");
        for (int i = 0; i < values.length; i++) setBitLocked(code, no + i, values[i]);
    }

    private void setBitLocked(int code, int no, boolean on) {
        if (on) bitsOn.add(key(code, no));
        else bitsOn.remove(key(code, no));
    }

    public synchronized void clear() {
        words.clear();
        bitsOn.clear();
    }
}
