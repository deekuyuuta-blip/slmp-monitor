package jp.yuta.slmpmonitor;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * スマホからPLCへSLMP（バイナリ）で読み書きしに行く。
 * - 一覧・操作画面で使っているデバイスを周期的に一括読出しして、スマホ内メモリへ入れる
 * - 画面から書いた値は、すぐPLCへ書込む（読出しより優先）
 * - 応答が来なければ短い時間で送り直す（シンプルCPU通信のように長く止まらない）
 * 4Eフレーム（通し番号つき）で始め、PLCが答えなければ3Eフレームに切り替える。
 */
public final class PlcClient extends Link {

    private static final int TIMEOUT_UDP_MS = 300;
    private static final int TIMEOUT_TCP_MS = 1000;
    private static final int RETRIES = 3;
    private static final int MAX_WORDS = 960;
    private static final int MAX_BITS = 3584;

    private final DeviceMemory mem;
    private final SharedPreferences prefs;
    private final String ip;
    private final int port;
    private final boolean udp;
    private final int pollMs;

    private volatile boolean running;
    private Thread thread;
    private DatagramSocket ds;
    private Socket ts;
    private InputStream tin;
    private OutputStream tout;
    private boolean use4E = true;
    private boolean everOk;
    private int serial;
    private volatile boolean connectedNow;

    /** 書込み待ち（画面操作・ハートビート） */
    private final LinkedBlockingDeque<Write> writes = new LinkedBlockingDeque<>();
    /** これより古い書込みは送らない（通信が戻ったときに昔のボタン操作が効かないように） */
    private static final long WRITE_MAX_AGE_MS = 3000;

    private static final class Write {
        final int code, no;
        final short[] words;
        final boolean[] bits;
        final long at = System.currentTimeMillis();

        Write(int code, int no, short[] words, boolean[] bits) {
            this.code = code;
            this.no = no;
            this.words = words;
            this.bits = bits;
        }
    }

    /** 一括読出しの単位（ワード単位。ビットデバイスは16点=1ワード） */
    private static final class Range {
        final int code, start, words;

        Range(int code, int start, int words) {
            this.code = code;
            this.start = start;
            this.words = words;
        }
    }

    private List<Range> ranges = new ArrayList<>();
    private long rangesAt;

    public PlcClient(Context c, DeviceMemory mem, String ip, int port, boolean udp, int pollMs) {
        this.mem = mem;
        this.prefs = c.getApplicationContext().getSharedPreferences("slmp", Context.MODE_PRIVATE);
        this.ip = ip == null ? "" : ip.trim();
        this.port = port;
        this.udp = udp;
        this.pollMs = Math.max(50, pollMs);
    }

    // ------------------------------------------------------------ Link

    @Override
    public void start() throws IOException {
        if (!NetUtil.isIpv4(ip)) throw new IOException("設定でPLCのIPアドレスを入れてください");
        running = true;
        addLog("PLC " + ip + ":" + port + "（" + (udp ? "UDP" : "TCP") + "）へ読みに行きます　周期 " + pollMs + "ms");
        thread = new Thread(this::loop, "slmp-client");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        closeSockets();
        if (thread != null) thread.interrupt();
        addLog("停止");
    }

    @Override
    public List<String> clientIps() {
        if (!connectedNow) return Collections.emptyList();
        return Collections.singletonList(ip + ":" + port + "(" + (udp ? "UDP" : "TCP") + (use4E ? "・4E" : "・3E") + ")");
    }

    @Override
    public String waitingText() {
        return "PLCへ接続中";
    }

    private final Object signal = new Object();

    public void queueWords(int code, int no, short[] v) {
        if (!running) return;
        writes.add(new Write(code, no, v, null));
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    public void queueBits(int code, int no, boolean[] v) {
        if (!running) return;
        writes.add(new Write(code, no, null, v));
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    // ------------------------------------------------------------ 周期処理

    private void loop() {
        int failStreak = 0;
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                // 書込みを先に（押したボタンがすぐ効くように）
                Write w;
                while (running && (w = writes.poll()) != null) {
                    if (System.currentTimeMillis() - w.at > WRITE_MAX_AGE_MS) {
                        addLog("書込を取り消し " + Device.name(w.code, w.no) + "（通信できず時間が経ったため）");
                        continue;
                    }
                    try {
                        doWrite(w);
                    } catch (PlcError e) {
                        throw e;
                    } catch (IOException e) {
                        writes.addFirst(w);   // 通信が戻ったら送る（古くなったら取り消し）
                        throw e;
                    }
                }
                if (t0 - rangesAt > 2000) {
                    ranges = buildRanges();
                    rangesAt = t0;
                }
                for (Range r : ranges) {
                    if (!running) break;
                    if (!writes.isEmpty()) break;   // 書込みが来たら先にそちらを
                    short[] v = request(readFrame(r));
                    if (v.length < r.words) throw new IOException("読出し応答が短い " + Device.name(r.code, r.start));
                    mem.writeWords(r.code, r.start, java.util.Arrays.copyOf(v, r.words));
                }
                markOk();
                failStreak = 0;
            } catch (PlcError e) {
                // PLCが異常応答を返した＝通信自体はできている
                markOk();
                addLog(e.getMessage());
                sleepQuiet(1000);
            } catch (IOException e) {
                failStreak++;
                connectedNow = false;
                addLog("通信エラー: " + e.getMessage());
                closeSockets();
                if (!everOk) {
                    use4E = !use4E;
                    addLog((use4E ? "4E" : "3E") + "フレームで試します");
                }
                sleepQuiet(Math.min(2000, 300L * failStreak));
                continue;
            } catch (RuntimeException e) {
                addLog("処理エラー: " + e.getClass().getSimpleName() + " " + e.getMessage());
                sleepQuiet(1000);
            }
            long wait = pollMs - (System.currentTimeMillis() - t0);
            if (wait > 0) {
                // 待っている間に書込みが来たらすぐ次の周期へ（書込みは周期の先頭で順番どおり処理）
                synchronized (signal) {
                    if (writes.isEmpty() && running) {
                        try {
                            signal.wait(wait);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    }

    private void markOk() {
        lastRequestMillis = System.currentTimeMillis();
        requestCount.incrementAndGet();
        if (!connectedNow) addLog("PLCと通信できました（" + (use4E ? "4E" : "3E") + "フレーム）");
        connectedNow = true;
        everOk = true;
    }

    private void doWrite(Write w) throws IOException {
        String dev = Device.name(w.code, w.no);
        if (w.words != null) {
            request(writeWordsFrame(w));
            addLog("書込 " + dev + (w.words.length == 1 ? " = " + w.words[0] : " " + w.words.length + "ワード"));
            mem.writeWords(w.code, w.no, w.words);
        } else {
            request(writeBitsFrame(w));
            addLog("書込 " + dev + (w.bits.length == 1 ? (w.bits[0] ? " ON" : " OFF") : " " + w.bits.length + "点"));
            mem.writeBits(w.code, w.no, w.bits);
        }
    }

    /** 一覧・操作画面で使っているデバイスを、読出し単位にまとめる */
    private List<Range> buildRanges() {
        // コード → (ワード番号 → 使う)
        Map<Integer, java.util.TreeSet<Integer>> want = new TreeMap<>();
        ItemStore store = new ItemStore(prefs);
        for (MonitorItem it : store.loadItems()) {
            try {
                add(want, it.device(), it.format.words());
            } catch (RuntimeException ignored) {
            }
        }
        String json = prefs.getString("panel", null);
        if (json != null) {
            try {
                Panel p = Panel.fromJson(json);
                for (Panel.Screen s : p.screens) {
                    for (Panel.Part part : s.parts) {
                        for (String d : new String[]{part.device, part.lampDevice}) {
                            if (d == null || d.isEmpty()) continue;
                            try {
                                add(want, Device.parse(d), part.format.words());
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        List<Range> out = new ArrayList<>();
        for (Map.Entry<Integer, java.util.TreeSet<Integer>> e : want.entrySet()) {
            int code = e.getKey();
            boolean bit = Boolean.TRUE.equals(Device.isBitCode(code));
            int s = -1, last = -1;
            for (int n : e.getValue()) {
                // 近い（16ワード以内）ものはまとめて1回で読む
                if (s >= 0 && n - last <= 16 && n - s < MAX_WORDS) {
                    last = n;
                    continue;
                }
                if (s >= 0) out.add(new Range(code, bit ? s * 16 : s, last - s + 1));
                s = last = n;
            }
            if (s >= 0) out.add(new Range(code, bit ? s * 16 : s, last - s + 1));
        }
        if (out.isEmpty()) out.add(new Range(0xA8, 0, 1));   // 登録なしでも通信の確認のためD0を読む
        return out;
    }

    /** ワード番号（ビットデバイスは16点ごとの番号）で集める */
    private static void add(Map<Integer, java.util.TreeSet<Integer>> want, Device d, int words) {
        java.util.TreeSet<Integer> set = want.get(d.code);
        if (set == null) want.put(d.code, set = new java.util.TreeSet<>());
        if (d.bit) {
            set.add(d.number / 16);
        } else {
            for (int i = 0; i < words; i++) set.add(d.number + i);
        }
    }

    // ------------------------------------------------------------ フレーム

    /** ワード単位の一括読出し（ビットデバイスは16点=1ワード、start は先頭のデバイス番号） */
    private byte[] readFrame(Range r) {
        return command(0x0401, 0x0000, devSpec(r.start, r.code), le16(r.words));
    }

    private byte[] writeWordsFrame(Write w) {
        byte[] data = new byte[w.words.length * 2];
        for (int i = 0; i < w.words.length; i++) {
            data[i * 2] = (byte) w.words[i];
            data[i * 2 + 1] = (byte) (w.words[i] >> 8);
        }
        return command(0x1401, 0x0000, devSpec(w.no, w.code), le16(w.words.length), data);
    }

    private byte[] writeBitsFrame(Write w) {
        byte[] data = new byte[(w.bits.length + 1) / 2];
        for (int i = 0; i < w.bits.length; i++) if (w.bits[i]) data[i / 2] |= (byte) (i % 2 == 0 ? 0x10 : 0x01);
        return command(0x1401, 0x0001, devSpec(w.no, w.code), le16(w.bits.length), data);
    }

    private static byte[] devSpec(int no, int code) {
        return new byte[]{(byte) no, (byte) (no >> 8), (byte) (no >> 16), (byte) code};
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    /** 監視タイマ + コマンド + サブコマンド + 続き（ヘッダは送信時に付ける） */
    private static byte[] command(int cmd, int sub, byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x04);   // 監視タイマ 4×250ms = 1秒
        b.write(0x00);
        b.write(cmd);
        b.write(cmd >> 8);
        b.write(sub);
        b.write(sub >> 8);
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    /** PLCが異常の終了コードを返した */
    private static final class PlcError extends IOException {
        PlcError(String m) {
            super(m);
        }
    }

    /**
     * 1要求を送って応答データ（読出しならワード列）を返す。
     * 応答が来なければ送り直す。PLCが異常終了コードを返したら PlcError。
     */
    private short[] request(byte[] cmd) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= RETRIES && running; attempt++) {
            int ser = (serial = (serial + 1) & 0xFFFF);
            byte[] frame = frame(cmd, ser);
            try {
                byte[] resp = udp ? exchangeUdp(frame, ser) : exchangeTcp(frame, ser);
                int hl = use4E ? 13 : 9;
                int end = (resp[hl] & 0xFF) | ((resp[hl + 1] & 0xFF) << 8);
                if (end != 0) {
                    int c = (cmd[2] & 0xFF) | ((cmd[3] & 0xFF) << 8);
                    int no = (cmd[6] & 0xFF) | ((cmd[7] & 0xFF) << 8) | ((cmd[8] & 0xFF) << 16);
                    throw new PlcError(String.format(Locale.ROOT, "PLCが異常応答 %04X（%s %s）%s", end,
                            c == 0x1401 ? "書込" : "読出", Device.name(cmd[9] & 0xFF, no), hint(end)));
                }
                int n = (resp.length - hl - 2) / 2;
                short[] v = new short[n];
                for (int i = 0; i < n; i++) {
                    v[i] = (short) ((resp[hl + 2 + i * 2] & 0xFF) | ((resp[hl + 3 + i * 2] & 0xFF) << 8));
                }
                if (attempt > 0) addLog("再送で回復（" + attempt + "回目）");
                return v;
            } catch (PlcError e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (!udp) {
                    closeSockets();   // TCPはつなぎ直して送る
                }
            }
        }
        throw last != null ? last : new IOException("停止");
    }

    private static String hint(int end) {
        switch (end) {
            case 0xC059:
                return "：PLCがこのコマンドに対応していない";
            case 0xC056:
            case 0xC051:
                return "：デバイス番号が範囲外";
            case 0xC05C:
                return "：要求内容が不正";
            case 0xC061:
                return "：データ長が不正";
            default:
                if (end >= 0x4000 && end < 0x5000) return "：PLC側の設定（RUN中書込許可など）を確認";
                return "";
        }
    }

    private byte[] frame(byte[] cmd, int ser) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (use4E) {
            b.write(0x54);
            b.write(0x00);
            b.write(ser);
            b.write(ser >> 8);
            b.write(0x00);
            b.write(0x00);
        } else {
            b.write(0x50);
            b.write(0x00);
        }
        b.write(0x00);   // ネットワーク番号
        b.write(0xFF);   // PC番号
        b.write(0xFF);   // 要求先ユニットI/O番号 03FF
        b.write(0x03);
        b.write(0x00);   // 要求先ユニット局番号
        b.write(cmd.length);
        b.write(cmd.length >> 8);
        b.write(cmd, 0, cmd.length);
        return b.toByteArray();
    }

    /** 応答のヘッダを確かめる（4Eなら通し番号も一致すること） */
    private boolean matches(byte[] r, int n, int ser) {
        if (use4E) {
            return n >= 15 && (r[0] & 0xFF) == 0xD4 && r[1] == 0
                    && ((r[2] & 0xFF) | ((r[3] & 0xFF) << 8)) == ser;
        }
        return n >= 11 && (r[0] & 0xFF) == 0xD0 && r[1] == 0;
    }

    private byte[] exchangeUdp(byte[] frame, int ser) throws IOException {
        if (ds == null) {
            ds = new DatagramSocket();
            ds.connect(new InetSocketAddress(InetAddress.getByName(ip), port));
        }
        // 前の要求への遅れた応答が残っていたら捨てる（3Eは番号がないので取り違えないように）
        ds.setSoTimeout(1);
        byte[] buf = new byte[4096];
        try {
            for (int i = 0; i < 20; i++) ds.receive(new DatagramPacket(buf, buf.length));
        } catch (SocketTimeoutException ignored) {
        }
        ds.send(new DatagramPacket(frame, frame.length));
        long deadline = System.currentTimeMillis() + TIMEOUT_UDP_MS;
        while (true) {
            int left = (int) (deadline - System.currentTimeMillis());
            if (left <= 0) throw new IOException("応答なし（" + TIMEOUT_UDP_MS + "ms）");
            ds.setSoTimeout(left);
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                ds.receive(p);
            } catch (SocketTimeoutException e) {
                throw new IOException("応答なし（" + TIMEOUT_UDP_MS + "ms）");
            }
            if (matches(buf, p.getLength(), ser)) {
                int hl = use4E ? 13 : 9;
                int len = (buf[hl - 2] & 0xFF) | ((buf[hl - 1] & 0xFF) << 8);
                if (hl + len > p.getLength()) continue;
                return java.util.Arrays.copyOf(buf, hl + len);
            }
            // 番号違い（古い応答）は読み捨てて待ち続ける
        }
    }

    private byte[] exchangeTcp(byte[] frame, int ser) throws IOException {
        if (ts == null) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getByName(ip), port), 2000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(TIMEOUT_TCP_MS);
            ts = s;
            tin = s.getInputStream();
            tout = s.getOutputStream();
        }
        tout.write(frame);
        tout.flush();
        while (true) {
            int hl = use4E ? 13 : 9;
            byte[] h = readFully(tin, hl);
            int len = (h[hl - 2] & 0xFF) | ((h[hl - 1] & 0xFF) << 8);
            byte[] body = readFully(tin, len);
            byte[] r = new byte[hl + len];
            System.arraycopy(h, 0, r, 0, hl);
            System.arraycopy(body, 0, r, hl, len);
            if (matches(r, r.length, ser)) return r;
            if (!use4E) throw new IOException("想定外の応答");
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException("PLCが切断しました");
            off += r;
        }
        return b;
    }

    private void closeSockets() {
        if (ds != null) ds.close();
        ds = null;
        try {
            if (ts != null) ts.close();
        } catch (IOException ignored) {
        }
        ts = null;
        tin = null;
        tout = null;
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
