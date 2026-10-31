package jp.yuta.slmpmonitor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 操作画面（GOT風）のデータ：複数の画面と、その上の部品。 */
public final class Panel {

    public static final int PORTRAIT_W = 360, PORTRAIT_H = 640;
    public static final int GRID = 10;

    public boolean landscape;
    public final List<Screen> screens = new ArrayList<>();

    public int width() { return landscape ? PORTRAIT_H : PORTRAIT_W; }
    public int height() { return landscape ? PORTRAIT_W : PORTRAIT_H; }

    // ------------------------------------------------------------ 色

    public static final String[] COLOR_NAMES = {"緑", "赤", "黄", "青", "水色", "橙", "紫", "灰", "暗い灰", "白", "黒"};
    public static final int[] COLORS = {
            0xFF2E7D32, 0xFFC62828, 0xFFF9A825, 0xFF1565C0, 0xFF0097A7, 0xFFEF6C00,
            0xFF6A1B9A, 0xFF757575, 0xFF37474F, 0xFFFFFFFF, 0xFF000000};

    public static int colorIndex(int c) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == c) return i;
        return 0;
    }

    // ------------------------------------------------------------ 画面

    public static final class Screen {
        public String name;
        public final List<Part> parts = new ArrayList<>();

        public Screen(String name) {
            this.name = name;
        }
    }

    // ------------------------------------------------------------ 部品

    public enum Type {
        LAMP("ランプ"), BUTTON("ボタン"), NUMBER("数値表示"), INPUT("数値入力"), TEXT("文字"), SWITCH("画面切替");
        public final String label;

        Type(String l) {
            label = l;
        }
    }

    public enum Action {
        MOMENTARY("モーメンタリ（押している間ON）"),
        ALTERNATE("オルタネート（押すたびON/OFF）"),
        SET("セット（ONにする）"),
        RESET("リセット（OFFにする）"),
        WORD_SET("値書込（ワードに値を入れる）");
        public final String label;

        Action(String l) {
            label = l;
        }

        public boolean bitAction() {
            return this != WORD_SET;
        }
    }

    public static final class Part {
        public Type type;
        public int x, y, w, h;
        public String label = "";
        public String device = "";
        public String lampDevice = "";
        public Action action = Action.MOMENTARY;
        public String setValue = "1";
        public int minOnMs = 500;
        public String onText = "", offText = "";
        public int onColor = COLORS[0], offColor = COLORS[8], textColor = 0xFFFFFFFF;
        public MonitorItem.Format format = MonitorItem.Format.S16;
        public int decimals = 0;
        public String unit = "";
        public String min = "", max = "";
        public boolean confirm;
        public int target;

        public Part(Type type, int x, int y, int w, int h) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public Part copy() {
            Part p = new Part(type, x, y, w, h);
            p.label = label;
            p.device = device;
            p.lampDevice = lampDevice;
            p.action = action;
            p.setValue = setValue;
            p.minOnMs = minOnMs;
            p.onText = onText;
            p.offText = offText;
            p.onColor = onColor;
            p.offColor = offColor;
            p.textColor = textColor;
            p.format = format;
            p.decimals = decimals;
            p.unit = unit;
            p.min = min;
            p.max = max;
            p.confirm = confirm;
            p.target = target;
            return p;
        }

        public boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        public boolean operable() {
            return type == Type.BUTTON || type == Type.INPUT;
        }

        // ---- 値の読み書き（スマホ内メモリ） ----

        /** ランプ・ボタンの点灯状態 */
        public boolean isOn(DeviceMemory m) {
            try {
                if (type == Type.BUTTON && action == Action.WORD_SET && lampDevice.isEmpty()) {
                    Double cur = readNumber(m, device);
                    Double want = parseNumber(setValue);
                    return cur != null && want != null && Math.abs(cur - want) < 1e-9;
                }
                String dev = (type == Type.BUTTON && !lampDevice.isEmpty()) ? lampDevice : device;
                if (dev.isEmpty()) return false;
                Device d = Device.parse(dev);
                if (d.bit) return m.readBits(d.code, d.number, 1)[0];
                return m.readWords(d.code, d.number, 1)[0] != 0;
            } catch (RuntimeException e) {
                return false;
            }
        }

        public void writeBit(DeviceMemory m, boolean on) {
            Device d = Device.parse(device);
            m.localWriteBits(d.code, d.number, new boolean[]{on});
        }

        /** モーメンタリ用（保存しない：再起動でONのまま戻るのを防ぐ） */
        public void writeBitTemp(DeviceMemory m, boolean on) {
            Device d = Device.parse(device);
            m.tempWriteBits(d.code, d.number, new boolean[]{on});
        }

        public boolean readBit(DeviceMemory m) {
            Device d = Device.parse(device);
            return m.readBits(d.code, d.number, 1)[0];
        }

        /** 表示単位（小数点位置を反映）の値。読めなければ null */
        public Double readNumber(DeviceMemory m, String dev) {
            try {
                Device d = Device.parse(dev);
                if (d.bit) return m.readBits(d.code, d.number, 1)[0] ? 1.0 : 0.0;
                short[] w = m.readWords(d.code, d.number, format.words());
                double raw;
                switch (format) {
                    case U16:
                    case HEX16:
                        raw = w[0] & 0xFFFF;
                        break;
                    case S32:
                        raw = (w[0] & 0xFFFF) | ((w[1] & 0xFFFF) << 16);
                        break;
                    case FLOAT32:
                        return (double) Float.intBitsToFloat((w[0] & 0xFFFF) | ((w[1] & 0xFFFF) << 16));
                    default:
                        raw = w[0];
                }
                return format == MonitorItem.Format.HEX16 ? raw : raw / Math.pow(10, decimals);
            } catch (RuntimeException e) {
                return null;
            }
        }

        public String displayValue(DeviceMemory m) {
            Double v = readNumber(m, device);
            if (v == null) return "----";
            if (format == MonitorItem.Format.HEX16) return String.format(Locale.ROOT, "%04X", v.intValue() & 0xFFFF);
            return String.format(Locale.ROOT, "%." + decimals + "f", v);
        }

        /** 入力文字列を表示単位の数値に（HEXは16進） */
        public Double parseNumber(String s) {
            try {
                String t = s.trim();
                if (format == MonitorItem.Format.HEX16) {
                    t = t.toUpperCase(Locale.ROOT);
                    if (t.startsWith("H")) t = t.substring(1);
                    if (t.startsWith("0X")) t = t.substring(2);
                    return (double) Integer.parseInt(t, 16);
                }
                return Double.parseDouble(t);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** 入力値の検査。問題なければ null、あればメッセージ */
        public String checkInput(String s) {
            Double v = parseNumber(s);
            if (v == null || v.isNaN() || v.isInfinite()) return "数値が正しくありません";
            Double lo = min.isEmpty() ? null : parseNumber(min);
            Double hi = max.isEmpty() ? null : parseNumber(max);
            if (lo != null && v < lo - 1e-9) return "下限 " + min + " より小さい値です";
            if (hi != null && v > hi + 1e-9) return "上限 " + max + " より大きい値です";
            if (format == MonitorItem.Format.FLOAT32) return null;
            double raw = format == MonitorItem.Format.HEX16 ? v : Math.round(v * Math.pow(10, decimals));
            switch (format) {
                case U16:
                case HEX16:
                    if (raw < 0 || raw > 65535) return "0〜" + scaledLimit(65535) + " の範囲で入力してください";
                    break;
                case S32:
                    if (raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) return "32bitの範囲外です";
                    break;
                default:
                    if (raw < -32768 || raw > 32767)
                        return scaledLimit(-32768) + "〜" + scaledLimit(32767) + " の範囲で入力してください";
            }
            return null;
        }

        private String scaledLimit(int raw) {
            if (decimals == 0 || format == MonitorItem.Format.HEX16) return Integer.toString(raw);
            return String.format(Locale.ROOT, "%." + decimals + "f", raw / Math.pow(10, decimals));
        }

        /** checkInput 済みの値を書込む */
        public void writeNumber(DeviceMemory m, String s) {
            Device d = Device.parse(device);
            double v = parseNumber(s);
            short[] w;
            switch (format) {
                case FLOAT32: {
                    int b = Float.floatToIntBits((float) v);
                    w = new short[]{(short) b, (short) (b >>> 16)};
                    break;
                }
                case S32: {
                    int r = (int) Math.round(v * Math.pow(10, decimals));
                    w = new short[]{(short) r, (short) (r >>> 16)};
                    break;
                }
                case HEX16:
                    w = new short[]{(short) (int) v};
                    break;
                default:
                    w = new short[]{(short) Math.round(v * Math.pow(10, decimals))};
            }
            m.localWriteWords(d.code, d.number, w);
        }

        /** 設定の検査。問題なければ null */
        public String validate(int screenCount) {
            try {
                switch (type) {
                    case LAMP:
                        if (device.isEmpty()) return "監視デバイスを入力してください";
                        Device.parse(device);
                        break;
                    case BUTTON: {
                        if (device.isEmpty()) return "書込デバイスを入力してください";
                        Device d = Device.parse(device);
                        if (action.bitAction() && !d.bit) return "この動作にはビットデバイス（M、Yなど）を指定してください";
                        if (!action.bitAction()) {
                            if (d.bit) return "値書込にはワードデバイス（Dなど）を指定してください";
                            String e = checkInput(setValue);
                            if (e != null) return "書込値: " + e;
                        }
                        if (d.writeBlockedReason() != null) return d.writeBlockedReason();
                        if (!lampDevice.isEmpty()) Device.parse(lampDevice);
                        break;
                    }
                    case NUMBER:
                    case INPUT: {
                        if (device.isEmpty()) return "デバイスを入力してください";
                        Device nd = Device.parse(device);
                        if (nd.bit) return "ワードデバイス（D、Wなど）を指定してください";
                        if (type == Type.INPUT && nd.writeBlockedReason() != null) return nd.writeBlockedReason();
                        if (!min.isEmpty() && parseNumber(min) == null) return "下限が数値ではありません";
                        if (!max.isEmpty() && parseNumber(max) == null) return "上限が数値ではありません";
                        break;
                    }
                    case SWITCH:
                        if (target < 0 || target >= screenCount) return "切替先の画面を選んでください";
                        break;
                    default:
                }
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            return null;
        }

        /** 編集画面で部品の下に出すデバイス表示 */
        public String deviceSummary() {
            switch (type) {
                case BUTTON:
                    return device + (lampDevice.isEmpty() ? "" : " / ランプ " + lampDevice);
                case SWITCH:
                    return "→ 画面" + (target + 1);
                case TEXT:
                    return "";
                default:
                    return device;
            }
        }
    }

    // ------------------------------------------------------------ 初期画面

    public static Panel createDefault() {
        Panel p = new Panel();
        Screen s = new Screen("メイン");
        Part t = new Part(Type.TEXT, 20, 10, 320, 50);
        t.label = "操作パネル";
        s.parts.add(t);

        Part l1 = new Part(Type.LAMP, 20, 70, 150, 60);
        l1.label = "運転中";
        l1.device = "M0";
        l1.onColor = COLORS[0];
        s.parts.add(l1);
        Part l2 = new Part(Type.LAMP, 190, 70, 150, 60);
        l2.label = "異常";
        l2.device = "M1";
        l2.onColor = COLORS[1];
        s.parts.add(l2);

        Part b1 = new Part(Type.BUTTON, 20, 150, 150, 90);
        b1.label = "運転";
        b1.device = "M100";
        b1.lampDevice = "M0";
        b1.onColor = COLORS[0];
        s.parts.add(b1);
        Part b2 = new Part(Type.BUTTON, 190, 150, 150, 90);
        b2.label = "停止";
        b2.device = "M101";
        b2.onColor = COLORS[1];
        b2.offColor = COLORS[8];
        s.parts.add(b2);

        Part n = new Part(Type.NUMBER, 20, 260, 320, 80);
        n.label = "現在値";
        n.device = "D0";
        n.decimals = 1;
        n.unit = "m/min";
        s.parts.add(n);
        Part in = new Part(Type.INPUT, 20, 360, 320, 80);
        in.label = "設定値";
        in.device = "D100";
        in.decimals = 1;
        in.unit = "m/min";
        in.min = "0";
        in.max = "100";
        s.parts.add(in);

        Part sw = new Part(Type.SWITCH, 20, 560, 320, 60);
        sw.label = "画面2へ";
        sw.target = 1;
        sw.onColor = COLORS[3];
        sw.offColor = COLORS[3];
        s.parts.add(sw);
        p.screens.add(s);

        Screen s2 = new Screen("画面2");
        Part t2 = new Part(Type.TEXT, 20, 10, 320, 50);
        t2.label = "画面2";
        s2.parts.add(t2);
        Part back = new Part(Type.SWITCH, 20, 560, 320, 60);
        back.label = "メインへ戻る";
        back.target = 0;
        back.onColor = COLORS[3];
        back.offColor = COLORS[3];
        s2.parts.add(back);
        p.screens.add(s2);
        return p;
    }

    // ------------------------------------------------------------ 縦横切替（位置を比率で移す）

    public void setLandscape(boolean l) {
        if (l == landscape) return;
        float sx = (float) (l ? PORTRAIT_H : PORTRAIT_W) / width();
        float sy = (float) (l ? PORTRAIT_W : PORTRAIT_H) / height();
        landscape = l;
        for (Screen s : screens) {
            for (Part p : s.parts) {
                p.x = snap(p.x * sx);
                p.y = snap(p.y * sy);
                p.w = Math.max(GRID * 2, snap(p.w * sx));
                p.h = Math.max(GRID * 2, snap(p.h * sy));
                clamp(p);
            }
        }
    }

    public static int snap(float v) {
        return Math.round(v / GRID) * GRID;
    }

    public void clamp(Part p) {
        p.w = Math.min(Math.max(p.w, GRID * 2), width());
        p.h = Math.min(Math.max(p.h, GRID * 2), height());
        p.x = Math.min(Math.max(p.x, 0), width() - p.w);
        p.y = Math.min(Math.max(p.y, 0), height() - p.h);
    }

    // ------------------------------------------------------------ JSON

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("landscape", landscape);
            JSONArray sa = new JSONArray();
            for (Screen s : screens) {
                JSONObject so = new JSONObject();
                so.put("name", s.name);
                JSONArray pa = new JSONArray();
                for (Part p : s.parts) pa.put(partJson(p));
                so.put("parts", pa);
                sa.put(so);
            }
            o.put("screens", sa);
            return o.toString(1);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject partJson(Part p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", p.type.name());
        o.put("x", p.x);
        o.put("y", p.y);
        o.put("w", p.w);
        o.put("h", p.h);
        o.put("label", p.label);
        o.put("device", p.device);
        o.put("lampDevice", p.lampDevice);
        o.put("action", p.action.name());
        o.put("setValue", p.setValue);
        o.put("minOnMs", p.minOnMs);
        o.put("onText", p.onText);
        o.put("offText", p.offText);
        o.put("onColor", String.format(Locale.ROOT, "#%08X", p.onColor));
        o.put("offColor", String.format(Locale.ROOT, "#%08X", p.offColor));
        o.put("textColor", String.format(Locale.ROOT, "#%08X", p.textColor));
        o.put("format", p.format.name());
        o.put("decimals", p.decimals);
        o.put("unit", p.unit);
        o.put("min", p.min);
        o.put("max", p.max);
        o.put("confirm", p.confirm);
        o.put("target", p.target);
        return o;
    }

    /** 不正な部分は読み飛ばす。全体が読めなければ例外 */
    public static Panel fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Panel panel = new Panel();
        panel.landscape = o.optBoolean("landscape", false);
        JSONArray sa = o.getJSONArray("screens");
        for (int i = 0; i < sa.length(); i++) {
            JSONObject so = sa.getJSONObject(i);
            Screen s = new Screen(so.optString("name", "画面" + (i + 1)));
            JSONArray pa = so.optJSONArray("parts");
            if (pa != null) {
                for (int j = 0; j < pa.length(); j++) {
                    try {
                        Part p = partFrom(pa.getJSONObject(j));
                        panel.clamp(p);
                        s.parts.add(p);
                    } catch (RuntimeException | JSONException ignored) {
                    }
                }
            }
            panel.screens.add(s);
        }
        if (panel.screens.isEmpty()) panel.screens.add(new Screen("メイン"));
        for (Screen s : panel.screens) {
            for (Part p : s.parts) {
                if (p.target >= panel.screens.size()) p.target = 0;
            }
        }
        return panel;
    }

    private static Part partFrom(JSONObject o) throws JSONException {
        Part p = new Part(Type.valueOf(o.getString("type")),
                o.optInt("x", 0), o.optInt("y", 0), o.optInt("w", 100), o.optInt("h", 60));
        p.label = o.optString("label", "");
        p.device = o.optString("device", "").toUpperCase(Locale.ROOT);
        p.lampDevice = o.optString("lampDevice", "").toUpperCase(Locale.ROOT);
        p.action = Action.valueOf(o.optString("action", "MOMENTARY"));
        p.setValue = o.optString("setValue", "1");
        p.minOnMs = Math.max(0, Math.min(5000, o.optInt("minOnMs", 500)));
        p.onText = o.optString("onText", "");
        p.offText = o.optString("offText", "");
        p.onColor = parseColor(o.optString("onColor", ""), COLORS[0]);
        p.offColor = parseColor(o.optString("offColor", ""), COLORS[8]);
        p.textColor = parseColor(o.optString("textColor", ""), 0xFFFFFFFF);
        p.format = MonitorItem.Format.valueOf(o.optString("format", "S16"));
        if (p.format == MonitorItem.Format.BIT) p.format = MonitorItem.Format.S16;
        p.decimals = Math.max(0, Math.min(6, o.optInt("decimals", 0)));
        p.unit = o.optString("unit", "");
        p.min = o.optString("min", "");
        p.max = o.optString("max", "");
        p.confirm = o.optBoolean("confirm", false);
        p.target = Math.max(0, o.optInt("target", 0));
        return p;
    }

    private static int parseColor(String s, int def) {
        try {
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            if (t.length() == 6) t = "FF" + t;
            if (t.length() != 8) return def;
            return (int) Long.parseLong(t, 16);
        } catch (RuntimeException e) {
            return def;
        }
    }
}
