package jp.yuta.slmpmonitor;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ポート探索：たくさんのUDPポートで同時に待ち受け、PLCから届いたポート番号を調べる。
 * 交信相手の種別によってポート番号が固定で表示されないとき用。返事はしない。
 */
public final class PortScan {

    public interface Progress {
        void update(String text);
    }

    /** from〜to のUDPポートで seconds 秒待ち、届いた「ポート → 送信元・先頭バイト」を返す */
    public static Map<Integer, String> run(int from, int to, int seconds, Progress progress) {
        Map<Integer, String> found = new LinkedHashMap<>();
        List<DatagramChannel> chans = new ArrayList<>();
        Selector sel = null;
        int opened = 0;
        try {
            sel = Selector.open();
            for (int p = from; p <= to; p++) {
                try {
                    DatagramChannel c = DatagramChannel.open();
                    c.socket().setReuseAddress(true);
                    c.bind(new InetSocketAddress(p));
                    c.configureBlocking(false);
                    c.register(sel, SelectionKey.OP_READ, p);
                    chans.add(c);
                    opened++;
                } catch (Exception ignored) {
                    // 使用中・開けないポートは飛ばす
                }
            }
            ByteBuffer buf = ByteBuffer.allocate(2048);
            long end = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < end) {
                long left = (end - System.currentTimeMillis()) / 1000;
                progress.update(String.format(Locale.ROOT, "%d〜%d のうち %d ポートで待受中… 残り %d 秒\n見つかった: %s",
                        from, to, opened, left, found.isEmpty() ? "まだなし" : found.keySet().toString()));
                if (sel.select(500) == 0) continue;
                for (SelectionKey k : sel.selectedKeys()) {
                    DatagramChannel c = (DatagramChannel) k.channel();
                    buf.clear();
                    InetSocketAddress src = (InetSocketAddress) c.receive(buf);
                    if (src == null) continue;
                    int port = (Integer) k.attachment();
                    if (found.containsKey(port)) continue;
                    buf.flip();
                    StringBuilder sb = new StringBuilder();
                    int n = Math.min(buf.remaining(), 24);
                    for (int i = 0; i < n; i++) sb.append(String.format(Locale.ROOT, "%02X ", buf.get(i) & 0xFF));
                    found.put(port, src.getAddress().getHostAddress() + ":" + src.getPort()
                            + "  " + buf.remaining() + "バイト\n  " + sb.toString().trim());
                }
                sel.selectedKeys().clear();
            }
        } catch (Exception e) {
            found.put(-1, "エラー: " + e);
        } finally {
            for (DatagramChannel c : chans) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
            try {
                if (sel != null) sel.close();
            } catch (Exception ignored) {
            }
        }
        return found;
    }
}
