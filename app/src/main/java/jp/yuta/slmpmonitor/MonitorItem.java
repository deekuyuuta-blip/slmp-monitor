package jp.yuta.slmpmonitor;

import java.util.Locale;

/** モニタ一覧の1行分の設定。 */
public final class MonitorItem {

    public enum Format {
        BIT("ビット"),
        S16("符号付き16bit"),
        U16("符号なし16bit"),
        HEX16("16進"),
        S32("符号付き32bit"),
        FLOAT32("実数(単精度)");

        public final String label;

        Format(String label) {
            this.label = label;
        }

        public int words() {
            return (this == S32 || this == FLOAT32) ? 2 : 1;
        }
    }

    public String name;
    public String deviceText;
    public Format format;
    public boolean writable;

    public MonitorItem(String name, String deviceText, Format format, boolean writable) {
        Device d = Device.parse(deviceText);   // 不正なら例外
        this.name = name;
        this.deviceText = d.toString();
        this.format = d.bit ? Format.BIT : (format == Format.BIT ? Format.S16 : format);
        this.writable = writable;
    }

    public Device device() {
        return Device.parse(deviceText);
    }

    /** スマホ内メモリの値を表示文字列にする */
    public String read(DeviceMemory m) {
        Device d = device();
        if (format == Format.BIT) {
            return m.readBits(d.code, d.number, 1)[0] ? "ON" : "OFF";
        }
        return formatWords(m.readWords(d.code, d.number, format.words()));
    }

    String formatWords(short[] w) {
        switch (format) {
            case U16:
                return Integer.toString(w[0] & 0xFFFF);
            case HEX16:
                return String.format(Locale.ROOT, "H%04X", w[0] & 0xFFFF);
            case S32:
                return Integer.toString(toInt(w));
            case FLOAT32: {
                float f = Float.intBitsToFloat(toInt(w));
                return String.format(Locale.ROOT, "%.3f", f);
            }
            default:
                return Short.toString(w[0]);
        }
    }

    /** 入力文字列をPLCへ書込むワード列に変換 */
    short[] parseWords(String s) {
        String t = s.trim();
        switch (format) {
            case U16: {
                int v = Integer.parseInt(t);
                if (v < 0 || v > 65535) throw new NumberFormatException("0〜65535で入力してください");
                return new short[]{(short) v};
            }
            case HEX16: {
                String h = t.toUpperCase(Locale.ROOT);
                if (h.startsWith("H")) h = h.substring(1);
                else if (h.startsWith("0X")) h = h.substring(2);
                int v = Integer.parseInt(h, 16);
                if (v < 0 || v > 0xFFFF) throw new NumberFormatException("0〜FFFFで入力してください");
                return new short[]{(short) v};
            }
            case S32:
                return fromInt(Integer.parseInt(t));
            case FLOAT32:
                return fromInt(Float.floatToIntBits(Float.parseFloat(t)));
            default: {
                int v = Integer.parseInt(t);
                if (v < -32768 || v > 32767) throw new NumberFormatException("-32768〜32767で入力してください");
                return new short[]{(short) v};
            }
        }
    }

    /** スマホ内メモリに値をセット（PLCが次の読出しで取りに来る） */
    public void writeValue(DeviceMemory m, String input) {
        Device d = device();
        m.writeWords(d.code, d.number, parseWords(input));
    }

    public void writeBit(DeviceMemory m, boolean on) {
        Device d = device();
        m.writeBits(d.code, d.number, new boolean[]{on});
    }

    // 32bitは下位ワードが先（三菱の連続2ワード）
    private static int toInt(short[] w) {
        return (w[0] & 0xFFFF) | ((w[1] & 0xFFFF) << 16);
    }

    private static short[] fromInt(int v) {
        return new short[]{(short) (v & 0xFFFF), (short) ((v >>> 16) & 0xFFFF)};
    }
}
