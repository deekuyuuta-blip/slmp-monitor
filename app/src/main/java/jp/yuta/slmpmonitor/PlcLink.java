package jp.yuta.slmpmonitor;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * アプリ全体で1つの通信状態（待受サーバ・スマホ内デバイスメモリ・ハートビート）。
 * 一覧画面と操作画面の両方から使う。メインスレッドから呼ぶこと。
 */
public final class PlcLink {

    public interface Listener {
        /** 待受の開始・停止・失敗が変わったとき（error は失敗時のみ） */
        void onServerStateChanged(String error);
    }

    public static final DeviceMemory mem = new DeviceMemory();

    private static SlmpServer server;
    private static int serverPort;
    private static String serverPlcIp = "";
    private static boolean starting;
    private static String heartbeatDevice = "";
    private static short heartbeatCount;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();

    private static final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (server != null && !heartbeatDevice.isEmpty()) {
                try {
                    Device d = Device.parse(heartbeatDevice);
                    heartbeatCount++;
                    if (d.bit) mem.writeBits(d.code, d.number, new boolean[]{(heartbeatCount & 1) != 0});
                    else mem.writeWords(d.code, d.number, new short[]{heartbeatCount});
                } catch (IllegalArgumentException ignored) {
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    static {
        main.postDelayed(heartbeat, 1000);
    }

    private PlcLink() {
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners(String error) {
        for (Listener l : new ArrayList<>(listeners)) l.onServerStateChanged(error);
    }

    public static SlmpServer server() {
        return server;
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static boolean isStarting() {
        return starting;
    }

    public static int serverPort() {
        return serverPort;
    }

    public static String serverPlcIp() {
        return serverPlcIp;
    }

    public static void setHeartbeatDevice(String dev) {
        heartbeatDevice = dev == null ? "" : dev;
    }

    /** 直近 staleMs 以内にPLCから要求があれば true */
    public static boolean isFresh(long staleMs) {
        SlmpServer s = server;
        if (s == null || s.lastRequestMillis == 0) return false;
        return System.currentTimeMillis() - s.lastRequestMillis <= staleMs;
    }

    public static void start(final int port, final String plcIp) {
        if (server != null || starting) return;
        starting = true;
        final SlmpServer s = new SlmpServer(mem, port, plcIp);
        new Thread(() -> {
            String err = null;
            try {
                s.start();
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String error = err;
            main.post(() -> {
                starting = false;
                if (error == null) {
                    server = s;
                    serverPort = port;
                    serverPlcIp = plcIp == null ? "" : plcIp;
                }
                notifyListeners(error == null ? null : "待受を開始できません（ポート " + port + "）: " + error);
            });
        }, "slmp-start").start();
    }

    public static void stop() {
        final SlmpServer s = server;
        server = null;
        if (s != null) new Thread(s::stop, "slmp-stop").start();
        notifyListeners(null);
    }

    /** 設定が変わっていれば待受をやり直す */
    public static void restartIfChanged(final int port, final String plcIp) {
        if (server == null) return;
        if (serverPort == port && serverPlcIp.equals(plcIp)) return;
        final SlmpServer old = server;
        server = null;
        starting = true;
        new Thread(() -> {
            old.stop();
            main.post(() -> {
                starting = false;
                start(port, plcIp);
            });
        }, "slmp-restart").start();
    }
}
