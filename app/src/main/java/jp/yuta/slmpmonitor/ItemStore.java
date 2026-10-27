package jp.yuta.slmpmonitor;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 設定とモニタ一覧の保存。 */
final class ItemStore {
    private final SharedPreferences prefs;

    ItemStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    int port() { return prefs.getInt("port", 5000); }
    void savePort(int port) { prefs.edit().putInt("port", port).apply(); }

    /** PLCのIPアドレス。空文字なら接続元を制限しない */
    String plcIp() { return prefs.getString("plcIp", ""); }
    void savePlcIp(String ip) { prefs.edit().putString("plcIp", ip).apply(); }

    /** この秒数PLCから要求がなければ「通信なし」 */
    int staleSec() { return prefs.getInt("staleSec", 3); }
    void saveStaleSec(int s) { prefs.edit().putInt("staleSec", s).apply(); }

    /** 空文字ならハートビートなし */
    String heartbeat() { return prefs.getString("heartbeat", ""); }
    void saveHeartbeat(String dev) { prefs.edit().putString("heartbeat", dev).apply(); }

    boolean autoStart() { return prefs.getInt("autoStart", 0) == 1; }
    void saveAutoStart(boolean on) { prefs.edit().putInt("autoStart", on ? 1 : 0).apply(); }

    List<MonitorItem> loadItems() {
        List<MonitorItem> list = new ArrayList<>();
        String json = prefs.getString("items", null);
        if (json == null) {
            list.add(new MonitorItem("PLCから受信 例", "D0", MonitorItem.Format.S16, false));
            list.add(new MonitorItem("PLCへ送る値 例", "D100", MonitorItem.Format.S16, true));
            list.add(new MonitorItem("PLCへ送るビット 例", "M100", MonitorItem.Format.BIT, true));
            return list;
        }
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                try {
                    list.add(new MonitorItem(o.optString("name"), o.getString("dev"),
                            MonitorItem.Format.valueOf(o.optString("fmt", "S16")), o.optBoolean("w")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    void saveItems(List<MonitorItem> items) {
        JSONArray a = new JSONArray();
        try {
            for (MonitorItem it : items) {
                JSONObject o = new JSONObject();
                o.put("name", it.name);
                o.put("dev", it.deviceText);
                o.put("fmt", it.format.name());
                o.put("w", it.writable);
                a.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("items", a.toString()).apply();
    }
}
