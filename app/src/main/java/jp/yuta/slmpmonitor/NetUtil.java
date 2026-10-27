package jp.yuta.slmpmonitor;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

final class NetUtil {
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private NetUtil() {
    }

    static boolean isIpv4(String s) {
        return IPV4.matcher(s.trim()).matches();
    }

    /** このスマホのIPv4アドレス（Wi-Fiには目印付き） */
    static String phoneIps() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        ips.add(a.getHostAddress() + (ni.getName().startsWith("wlan") ? "（Wi-Fi）" : ""));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (ips.isEmpty()) return "取得できません";
        StringBuilder b = new StringBuilder();
        for (String s : ips) {
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.toString();
    }

    /** 同じネットワーク（/24）にいるかの簡易判定。判定できなければ true */
    static boolean sameSubnet24(String plcIp) {
        if (!isIpv4(plcIp)) return true;
        String prefix = plcIp.substring(0, plcIp.lastIndexOf('.') + 1);
        String phone = phoneIps();
        return phone.startsWith("取得") || phone.contains(prefix);
    }
}
