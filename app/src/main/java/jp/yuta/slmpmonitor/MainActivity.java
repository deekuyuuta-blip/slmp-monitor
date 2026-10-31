package jp.yuta.slmpmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * スマホを「SLMP対応機器」として待ち受け、R04CPU等のシンプルCPU通信で
 * PLC→スマホ（PLCの書込み）・スマホ→PLC（PLCの読出し）を行う。
 */
public class MainActivity extends Activity implements PlcLink.Listener {

    private static final int COLOR_ON = 0xFF2E7D32;
    private static final int COLOR_GRAY = 0xFF9E9E9E;
    private static final int COLOR_ERR = 0xFFC62828;
    private static final int COLOR_VALUE = 0xFF1565C0;
    private static final int MENU_LOG = 1, MENU_SETTINGS = 2, MENU_HELP = 3;
    private static final int REQ_SETTINGS = 10;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DeviceMemory mem = PlcLink.mem;
    private ItemStore store;
    private final List<MonitorItem> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private Button startBtn;
    private TextView ipText, statusText;
    private Switch unlockSwitch;
    private LinearLayout listLayout;

    private static final class Row {
        MonitorItem item;
        TextView value;
        List<Button> buttons = new ArrayList<>();
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatusAndValues();
            main.postDelayed(this, 300);
        }
    };

    // ------------------------------------------------------------ 画面構築

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new ItemStore(getSharedPreferences("slmp", MODE_PRIVATE));
        PlcLink.init(this);
        // 待受中の通知（常駐サービス）を出すため
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        items.addAll(store.loadItems());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        // 開始ボタン・設定ボタン
        LinearLayout r1 = hRow();
        startBtn = new Button(this);
        startBtn.setText("待受開始");
        startBtn.setOnClickListener(v -> {
            if (PlcLink.isRunning()) stopServer();
            else startServer();
        });
        r1.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        Button settings = new Button(this);
        settings.setText("⚙ 設定");
        settings.setOnClickListener(v -> openSettings());
        r1.addView(settings);
        root.addView(r1);

        Button panel = new Button(this);
        panel.setText("▶ 操作画面を開く");
        panel.setOnClickListener(v -> openPanel());
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        ipText = new TextView(this);
        ipText.setTextSize(13);
        root.addView(ipText);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(0, dp(4), 0, dp(4));
        root.addView(statusText);

        // 操作ロック・追加
        LinearLayout r3 = hRow();
        unlockSwitch = new Switch(this);
        unlockSwitch.setText("操作ロック解除");
        unlockSwitch.setOnCheckedChangeListener((b, c) -> refreshButtons());
        r3.addView(unlockSwitch, new LinearLayout.LayoutParams(0, -2, 1f));
        Button add = new Button(this);
        add.setText("＋ 追加");
        add.setOnClickListener(v -> showEditDialog(null));
        r3.addView(add);
        root.addView(r3);

        ScrollView sv = new ScrollView(this);
        listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listLayout);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        rebuildRows();
        updateIpText();
        main.post(refresh);
        PlcLink.addListener(this);
        PlcLink.setHeartbeatDevice(store.heartbeat());
        if (savedInstanceState == null) {
            if (store.autoStart()) startServer();
            if (store.openPanelOnStart()) openPanel();
        }
        onServerStateChanged(null);
    }

    private void openPanel() {
        startActivity(new Intent(this, PanelActivity.class));
    }

    private void rebuildRows() {
        listLayout.removeAllViews();
        rows.clear();
        addSection("PLC → スマホ（PLCがシンプルCPU通信で書込む）", false);
        addSection("スマホ → PLC（PLCがシンプルCPU通信で読出す）", true);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("「＋ 追加」でデバイスを登録してください。行を長押しで編集・削除。");
            empty.setPadding(dp(4), dp(24), dp(4), 0);
            listLayout.addView(empty);
        }
        refreshButtons();
        updateStatusAndValues();
    }

    private void addSection(String title, boolean writable) {
        boolean any = false;
        for (MonitorItem it : items) if (it.writable == writable) any = true;
        if (!any) return;
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(12);
        h.setTextColor(COLOR_GRAY);
        h.setPadding(dp(4), dp(14), 0, dp(2));
        listLayout.addView(h);
        for (MonitorItem it : items) if (it.writable == writable) addRow(it);
    }

    private void addRow(MonitorItem it) {
        Row row = new Row();
        row.item = it;
        LinearLayout line = hRow();
        line.setPadding(dp(4), dp(10), dp(4), dp(10));
        line.setOnLongClickListener(v -> {
            showRowMenu(it);
            return true;
        });

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(it.name.isEmpty() ? it.deviceText : it.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(name);
        TextView sub = new TextView(this);
        sub.setText(it.deviceText + "  " + it.format.label);
        sub.setTextSize(12);
        sub.setTextColor(COLOR_GRAY);
        left.addView(sub);
        line.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        row.value = new TextView(this);
        row.value.setTextSize(20);
        row.value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.value.setGravity(Gravity.CENTER);
        row.value.setMinWidth(dp(84));
        row.value.setPadding(dp(8), dp(4), dp(8), dp(4));
        line.addView(row.value);

        if (it.writable) {
            if (it.format == MonitorItem.Format.BIT) {
                row.buttons.add(smallButton(line, "ON", v -> confirmBit(it, true)));
                row.buttons.add(smallButton(line, "OFF", v -> confirmBit(it, false)));
            } else {
                row.buttons.add(smallButton(line, "変更", v -> showWordDialog(it, row)));
            }
        }
        listLayout.addView(line);
        View div = new View(this);
        div.setBackgroundColor(0x22000000);
        listLayout.addView(div, new LinearLayout.LayoutParams(-1, 1));
        rows.add(row);
    }

    private Button smallButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(56));
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(-2, -2));
        return b;
    }

    private void refreshButtons() {
        boolean en = PlcLink.isRunning() && unlockSwitch.isChecked();
        for (Row r : rows) for (Button b : r.buttons) b.setEnabled(en);
    }

    // ------------------------------------------------------------ サーバ

    private void startServer() {
        startBtn.setEnabled(false);
        PlcLink.start(store.port(), store.plcIp());
    }

    private void stopServer() {
        PlcLink.stop();
    }

    @Override
    public void onServerStateChanged(String error) {
        startBtn.setEnabled(!PlcLink.isStarting());
        if (error != null) toast(error);
        boolean on = PlcLink.isRunning();
        startBtn.setText(on ? "待受停止" : "待受開始");
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            unlockSwitch.setChecked(false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        updateIpText();
        refreshButtons();
        updateStatusAndValues();
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SETTINGS || resultCode != RESULT_OK) return;
        updateIpText();
        PlcLink.setHeartbeatDevice(store.heartbeat());
        // 待受中にポートやPLC IPが変わったら、待受をやり直す
        if (PlcLink.isRunning() && (PlcLink.serverPort() != store.port() || !PlcLink.serverPlcIp().equals(store.plcIp()))) {
            startBtn.setEnabled(false);
            PlcLink.restartIfChanged(store.port(), store.plcIp());
            toast("新しい設定で待受をやり直します");
        }
    }

    private void updateStatusAndValues() {
        boolean fresh = false;
        SlmpServer server = PlcLink.server();
        if (server == null) {
            setStatus(PlcLink.isStarting() ? "待受を開始しています…" : "停止中", COLOR_GRAY);
        } else {
            long last = server.lastRequestMillis;
            long age = System.currentTimeMillis() - last;
            List<String> peers = server.clientIps();
            String who = peers.isEmpty() ? "PLC未接続" : "PLC " + android.text.TextUtils.join(", ", peers);
            if (last == 0) {
                setStatus("PLCからの接続待ち（" + who + "）", COLOR_GRAY);
            } else if (age > store.staleSec() * 1000L) {
                setStatus("PLCから通信なし " + (age / 1000) + "秒（" + who + "）", COLOR_ERR);
            } else {
                fresh = true;
                setStatus("通信中  " + who + " ／ 受信 " + server.requestCount.get() + "回", COLOR_ON);
            }
        }
        for (Row r : rows) {
            String v;
            try {
                v = r.item.read(mem);
            } catch (IllegalArgumentException e) {
                v = "ERR";
            }
            // PLC→スマホの値は、通信が途切れたら灰色にして古い値だと分かるようにする
            boolean stale = !r.item.writable && !fresh;
            showValue(r, v, stale);
        }
    }

    private void showValue(Row r, String v, boolean stale) {
        r.value.setText(v);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        if ("ON".equals(v)) {
            bg.setColor(stale ? COLOR_GRAY : COLOR_ON);
            r.value.setTextColor(Color.WHITE);
            r.value.setBackground(bg);
        } else if ("OFF".equals(v)) {
            bg.setColor(0x33000000);
            r.value.setTextColor(Color.DKGRAY);
            r.value.setBackground(bg);
        } else {
            r.value.setBackground(null);
            r.value.setTextColor(stale ? COLOR_GRAY : COLOR_VALUE);
        }
    }

    private void setStatus(String s, int color) {
        statusText.setText(s);
        statusText.setTextColor(color);
    }

    private void updateIpText() {
        String plc = store.plcIp();
        String warn = (!plc.isEmpty() && !NetUtil.sameSubnet24(plc)) ? "\n※ スマホと別のネットワークのようです" : "";
        ipText.setText("PLC: " + (plc.isEmpty() ? "未設定（接続元を制限しない）" : plc)
                + "　待受ポート: " + store.port()
                + "\nこのスマホのIP: " + NetUtil.phoneIps() + warn);
    }

    // ------------------------------------------------------------ スマホ→PLC の値を変更

    private void confirmBit(MonitorItem it, boolean on) {
        new AlertDialog.Builder(this)
                .setTitle("操作確認")
                .setMessage(label(it) + " を " + (on ? "ON" : "OFF") + " にします。\n"
                        + "PLCが次の読出し周期で取得します。実機の安全を確認してください。")
                .setPositiveButton("実行", (d, w) -> {
                    it.writeBit(mem, on);
                    updateStatusAndValues();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showWordDialog(MonitorItem it, Row row) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        if (it.format == MonitorItem.Format.HEX16) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            input.setHint("例: H00FF");
        } else if (it.format == MonitorItem.Format.FLOAT32) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        } else {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(label(it) + " の値を変更")
                .setMessage("現在値: " + row.value.getText() + "（" + it.format.label + "）\nPLCが次の読出し周期で取得します。")
                .setView(box)
                .setPositiveButton("変更", null)
                .setNegativeButton("キャンセル", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                it.writeValue(mem, input.getText().toString());
            } catch (NumberFormatException e) {
                String m = e.getMessage();
                input.setError(m != null && !m.startsWith("For input") && !m.startsWith("empty") ? m : "数値が正しくありません");
                return;
            }
            d.dismiss();
            updateStatusAndValues();
        }));
        d.show();
    }

    // ------------------------------------------------------------ 登録・編集

    private void showRowMenu(MonitorItem it) {
        new AlertDialog.Builder(this)
                .setTitle(label(it))
                .setItems(new String[]{"編集", "上へ移動", "削除"}, (d, which) -> {
                    int i = items.indexOf(it);
                    if (i < 0) return;
                    if (which == 0) {
                        showEditDialog(it);
                    } else if (which == 1 && i > 0) {
                        items.remove(i);
                        items.add(i - 1, it);
                        store.saveItems(items);
                        rebuildRows();
                    } else if (which == 2) {
                        items.remove(i);
                        store.saveItems(items);
                        rebuildRows();
                    }
                })
                .show();
    }

    private void showEditDialog(final MonitorItem existing) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText name = new EditText(this);
        name.setHint("名前（例: 送りモータ運転）");
        name.setSingleLine(true);
        f.addView(name);

        final EditText dev = new EditText(this);
        dev.setHint("スマホ側デバイス（例: D0, M100）");
        dev.setSingleLine(true);
        dev.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        f.addView(dev);

        TextView note = new TextView(this);
        note.setText("シンプルCPU通信設定の「交信先」側デバイスと同じ番号にします");
        note.setTextSize(12);
        note.setTextColor(COLOR_GRAY);
        f.addView(note);

        TextView fl = new TextView(this);
        fl.setText("ワードの表示形式（ビットデバイスは自動でON/OFF）");
        fl.setTextSize(12);
        fl.setPadding(0, dp(8), 0, 0);
        f.addView(fl);

        final MonitorItem.Format[] fmts = {
                MonitorItem.Format.S16, MonitorItem.Format.U16, MonitorItem.Format.HEX16,
                MonitorItem.Format.S32, MonitorItem.Format.FLOAT32};
        String[] labels = new String[fmts.length];
        for (int i = 0; i < fmts.length; i++) labels[i] = fmts[i].label;
        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        f.addView(sp);

        final CheckBox w = new CheckBox(this);
        w.setText("スマホ → PLC（スマホで値を変え、PLCが読出す）");
        f.addView(w);

        if (existing != null) {
            name.setText(existing.name);
            dev.setText(existing.deviceText);
            for (int i = 0; i < fmts.length; i++) if (fmts[i] == existing.format) sp.setSelection(i);
            w.setChecked(existing.writable);
        }

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "デバイスを追加" : "デバイスを編集")
                .setView(f)
                .setPositiveButton("保存", null)
                .setNegativeButton("キャンセル", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            MonitorItem n;
            try {
                n = new MonitorItem(name.getText().toString().trim(), dev.getText().toString(),
                        fmts[sp.getSelectedItemPosition()], w.isChecked());
            } catch (IllegalArgumentException e) {
                dev.setError(e.getMessage());
                return;
            }
            int i = existing == null ? -1 : items.indexOf(existing);
            if (i >= 0) items.set(i, n);
            else items.add(n);
            store.saveItems(items);
            rebuildRows();
            d.dismiss();
        }));
        d.show();
    }

    // ------------------------------------------------------------ メニュー

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_LOG, 0, "通信ログ");
        menu.add(0, MENU_SETTINGS, 1, "設定");
        menu.add(0, MENU_HELP, 2, "使い方");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == MENU_LOG) {
            showLog();
            return true;
        }
        if (id == MENU_SETTINGS) {
            openSettings();
            return true;
        }
        if (id == MENU_HELP) {
            new AlertDialog.Builder(this).setTitle("使い方")
                    .setMessage("1. スマホをPLCと同じネットワークのWi-Fiにつなぐ（IPは固定がおすすめ）\n"
                            + "2. 「⚙ 設定」でPLCのIPと待受ポートを入れ、「待受開始」\n"
                            + "3. GX Works3のシンプルCPU通信設定で、交信先を「SLMP対応機器」、IPをこのスマホ、ポートを待受ポートにする\n"
                            + "4. PLC→スマホは「書込み」、スマホ→PLCは「読出し」の交信パターンで設定\n"
                            + "5. このアプリで同じデバイス番号を登録する\n\n"
                            + "アプリは開いたままにしてください（画面は消えないようにしています）。")
                    .setPositiveButton("OK", null).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLog() {
        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        List<String> lines = PlcLink.server() != null ? PlcLink.server().logLines() : new ArrayList<>();
        Collections.reverse(lines);   // 新しい順
        t.setText(lines.isEmpty() ? "ログはありません（待受中のみ記録）" : android.text.TextUtils.join("\n", lines));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle("通信ログ（新しい順）").setView(sv)
                .setPositiveButton("閉じる", null).show();
    }

    // ------------------------------------------------------------ ライフサイクル

    @Override
    protected void onResume() {
        super.onResume();
        onServerStateChanged(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(refresh);
        PlcLink.removeListener(this);
        // アプリを閉じたら（戻るボタンで終了）待受も止める
        if (isFinishing()) PlcLink.stop();
    }

    // ------------------------------------------------------------ 小物

    private static String label(MonitorItem it) {
        return it.name.isEmpty() ? it.deviceText : it.deviceText + "（" + it.name + "）";
    }

    private LinearLayout hRow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
