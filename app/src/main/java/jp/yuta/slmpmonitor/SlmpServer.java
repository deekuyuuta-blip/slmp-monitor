package jp.yuta.slmpmonitor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SLMP対応機器（QnA互換3Eフレーム・バイナリ）として待ち受けるサーバ。
 * 同じポート番号で TCP と UDP の両方を待ち受ける（シンプルCPU通信はUDPのことが多い）。
 * R04CPU等のシンプルCPU通信から来る一括読出し(0401)／一括書込み(1401)に応答する。
 * サブコマンド 0000/0001（Q互換）と 0002/0003（iQ-R形式）に対応。
 */
public final class SlmpServer {

    private static final int MAX_WORDS = 960;
    private static final int MAX_BITS = 7168;
    private static final int END_OK = 0x0000;
    private static final int END_RANGE = 0xC056;     // デバイス範囲外・未対応デバイス
    private static final int END_COMMAND = 0xC059;   // 未対応コマンド/サブコマンド
    private static final int END_LENGTH = 0xC061;    // データ長不一致

    private final DeviceMemory mem;
    private final int port;
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<>());
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private String lastLogBody;
    private int lastLogRepeat;

    private volatile ServerSocket server;
    private volatile DatagramSocket udp;
    /** UDPの相手IP → 最後に受信した時刻 */
    private final Map<String, Long> udpPeers = new HashMap<>();
    private static final long UDP_PEER_MS = 5000;
    private volatile boolean running;

    public final AtomicLong requestCount = new AtomicLong();
    public volatile long lastRequestMillis;   // System.currentTimeMillis()

    private final String allowedIp;

    /** allowedIp が空でなければ、そのIPからの接続だけ受け付ける */
    public SlmpServer(DeviceMemory mem, int port, String allowedIp) {
        this.mem = mem;
        this.port = port;
        this.allowedIp = allowedIp == null ? "" : allowedIp.trim();
    }

    public SlmpServer(DeviceMemory mem, int port) {
        this(mem, port, "");
    }

    /** 接続中の相手IP一覧 */
    public List<String> clientIps() {
        List<String> l = new ArrayList<>();
        synchronized (clients) {
            for (Socket s : clients) {
                String ip = s.getInetAddress().getHostAddress() + "(TCP)";
                if (!l.contains(ip)) l.add(ip);
            }
        }
        long now = System.currentTimeMillis();
        synchronized (udpPeers) {
            for (Map.Entry<String, Long> e : udpPeers.entrySet()) {
                if (now - e.getValue() <= UDP_PEER_MS) l.add(e.getKey() + "(UDP)");
            }
        }
        return l;
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        DatagramSocket ds;
        try {
            ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            ss.close();
            throw e;
        }
        server = ss;
        udp = ds;
        running = true;
        addLog("ポート " + port + " で待受開始（TCP・UDP）" + (allowedIp.isEmpty() ? "（接続元の制限なし）" : "（許可: " + allowedIp + "）"));
        Thread t = new Thread(this::acceptLoop, "slmp-accept");
        t.setDaemon(true);
        t.start();
        Thread u = new Thread(this::udpLoop, "slmp-udp");
        u.setDaemon(true);
        u.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (udp != null) udp.close();
        synchronized (clients) {
            for (Socket s : clients) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            clients.clear();
        }
        addLog("待受停止");
    }

    public boolean isRunning() {
        return running;
    }

    public int clientCount() {
        return clientIps().size();
    }

    // ---- ログ（同じ内容が続くときはまとめる） ----

    private synchronized void addLog(String body) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        if (body.equals(lastLogBody) && !log.isEmpty()) {
            lastLogRepeat++;
            log.pollLast();
            log.addLast(time + " " + body + "  ×" + lastLogRepeat);
            return;
        }
        lastLogBody = body;
        lastLogRepeat = 1;
        log.addLast(time + " " + body);
        while (log.size() > 200) log.pollFirst();
    }

    /** 外からログに1行残す */
    public void note(String s) {
        addLog(s);
    }

    public synchronized List<String> logLines() {
        return new ArrayList<>(log);
    }

    // ---- 接続処理 ----

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                String ip = s.getInetAddress().getHostAddress();
                if (!allowedIp.isEmpty() && !allowedIp.equals(ip)) {
                    addLog("接続拒否 " + ip + "（設定のPLC IP " + allowedIp + " と不一致）");
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                s.setTcpNoDelay(true);
                clients.add(s);
                addLog("PLC接続 " + s.getInetAddress().getHostAddress());
                Thread t = new Thread(() -> serve(s), "slmp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) addLog("待受エラー: " + e.getMessage());
            } catch (Throwable e) {
                if (running) addLog("待受処理エラー: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
    }

    private void udpLoop() {
        byte[] buf = new byte[8192];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                String ip = p.getAddress().getHostAddress();
                if (!allowedIp.isEmpty() && !allowedIp.equals(ip)) {
                    addLog("UDP受信拒否 " + ip + "（設定のPLC IP " + allowedIp + " と不一致）");
                    continue;
                }
                int n = p.getLength();
                int hl = headerLength(buf, n);   // 3E=9, 4E=13
                if (hl < 0) {
                    addLog(ip + " UDP: 3E/4Eフレーム以外を受信 " + hex(buf, Math.min(n, 8)));
                    continue;
                }
                int len = u16(buf, hl - 2);
                if (hl + len > n) {
                    addLog(ip + " UDP: データ長が不正");
                    continue;
                }
                boolean first;
                synchronized (udpPeers) {
                    Long last = udpPeers.put(ip, System.currentTimeMillis());
                    first = last == null || System.currentTimeMillis() - last > UDP_PEER_MS;
                }
                if (first) addLog("PLC受信開始 " + ip + "（UDP・" + (hl == 13 ? "4E" : "3E") + "フレーム）");
                byte[] h = java.util.Arrays.copyOfRange(buf, 0, hl);
                byte[] body = java.util.Arrays.copyOfRange(buf, hl, hl + len);
                byte[] r = handle(ip, h, body);
                udp.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
            } catch (IOException e) {
                if (running) addLog("UDPエラー: " + e.getMessage());
                if (udp.isClosed()) {
                    if (running) addLog("UDPの待受が閉じられました（待受停止→開始でやり直してください）");
                    break;
                }
            } catch (Throwable e) {
                // 想定外のエラーでも受信を止めない（止まるとPLCからは「返事なし」になる）
                if (running) addLog("UDP処理エラー: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
    }

    private void serve(Socket s) {
        String peer = s.getInetAddress().getHostAddress();
        try {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (running) {
                byte[] sh = readFully(in, 2, true);
                if (sh == null) break;   // 相手が切断
                int hl = headerLength(new byte[]{sh[0], sh[1], 0, 0, 0, 0}, 13);
                if (hl < 0) {
                    addLog(peer + " 3E/4Eフレーム以外を受信 " + hex(sh, 2));
                    break;
                }
                byte[] rest = readFully(in, hl - 2, false);
                byte[] h = new byte[hl];
                h[0] = sh[0];
                h[1] = sh[1];
                System.arraycopy(rest, 0, h, 2, hl - 2);
                int len = u16(h, hl - 2);
                byte[] body = readFully(in, len, false);
                out.write(handle(peer, h, body));
                out.flush();
            }
        } catch (IOException e) {
            if (running) addLog(peer + " 通信エラー: " + e.getMessage());
        } catch (Throwable e) {
            if (running) addLog(peer + " 処理エラー: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            clients.remove(s);
            try {
                s.close();
            } catch (IOException ignored) {
            }
            if (running) addLog("PLC切断 " + peer);
        }
    }

    /** 1要求を処理して応答フレームを返す（テストから直接呼べるよう公開） */
    public byte[] handle(String peer, byte[] h, byte[] body) {
        requestCount.incrementAndGet();
        lastRequestMillis = System.currentTimeMillis();
        if (body.length < 6) return response(h, END_LENGTH, null, 0, 0);
        int cmd = u16(body, 2);
        int sub = u16(body, 4);
        boolean iqr = sub == 0x0002 || sub == 0x0003;
        boolean bitUnit = sub == 0x0001 || sub == 0x0003;
        if (cmd == 0x0101 && sub == 0x0000) {
            // CPU型名読出し（相手がCPUかを確かめに来ることがある）
            byte[] d = new byte[18];
            byte[] name = "R04CPU          ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, d, 0, 16);
            d[16] = 0x00;
            d[17] = 0x48;   // R04CPU の型名コード 4800h
            addLog(peer + " 型名読出し → R04CPU");
            return response(h, END_OK, d, cmd, sub);
        }
        if (cmd == 0x0619 && sub == 0x0000) {
            // 折返しテスト：受け取ったデータをそのまま返す
            byte[] d = java.util.Arrays.copyOfRange(body, 6, body.length);
            addLog(peer + " 折返しテスト");
            return response(h, END_OK, d, cmd, sub);
        }
        if ((cmd != 0x0401 && cmd != 0x1401) || sub > 0x0003) {
            addLog(String.format(Locale.ROOT, "%s 未対応コマンド %04X/%04X", peer, cmd, sub));
            return response(h, END_COMMAND, null, cmd, sub);
        }
        int headLen = iqr ? 8 : 6;
        if (body.length < 6 + headLen) return response(h, END_LENGTH, null, cmd, sub);
        int p = 6;
        int no = iqr ? (int) u32(body, p) : ((body[p] & 0xFF) | ((body[p + 1] & 0xFF) << 8) | ((body[p + 2] & 0xFF) << 16));
        int code = iqr ? u16(body, p + 4) : (body[p + 3] & 0xFF);
        int points = u16(body, p + headLen - 2);
        int dataStart = 6 + headLen;
        String dev = Device.name(code, no);
        boolean write = cmd == 0x1401;

        Boolean isBit = Device.isBitCode(code);
        if (isBit == null || points < 1 || points > (bitUnit ? MAX_BITS : MAX_WORDS) || no < 0) {
            addLog(peer + " 範囲外 " + dev + " " + points + "点");
            return response(h, END_RANGE, null, cmd, sub);
        }
        if (bitUnit && !isBit) {
            addLog(peer + " ワードデバイスへのビット単位アクセス " + dev);
            return response(h, END_RANGE, null, cmd, sub);
        }

        try {
            if (!write) {
                byte[] data;
                if (bitUnit) {
                    boolean[] v = mem.readBits(code, no, points);
                    data = new byte[(points + 1) / 2];
                    for (int i = 0; i < points; i++) if (v[i]) data[i / 2] |= (byte) (i % 2 == 0 ? 0x10 : 0x01);
                } else {
                    short[] v = mem.readWords(code, no, points);
                    data = new byte[points * 2];
                    for (int i = 0; i < points; i++) {
                        data[i * 2] = (byte) v[i];
                        data[i * 2 + 1] = (byte) (v[i] >> 8);
                    }
                }
                addLog(peer + " 読出 " + dev + " " + points + (bitUnit ? "点(ビット)" : "ワード"));
                return response(h, END_OK, data, cmd, sub);
            } else {
                int need = bitUnit ? (points + 1) / 2 : points * 2;
                if (body.length != dataStart + need) {
                    addLog(peer + " 書込データ長不一致 " + dev);
                    return response(h, END_LENGTH, null, cmd, sub);
                }
                if (bitUnit) {
                    boolean[] v = new boolean[points];
                    for (int i = 0; i < points; i++) {
                        int b = body[dataStart + i / 2] & 0xFF;
                        v[i] = ((i % 2 == 0) ? (b >> 4) : (b & 0x0F)) != 0;
                    }
                    mem.writeBits(code, no, v);
                } else {
                    short[] v = new short[points];
                    for (int i = 0; i < points; i++) v[i] = (short) u16(body, dataStart + i * 2);
                    mem.writeWords(code, no, v);
                }
                addLog(peer + " 書込 " + dev + " " + points + (bitUnit ? "点(ビット)" : "ワード"));
                return response(h, END_OK, null, cmd, sub);
            }
        } catch (IllegalArgumentException e) {
            addLog(peer + " " + e.getMessage() + " " + dev);
            return response(h, END_RANGE, null, cmd, sub);
        }
    }

    /** 先頭がサブヘッダ 50 00（3E）なら9、54 00（4E）なら13、それ以外は -1 */
    private static int headerLength(byte[] b, int n) {
        if (n >= 9 && (b[0] & 0xFF) == 0x50 && b[1] == 0x00) return 9;
        if (n >= 13 && (b[0] & 0xFF) == 0x54 && b[1] == 0x00) return 13;
        return -1;
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format(Locale.ROOT, "%02X ", b[i] & 0xFF));
        return sb.toString().trim();
    }

    /**
     * 応答。3E: D0 00 / 4E: D4 00 + シリアル番号 + 00 00、
     * 続けて要求と同じ ネットワーク/PC/I/O/局番 + データ長 + 終了コード + データ
     */
    private static byte[] response(byte[] h, int end, byte[] data, int cmd, int sub) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int r = h.length - 7;   // ネットワーク番号の位置（3E=2, 4E=6）
        if (r == 6) {
            b.write(0xD4);
            b.write(0x00);
            b.write(h, 2, 4);   // シリアル番号 + 00 00 をそのまま返す
        } else {
            b.write(0xD0);
            b.write(0x00);
        }
        b.write(h, r, 5);
        byte[] payload;
        if (end == END_OK) {
            payload = data == null ? new byte[0] : data;
        } else {
            // 異常時：エラー情報（要求元のネットワーク/PC/I/O/局番 + コマンド + サブコマンド）
            payload = new byte[]{h[r], h[r + 1], h[r + 2], h[r + 3], h[r + 4],
                    (byte) cmd, (byte) (cmd >> 8), (byte) sub, (byte) (sub >> 8)};
        }
        int len = 2 + payload.length;
        b.write(len & 0xFF);
        b.write((len >> 8) & 0xFF);
        b.write(end & 0xFF);
        b.write((end >> 8) & 0xFF);
        b.write(payload, 0, payload.length);
        return b.toByteArray();
    }

    private static byte[] readFully(InputStream in, int n, boolean eofOk) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                if (eofOk && off == 0) return null;
                throw new EOFException("途中で切断されました");
            }
            off += r;
        }
        return buf;
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }
}
