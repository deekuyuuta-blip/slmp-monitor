package jp.yuta.slmpmonitor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * スマホ内の仮想デバイスメモリ。
 * PLCがシンプルCPU通信で書込んだ値・読出しに来る値をここに保持する。
 * 初期値はすべて0／OFF。
 * スマホの画面から書いた値（＝PLCが読出しに来る値）は「ローカル値」として保存・復元できる。
 * アプリ再起動で0に戻り、PLCの読出しでPLC側まで0で上書きされるのを防ぐため。
 */
public final class DeviceMemory {

    private final Map<Long, Short> words = new HashMap<>();
    private final Set<Long> bitsOn = new HashSet<>();
    /** 画面から書いたデバイス（保存対象） */
    private final Set<Long> localWordKeys = new HashSet<>();
    private final Set<Long> localBitKeys = new HashSet<>();
    private volatile Runnable onLocalChange;

    /** 画面から書いたときに呼ばれる（スマホからPLCへ書きに行く方式で使う） */
    public interface WriteHook {
        void words(int code, int no, short[] values);

        /** momentary: 押している間だけONのボタン（OFFは必ず届ける） */
        void bits(int code, int no, boolean[] values, boolean momentary);
    }

    private volatile WriteHook writeHook;

    public void setWriteHook(WriteHook h) {
        writeHook = h;
    }

    private void hookWords(int code, int no, short[] v) {
        WriteHook h = writeHook;
        if (h != null) h.words(code, no, v.clone());
    }

    private void hookBits(int code, int no, boolean[] v, boolean momentary) {
        WriteHook h = writeHook;
        if (h != null) h.bits(code, no, v.clone(), momentary);
    }

    public void setOnLocalChange(Runnable r) {
        onLocalChange = r;
    }

    private void localChanged() {
        Runnable r = onLocalChange;
        if (r != null) r.run();
    }

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

    // ---- 画面からの書込み（保存対象） ----

    public void localWriteWords(int code, int no, short[] values) {
        synchronized (this) {
            writeWords(code, no, values);
            boolean bit = Boolean.TRUE.equals(Device.isBitCode(code));
            for (int i = 0; i < values.length; i++) {
                if (bit) for (int j = 0; j < 16; j++) localBitKeys.add(key(code, no + i * 16 + j));
                else localWordKeys.add(key(code, no + i));
            }
        }
        hookWords(code, no, values);
        localChanged();
    }

    public void localWriteBits(int code, int no, boolean[] values) {
        synchronized (this) {
            writeBits(code, no, values);
            for (int i = 0; i < values.length; i++) localBitKeys.add(key(code, no + i));
        }
        hookBits(code, no, values, false);
        localChanged();
    }

    /** 一時的な書込み（モーメンタリ用）。保存はしないが、保存済みのデバイスなら保存内容も更新 */
    public void tempWriteBits(int code, int no, boolean[] values) {
        boolean saved = false;
        synchronized (this) {
            writeBits(code, no, values);
            for (int i = 0; i < values.length; i++) if (localBitKeys.contains(key(code, no + i))) saved = true;
        }
        hookBits(code, no, values, true);
        if (saved) localChanged();
    }

    /** 保存用の文字列。例: "w:168:100=555;b:144:2000=1" */
    public synchronized String exportLocal() {
        StringBuilder b = new StringBuilder();
        for (long k : localWordKeys) {
            Short v = words.get(k);
            b.append("w:").append(k >>> 32).append(':').append((int) k).append('=').append(v == null ? 0 : v).append(';');
        }
        for (long k : localBitKeys) {
            b.append("b:").append(k >>> 32).append(':').append((int) k).append('=').append(bitsOn.contains(k) ? 1 : 0).append(';');
        }
        return b.toString();
    }

    /** 保存しておいた値を戻す。戻した数を返す */
    public synchronized int importLocal(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (String item : s.split(";")) {
            try {
                String[] kv = item.split("=");
                String[] a = kv[0].split(":");
                int code = Integer.parseInt(a[1]);
                int no = Integer.parseInt(a[2]);
                if (Device.isBitCode(code) == null) continue;
                long k = key(code, no);
                if ("w".equals(a[0])) {
                    words.put(k, Short.parseShort(kv[1]));
                    localWordKeys.add(k);
                } else {
                    setBitLocked(code, no, "1".equals(kv[1]));
                    localBitKeys.add(k);
                }
                n++;
            } catch (RuntimeException ignored) {
            }
        }
        return n;
    }

    public synchronized int localCount() {
        return localWordKeys.size() + localBitKeys.size();
    }

    /** 保存していた値を消す（0／OFFに戻す） */
    public void clearLocal() {
        synchronized (this) {
            for (long k : localWordKeys) words.remove(k);
            for (long k : localBitKeys) bitsOn.remove(k);
            localWordKeys.clear();
            localBitKeys.clear();
        }
        localChanged();
    }

    public synchronized void clear() {
        words.clear();
        bitsOn.clear();
    }
}
