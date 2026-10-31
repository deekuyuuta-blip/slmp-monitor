package jp.yuta.slmpmonitor;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 設定画面：PLCのIP・ポート、読出し周期、ハートビート、通信なし判定、自動開始 */
public class SettingsActivity extends Activity {

    private static final int COLOR_NOTE = 0xFF757575;
    private static final int COLOR_WARN = 0xFFE65100;

    private ItemStore store;
    private EditText plcIp, heartbeat, staleSec, plcPort, pollMs;
    private android.widget.RadioButton protoUdp, protoTcp;
    private CheckBox usePw, savePw;
    private EditText pwField;
    private CheckBox autoStart, openPanel;
    private TextView subnetWarn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("設定");
        store = new ItemStore(getSharedPreferences("slmp", MODE_PRIVATE));
        PlcLink.init(this);

        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(16), dp(8), dp(16), dp(16));

        section(f, "PLC");
        plcIp = field(f, "PLCのIPアドレス", "例: 192.168.3.39",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                "PLC（内蔵Ethernet）のIPアドレス。スマホからこのIPへ読み書きしに行きます。");
        plcIp.setText(store.plcIp());
        subnetWarn = new TextView(this);
        subnetWarn.setTextSize(12);
        subnetWarn.setTextColor(COLOR_WARN);
        f.addView(subnetWarn);
        plcIp.setOnFocusChangeListener((v, has) -> {
            if (!has) checkSubnet();
        });

        section(f, "PLCとの通信（SLMP）");
        plcPort = field(f, "PLCのSLMPポート番号", "例: 5010", InputType.TYPE_CLASS_NUMBER,
                "GX Works3「Ethernetポート → 基本設定 → 相手機器接続構成設定」で追加したSLMP接続機器のポート番号（交信データコードはバイナリ）。");
        plcPort.setText(String.valueOf(store.plcPort()));
        android.widget.RadioGroup pg = new android.widget.RadioGroup(this);
        pg.setOrientation(LinearLayout.HORIZONTAL);
        protoUdp = new android.widget.RadioButton(this);
        protoUdp.setId(android.view.View.generateViewId());
        protoUdp.setText("UDP（おすすめ）");
        protoTcp = new android.widget.RadioButton(this);
        protoTcp.setId(android.view.View.generateViewId());
        protoTcp.setText("TCP");
        pg.addView(protoUdp);
        pg.addView(protoTcp);
        f.addView(pg);
        (store.plcUdp() ? protoUdp : protoTcp).setChecked(true);
        pollMs = field(f, "読出し周期（ms）", "例: 200", InputType.TYPE_CLASS_NUMBER,
                "この間隔で、一覧と操作画面のデバイスをPLCから読みます（50〜5000）。応答がなければ0.3秒で送り直します。");
        pollMs.setText(String.valueOf(store.pollMs()));

        TextView phone = new TextView(this);
        phone.setText("このスマホのIP: " + NetUtil.phoneIps());
        phone.setTextSize(13);
        phone.setPadding(0, dp(4), 0, dp(4));
        f.addView(phone);

        section(f, "リモートパスワード");
        usePw = new CheckBox(this);
        usePw.setText("リモートパスワードを使う");
        usePw.setChecked(store.usePassword());
        f.addView(usePw);
        TextView pwNote = new TextView(this);
        pwNote.setTextSize(12);
        pwNote.setTextColor(COLOR_NOTE);
        pwNote.setText("GX Works3の「リモートパスワード」で、SLMP接続機器の接続に設定したパスワードです。"
                + "通信開始のたびに入力します。間違えた場合は、PLCがロックアウトしないよう、それ以上は送りません。");
        f.addView(pwNote);
        savePw = new CheckBox(this);
        savePw.setText("このスマホに保存する（毎回の入力を省く。スマホを持った人は誰でも操作できます）");
        savePw.setChecked(!store.savedPassword().isEmpty());
        f.addView(savePw);
        pwField = new EditText(this);
        pwField.setSingleLine(true);
        pwField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwField.setHint(store.savedPassword().isEmpty() ? "保存するパスワード" : "保存済み（変えるときだけ入力）");
        f.addView(pwField);
        Runnable vis = () -> {
            savePw.setEnabled(usePw.isChecked());
            pwField.setVisibility(usePw.isChecked() && savePw.isChecked() ? android.view.View.VISIBLE : android.view.View.GONE);
        };
        usePw.setOnCheckedChangeListener((b, c) -> vis.run());
        savePw.setOnCheckedChangeListener((b, c) -> vis.run());
        vis.run();

        section(f, "安全・監視");
        heartbeat = field(f, "ハートビート デバイス", "例: D199（空欄でなし）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
                "1秒ごとにPLCのこのデバイスへ+1を書込みます（ビットなら反転）。ラダーで値が変わらなくなったら、スマホ停止とみなしてください（例: D2019）。");
        heartbeat.setText(store.heartbeat());
        staleSec = field(f, "通信なし判定（秒）", "例: 3", InputType.TYPE_CLASS_NUMBER,
                "この秒数PLCとやり取りできなければ「通信なし」と表示し、受信値を灰色にします。");
        staleSec.setText(String.valueOf(store.staleSec()));

        section(f, "起動");
        autoStart = new CheckBox(this);
        autoStart.setText("アプリ起動時に自動で通信を開始");
        autoStart.setChecked(store.autoStart());
        f.addView(autoStart);
        openPanel = new CheckBox(this);
        openPanel.setText("アプリ起動時に操作画面を開く");
        openPanel.setChecked(store.openPanelOnStart());
        f.addView(openPanel);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);
        Button cancel = new Button(this);
        cancel.setText("キャンセル");
        cancel.setOnClickListener(v -> finish());
        btns.addView(cancel);
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> save());
        btns.addView(save);
        f.addView(btns);

        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        setContentView(sv);
        checkSubnet();
    }

    private void checkSubnet() {
        String ip = plcIp.getText().toString().trim();
        if (NetUtil.isIpv4(ip) && !NetUtil.sameSubnet24(ip)) {
            subnetWarn.setText("※ スマホと別のネットワークのようです。Wi-FiのIP（上3桁）をPLCと合わせてください。");
        } else {
            subnetWarn.setText("");
        }
    }

    private void save() {
        String ip = plcIp.getText().toString().trim();
        if (!ip.isEmpty() && !NetUtil.isIpv4(ip)) {
            plcIp.setError("IPアドレスの形式が正しくありません（例: 192.168.3.39）");
            plcIp.requestFocus();
            return;
        }
        String hb = heartbeat.getText().toString().trim();
        if (!hb.isEmpty()) {
            try {
                hb = Device.parse(hb).toString();
            } catch (IllegalArgumentException e) {
                heartbeat.setError(e.getMessage());
                heartbeat.requestFocus();
                return;
            }
        }
        int st;
        try {
            st = Integer.parseInt(staleSec.getText().toString().trim());
            if (st < 1 || st > 60) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            staleSec.setError("1〜60秒で入力してください");
            staleSec.requestFocus();
            return;
        }
        int pp;
        try {
            pp = Integer.parseInt(plcPort.getText().toString().trim());
            if (pp < 1 || pp > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plcPort.setError("1〜65535で入力してください");
            plcPort.requestFocus();
            return;
        }
        int pm;
        try {
            pm = Integer.parseInt(pollMs.getText().toString().trim());
            if (pm < 50 || pm > 5000) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            pollMs.setError("50〜5000で入力してください");
            pollMs.requestFocus();
            return;
        }
        if (!NetUtil.isIpv4(ip)) {
            plcIp.setError("PLCのIPアドレスを入れてください（例: 192.168.1.1）");
            plcIp.requestFocus();
            return;
        }
        String newPw = pwField.getText().toString();
        if (usePw.isChecked() && savePw.isChecked()) {
            if (newPw.isEmpty() && store.savedPassword().isEmpty()) {
                pwField.setError("保存するパスワードを入力してください");
                pwField.requestFocus();
                return;
            }
            if (!newPw.isEmpty() && (newPw.length() < 4 || newPw.length() > 32 || !newPw.matches("[\\x21-\\x7E]+"))) {
                pwField.setError("半角英数字・記号で入力してください");
                pwField.requestFocus();
                return;
            }
            if (!newPw.isEmpty()) store.saveSavedPassword(newPw);
        } else {
            store.saveSavedPassword("");
        }
        boolean pwChanged = store.usePassword() != usePw.isChecked() || !newPw.isEmpty();
        store.saveUsePassword(usePw.isChecked());
        if (pwChanged) PlcLink.setSessionPassword(null);
        store.savePlcPort(pp);
        store.savePlcUdp(protoUdp.isChecked());
        store.savePollMs(pm);
        store.savePlcIp(ip);
        store.saveHeartbeat(hb);
        store.saveStaleSec(st);
        store.saveAutoStart(autoStart.isChecked());
        store.saveOpenPanelOnStart(openPanel.isChecked());
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    // ---- 部品 ----

    private void section(LinearLayout f, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF1565C0);
        t.setPadding(0, dp(18), 0, dp(2));
        f.addView(t);
    }

    private EditText field(LinearLayout f, String label, String hint, int inputType, String note) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(15);
        l.setPadding(0, dp(8), 0, 0);
        f.addView(l);
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(inputType);
        f.addView(e);
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextSize(12);
        n.setTextColor(COLOR_NOTE);
        f.addView(n);
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
