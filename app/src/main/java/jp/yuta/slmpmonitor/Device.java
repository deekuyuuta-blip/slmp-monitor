package jp.yuta.slmpmonitor;

import java.util.Locale;

/** PLCデバイス（例: D100, M0, X1F）。SLMP 3E/Q互換のデバイスコードを使用。 */
public final class Device {
    public final String prefix;
    public final int code;
    public final int number;
    public final boolean bit;
    public final boolean hexNumbered;

    private Device(String prefix, int code, int number, boolean bit, boolean hexNumbered) {
        this.prefix = prefix;
        this.code = code;
        this.number = number;
        this.bit = bit;
        this.hexNumbered = hexNumbered;
    }

    // prefix, デバイスコード, ビットデバイスか, 16進番号か（長い名前を先に判定）
    private static final Object[][] TABLE = {
            {"SM", 0x91, true, false},
            {"SD", 0xA9, false, false},
            {"SB", 0xA1, true, true},
            {"SW", 0xB5, false, true},
            {"ZR", 0xB0, false, false},
            {"TS", 0xC1, true, false},
            {"TC", 0xC0, true, false},
            {"TN", 0xC2, false, false},
            {"CS", 0xC4, true, false},
            {"CC", 0xC3, true, false},
            {"CN", 0xC5, false, false},
            {"X", 0x9C, true, true},
            {"Y", 0x9D, true, true},
            {"M", 0x90, true, false},
            {"L", 0x92, true, false},
            {"F", 0x93, true, false},
            {"B", 0xA0, true, true},
            {"D", 0xA8, false, false},
            {"W", 0xB4, false, true},
            {"R", 0xAF, false, false},
    };

    /** デバイスコードがビットデバイスなら true、ワードなら false、未知なら null */
    public static Boolean isBitCode(int code) {
        for (Object[] row : TABLE) if ((Integer) row[1] == code) return (Boolean) row[2];
        return null;
    }

    /** デバイスコードから表示名（ログ用） */
    public static String name(int code, int number) {
        for (Object[] row : TABLE) {
            if ((Integer) row[1] == code) {
                return row[0] + ((Boolean) row[3] ? Integer.toHexString(number).toUpperCase(Locale.ROOT) : Integer.toString(number));
            }
        }
        return String.format(Locale.ROOT, "code%02X:%d", code, number);
    }

    public static Device parse(String text) {
        String t = text.trim().toUpperCase(Locale.ROOT);
        for (Object[] row : TABLE) {
            String p = (String) row[0];
            if (!t.startsWith(p) || t.length() == p.length()) continue;
            boolean hex = (Boolean) row[3];
            int n;
            try {
                n = Integer.parseInt(t.substring(p.length()), hex ? 16 : 10);
            } catch (NumberFormatException e) {
                continue;
            }
            if (n < 0 || n > 0xFFFFFF) break;
            return new Device(p, (Integer) row[1], n, (Boolean) row[2], hex);
        }
        throw new IllegalArgumentException("デバイス名が正しくありません: " + text);
    }

    @Override
    public String toString() {
        return prefix + (hexNumbered ? Integer.toHexString(number).toUpperCase(Locale.ROOT) : Integer.toString(number));
    }
}
