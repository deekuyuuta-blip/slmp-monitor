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

    private static android.content.SharedPreferences prefs;
    private static android.content.Context app;
    private static android.net.wifi.WifiManager.WifiLock wifiLock;
    private static android.net.Network boundNetwork;
    private static String bindNote = "";

    /**
     * 通信をWi-Fiに固定する。
     * インターネットのないWi-Fi＋モバイルデータのとき、Androidは送信をモバイル側へ流すことがあり、
     * PLCからの要求は届くのに返事がPLCへ届かなくなるため。
     */
    @SuppressWarnings("deprecation")
    private static void bindToWifi(boolean on) {
        bindNote = "";
        if (app == null || android.os.Build.VERSION.SDK_INT < 23) return;
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            if (!on) {
                cm.bindProcessToNetwork(null);
                boundNetwork = null;
                return;
            }
            android.net.Network wifi = null;
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    wifi = n;
                    break;
                }
            }
            if (wifi != null && cm.bindProcessToNetwork(wifi)) {
                boundNetwork = wifi;
                bindNote = "Wi-Fiで送受信";
            } else {
                cm.bindProcessToNetwork(null);
                boundNetwork = null;
                bindNote = "Wi-Fiが見つかりません（モバイルデータで送信される可能性）";
            }
        } catch (RuntimeException e) {
            bindNote = "Wi-Fi固定に失敗: " + e.getMessage();
        }
    }

    private static android.net.ConnectivityManager.NetworkCallback wifiCallback;

    /** Wi-Fiがつなぎ直されたら、新しいWi-Fiで待受をやり直す */
    private static void watchWifi(boolean on) {
        if (app == null || android.os.Build.VERSION.SDK_INT < 24) return;
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            if (!on) {
                if (wifiCallback != null) cm.unregisterNetworkCallback(wifiCallback);
                wifiCallback = null;
                return;
            }
            if (wifiCallback != null) return;
            wifiCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    main.post(() -> {
                        if (server != null && !network.equals(boundNetwork)) {
                            final int port = serverPort;
                            final String ip = serverPlcIp;
                            final SlmpServer old = server;
                            server = null;
                            starting = true;
                            new Thread(() -> {
                                old.stop();
                                main.post(() -> {
                                    starting = false;
                                    start(port, ip);
                                });
                            }, "slmp-wifi-restart").start();
                        }
                    });
                }
            };
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build();
            cm.registerNetworkCallback(req, wifiCallback);
        } catch (RuntimeException ignored) {
        }
    }

    /** 待受中はWi-Fiの省電力を抑える（受信が数百ms遅れてPLC側がタイムアウトするのを防ぐ） */
    @SuppressWarnings("deprecation")
    private static void holdWifi(boolean on) {
        try {
            if (on) {
                if (wifiLock == null && app != null) {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) app.getSystemService(android.content.Context.WIFI_SERVICE);
                    if (wm == null) return;
                    int mode = android.os.Build.VERSION.SDK_INT >= 29
                            ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "slmp-monitor");
                    wifiLock.setReferenceCounted(false);
                }
                if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            } else if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (RuntimeException ignored) {
        }
    }
    private static int restoredCount = -1;

    /** 保存しておいたスマホ→PLCの値を戻し、以後の変更を保存する。待受開始より前に呼ぶ */
    public static void init(android.content.Context c) {
        if (prefs != null) return;
        app = c.getApplicationContext();
        prefs = c.getApplicationContext().getSharedPreferences("slmp", android.content.Context.MODE_PRIVATE);
        restoredCount = mem.importLocal(prefs.getString("localValues", ""));
        mem.setOnLocalChange(() -> prefs.edit().putString("localValues", mem.exportLocal()).apply());
    }

    /** 起動時に戻した値の数（init前は -1） */
    public static int restoredCount() {
        return restoredCount;
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
        bindToWifi(true);   // ソケットを作る前に（作ったソケットがWi-Fiに固定される）
        watchWifi(true);
        final String note = bindNote;
        new Thread(() -> {
            String err = null;
            try {
                s.start();
                if (!note.isEmpty()) s.note(note);
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String error = err;
            main.post(() -> {
                starting = false;
                if (error == null) {
                    holdWifi(true);
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
        holdWifi(false);
        watchWifi(false);
        bindToWifi(false);
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
