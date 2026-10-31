package jp.yuta.slmpmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** GOT風の操作画面（運転モード／編集モード） */
public class PanelActivity extends Activity implements PanelView.Host, PlcLink.Listener {

    private static final long AUTO_LOCK_MS = 120_000;
    private static final int M_EDIT = 1, M_SCREENS = 2, M_ORIENT = 3, M_EXPORT = 4, M_IMPORT = 5, M_LIST = 6;
    private static final int C_OK = 0xFF2E7D32, C_ERR = 0xFFC62828, C_GRAY = 0xFF90A4AE;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ItemStore store;
    private Panel panel;
    private int screenIndex;
    private boolean editMode, unlocked;
    private long lastOperated;
    private Toast lastToast;

    private PanelView view;
    private LinearLayout runBar, editBar, tabs;
    private TextView status;
    private Button lockBtn, propBtn, dupBtn, delBtn, frontBtn;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            view.invalidate();
            main.postDelayed(this, 200);
        }
    };

    // ------------------------------------------------------------ 起動

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("slmp", MODE_PRIVATE);
        store = new ItemStore(prefs);
        PlcLink.init(this);
        panel = loadPanel();
        screenIndex = Math.min(prefs.getInt("panelScreen", 0), panel.screens.size() - 1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101316);

        // 運転モードのバー
        runBar = bar();
        status = new TextView(this);
        status.setTextSize(13);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(dp(10), 0, dp(4), 0);
        status.setOnClickListener(v -> {
            if (!PlcLink.isRunning() && !PlcLink.isStarting()) PlcLink.startWithPassword(this, store.port(), store.plcIp());
        });
        runBar.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        lockBtn = new Button(this);
        lockBtn.setOnClickListener(v -> setUnlocked(!unlocked));
        runBar.addView(lockBtn);
        Button more = new Button(this);
        more.setText("⋮");
        more.setMinWidth(0);
        more.setMinimumWidth(dp(44));
        more.setOnClickListener(v -> showMenu());
        runBar.addView(more, new LinearLayout.LayoutParams(dp(48), -2));
        root.addView(runBar);

        // 編集モードのバー
        editBar = bar();
        barButton(editBar, "＋部品", v -> addPart());
        propBtn = barButton(editBar, "設定", v -> {
            if (view.selected() != null) editPart(view.selected());
        });
        dupBtn = barButton(editBar, "複製", v -> duplicate());
        frontBtn = barButton(editBar, "前へ", v -> bringToFront());
        delBtn = barButton(editBar, "削除", v -> deleteSelected());
        barButton(editBar, "完了", v -> setEditMode(false));
        barButton(editBar, "⋮", v -> showMenu());
        root.addView(editBar);

        // 画面タブ
        HorizontalScrollView hs = new HorizontalScrollView(this);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(4), 0, dp(4), 0);
        hs.addView(tabs);
        root.addView(hs);

        view = new PanelView(this);
        view.setHost(this);
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        applyOrientation();
        view.setPanel(panel, screenIndex);
        setEditMode(false);
        setUnlocked(false);
        rebuildTabs();
        PlcLink.addListener(this);

        // 動作確認用：起動時に編集モードで開く
        if (getIntent() != null && getIntent().getBooleanExtra("edit", false)) setEditMode(true);
    }

    private LinearLayout bar() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackgroundColor(0xFF263238);
        l.setPadding(dp(2), 0, dp(2), 0);
        return l;
    }

    private Button barButton(LinearLayout bar, String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setOnClickListener(l);
        bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
        updateKeepScreenOn();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        view.releaseMomentary();
        setUnlocked(false);
        if (editMode) savePanel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PlcLink.removeListener(this);
    }

    @Override
    public void onBackPressed() {
        if (editMode) {
            setEditMode(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onServerStateChanged(String error) {
        if (error != null) message(error);
        updateStatus();
        updateKeepScreenOn();
    }

    private void updateKeepScreenOn() {
        if (PlcLink.isRunning()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ------------------------------------------------------------ 保存

    private Panel loadPanel() {
        String json = prefs.getString("panel", null);
        if (json != null) {
            try {
                return Panel.fromJson(json);
            } catch (JSONException | RuntimeException ignored) {
            }
        }
        return Panel.createDefault();
    }

    private void savePanel() {
        prefs.edit().putString("panel", panel.toJson()).putInt("panelScreen", screenIndex).apply();
    }

    // ------------------------------------------------------------ 状態表示・ロック

    private boolean fresh() {
        return PlcLink.isFresh(store.staleSec() * 1000L);
    }

    private void updateStatus() {
        boolean fresh = fresh();
        view.setFresh(fresh);
        if (editMode) return;
        if (unlocked && System.currentTimeMillis() - lastOperated > AUTO_LOCK_MS) {
            setUnlocked(false);
            message("一定時間操作がなかったのでロックしました");
        }
        if (PlcLink.isStarting()) {
            setStatus("通信を開始しています…", C_GRAY);
        } else if (!PlcLink.isRunning()) {
            setStatus("● 通信停止中（タップで開始）", C_ERR);
        } else if (PlcLink.server().lastRequestMillis == 0) {
            setStatus("● " + PlcLink.server().waitingText() + (PlcLink.isClient() ? "" : "  ポート " + PlcLink.serverPort()), C_GRAY);
        } else if (!fresh) {
            long age = (System.currentTimeMillis() - PlcLink.server().lastRequestMillis) / 1000;
            setStatus("● PLCから通信なし " + age + "秒", C_ERR);
        } else {
            setStatus("● 通信中", 0xFF69F0AE);
        }
    }

    private void setStatus(String s, int color) {
        if (!s.contentEquals(status.getText())) status.setText(s);
        status.setTextColor(color);
    }

    private void setUnlocked(boolean u) {
        unlocked = u;
        if (u) lastOperated = System.currentTimeMillis();
        lockBtn.setText(u ? "🔓 操作可" : "🔒 ロック中");
        lockBtn.setTextColor(u ? 0xFFFF8F00 : 0xFF000000);
    }

    @Override
    public String canOperate() {
        if (!PlcLink.isRunning()) return "通信が停止しています";
        if (!fresh()) return "PLCと通信していないため操作できません";
        if (!unlocked) {
            flashLock();
            return "ロック中です。右上の「🔒」をタップして解除してください";
        }
        return null;
    }

    private void flashLock() {
        lockBtn.setTextColor(C_ERR);
        main.postDelayed(() -> setUnlocked(unlocked), 400);
    }

    @Override
    public void operated() {
        lastOperated = System.currentTimeMillis();
    }

    @Override
    public void message(String msg) {
        if (lastToast != null) lastToast.cancel();
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        lastToast.show();
    }

    @Override
    public void confirmThen(String msg, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle("操作確認")
                .setMessage(msg)
                .setPositiveButton("実行", (d, w) -> action.run())
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------------ 画面切替・タブ

    @Override
    public void switchScreen(int index) {
        if (index < 0 || index >= panel.screens.size()) return;
        screenIndex = index;
        view.setScreen(index);
        rebuildTabs();
        prefs.edit().putInt("panelScreen", index).apply();
    }

    private void rebuildTabs() {
        tabs.removeAllViews();
        boolean show = panel.screens.size() > 1 || editMode;
        ((View) tabs.getParent()).setVisibility(show ? View.VISIBLE : View.GONE);
        for (int i = 0; i < panel.screens.size(); i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(panel.screens.get(i).name);
            t.setTextSize(14);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            boolean cur = i == screenIndex;
            t.setTextColor(cur ? 0xFFFFFFFF : 0xFF90A4AE);
            t.setTypeface(cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setBackgroundColor(cur ? 0xFF37474F : 0x00000000);
            t.setOnClickListener(v -> switchScreen(idx));
            tabs.addView(t);
        }
        if (editMode) {
            TextView add = new TextView(this);
            add.setText("＋画面");
            add.setTextSize(14);
            add.setTextColor(0xFF64B5F6);
            add.setPadding(dp(14), dp(8), dp(14), dp(8));
            add.setOnClickListener(v -> addScreen());
            tabs.addView(add);
        }
    }

    // ------------------------------------------------------------ 数値入力

    @Override
    public void requestInput(final Panel.Part p) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        if (p.format == MonitorItem.Format.HEX16) {
            in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else {
            in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                    | (p.decimals > 0 || p.format == MonitorItem.Format.FLOAT32 ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        }
        in.setText(p.displayValue(PlcLink.mem));
        in.selectAll();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        final TextView errText = new TextView(this);
        errText.setTextColor(0xFFEF5350);
        errText.setTextSize(14);
        box.addView(errText);

        String range = "";
        if (!p.min.isEmpty() || !p.max.isEmpty()) {
            range = "\n範囲: " + (p.min.isEmpty() ? "" : p.min) + " 〜 " + (p.max.isEmpty() ? "" : p.max);
        }
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(p.label.isEmpty() ? p.device : p.label)
                .setMessage(p.device + (p.unit.isEmpty() ? "" : "（" + p.unit + "）") + range)
                .setView(box)
                .setPositiveButton("書込", null)
                .setNegativeButton("キャンセル", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String s = in.getText().toString();
            String err = p.checkInput(s);
            if (err != null) {
                errText.setText("⚠ " + err);   // ボタンを隠さないようダイアログ内に表示
                return;
            }
            String why = canOperate();
            if (why != null) {
                message(why);
                return;
            }
            d.dismiss();
            Runnable write = () -> {
                if (canOperate() != null) return;
                p.writeNumber(PlcLink.mem, s);
                operated();
                view.invalidate();
            };
            if (p.confirm) confirmThen((p.label.isEmpty() ? p.device : p.label) + " を " + s + " " + p.unit + " にします", write);
            else write.run();
        }));
        d.show();
    }

    // ------------------------------------------------------------ 編集モード

    private void setEditMode(boolean e) {
        if (editMode && !e) {
            savePanel();
            message("保存しました");
        }
        editMode = e;
        if (e) setUnlocked(false);
        runBar.setVisibility(e ? View.GONE : View.VISIBLE);
        editBar.setVisibility(e ? View.VISIBLE : View.GONE);
        view.setEditMode(e);
        if (e) message("編集モード：部品をタップで選択、もう一度タップで設定。ドラッグで移動、右下のつまみで大きさ変更");
        rebuildTabs();
        selectionChanged(null);
    }

    @Override
    public void selectionChanged(Panel.Part p) {
        boolean has = p != null;
        propBtn.setEnabled(has);
        dupBtn.setEnabled(has);
        delBtn.setEnabled(has);
        frontBtn.setEnabled(has);
    }

    private Panel.Screen cur() {
        return panel.screens.get(screenIndex);
    }

    private void addPart() {
        final Panel.Type[] types = Panel.Type.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].label;
        new AlertDialog.Builder(this).setTitle("部品を追加")
                .setItems(names, (d, which) -> {
                    Panel.Type t = types[which];
                    int w = 150, h = 70;
                    if (t == Panel.Type.BUTTON) h = 90;
                    if (t == Panel.Type.NUMBER || t == Panel.Type.INPUT) w = 320;
                    if (t == Panel.Type.TEXT) w = 320;
                    Panel.Part p = new Panel.Part(t, 0, 0, Math.min(w, panel.width()), h);
                    placeFree(p);
                    switch (t) {
                        case LAMP:
                            p.label = "ランプ";
                            break;
                        case BUTTON:
                            p.label = "ボタン";
                            break;
                        case NUMBER:
                            p.label = "表示";
                            break;
                        case INPUT:
                            p.label = "入力";
                            break;
                        case TEXT:
                            p.label = "文字";
                            break;
                        case SWITCH:
                            p.label = "画面切替";
                            p.onColor = Panel.COLORS[3];
                            p.offColor = Panel.COLORS[3];
                            break;
                        default:
                    }
                    showPartDialog(p, true);
                }).show();
    }

    /** 空いている場所を上から探して置く */
    private void placeFree(Panel.Part p) {
        for (int y = 0; y + p.h <= panel.height(); y += Panel.GRID) {
            for (int x = 0; x + p.w <= panel.width(); x += Panel.GRID) {
                p.x = x;
                p.y = y;
                boolean overlap = false;
                for (Panel.Part o : cur().parts) {
                    if (x < o.x + o.w && x + p.w > o.x && y < o.y + o.h && y + p.h > o.y) {
                        overlap = true;
                        break;
                    }
                }
                if (!overlap) return;
            }
        }
        p.x = 0;
        p.y = 0;
    }

    @Override
    public void editPart(Panel.Part p) {
        showPartDialog(p, false);
    }

    private void duplicate() {
        Panel.Part s = view.selected();
        if (s == null) return;
        Panel.Part c = s.copy();
        c.x += Panel.GRID * 2;
        c.y += Panel.GRID * 2;
        panel.clamp(c);
        cur().parts.add(c);
        view.select(c);
    }

    private void bringToFront() {
        Panel.Part s = view.selected();
        if (s == null) return;
        cur().parts.remove(s);
        cur().parts.add(s);
        view.invalidate();
    }

    private void deleteSelected() {
        final Panel.Part s = view.selected();
        if (s == null) return;
        new AlertDialog.Builder(this).setTitle("削除")
                .setMessage("「" + (s.label.isEmpty() ? s.type.label : s.label) + "」を削除しますか？")
                .setPositiveButton("削除", (d, w) -> {
                    cur().parts.remove(s);
                    view.select(null);
                })
                .setNegativeButton("キャンセル", null).show();
    }

    // ------------------------------------------------------------ 部品の設定ダイアログ

    private final class Form {
        final LinearLayout root = new LinearLayout(PanelActivity.this);

        Form() {
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(20), dp(4), dp(20), dp(4));
        }

        LinearLayout row(String label) {
            LinearLayout r = new LinearLayout(PanelActivity.this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, dp(6), 0, 0);
            TextView t = new TextView(PanelActivity.this);
            t.setText(label);
            t.setTextSize(12);
            t.setTextColor(0xFF607D8B);
            r.addView(t);
            root.addView(r);
            return r;
        }

        EditText text(LinearLayout row, String value, String hint, boolean caps) {
            EditText e = new EditText(PanelActivity.this);
            e.setText(value);
            e.setHint(hint);
            e.setSingleLine(true);
            if (caps) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            row.addView(e);
            return e;
        }

        Spinner spinner(LinearLayout row, String[] items, int sel) {
            Spinner s = new Spinner(PanelActivity.this);
            ArrayAdapter<String> a = new ArrayAdapter<>(PanelActivity.this, android.R.layout.simple_spinner_item, items);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            s.setAdapter(a);
            s.setSelection(Math.max(0, Math.min(sel, items.length - 1)));
            row.addView(s);
            return s;
        }
    }

    private void showPartDialog(final Panel.Part p, final boolean isNew) {
        final Form f = new Form();
        final Panel.Type t = p.type;

        LinearLayout rLabel = f.row(t == Panel.Type.TEXT ? "表示する文字（改行可）" : "表示名（改行可）");
        final EditText eLabel = new EditText(this);
        eLabel.setText(p.label);
        eLabel.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        rLabel.addView(eLabel);

        // ボタンの動作
        final Panel.Action[] actions = Panel.Action.values();
        String[] actNames = new String[actions.length];
        for (int i = 0; i < actions.length; i++) actNames[i] = actions[i].label;
        LinearLayout rAction = f.row("動作");
        final Spinner sAction = f.spinner(rAction, actNames, p.action.ordinal());

        String devLabel = t == Panel.Type.LAMP ? "監視デバイス（例: M0, X10, D0）"
                : t == Panel.Type.BUTTON ? "書込デバイス（PLCが読出す側）"
                : "デバイス（ワード 例: D0）";
        LinearLayout rDev = f.row(devLabel);
        final EditText eDev = f.text(rDev, p.device, "例: M100", true);

        LinearLayout rSetValue = f.row("書込む値");
        final EditText eSetValue = f.text(rSetValue, p.setValue, "例: 1", false);

        LinearLayout rLamp = f.row("ランプデバイス（空欄なら書込デバイスで点灯）");
        final EditText eLamp = f.text(rLamp, p.lampDevice, "例: M0", true);

        LinearLayout rMinOn = f.row("最小ON時間（ミリ秒）PLCの交信周期より長く");
        final EditText eMinOn = f.text(rMinOn, String.valueOf(p.minOnMs), "500", false);
        eMinOn.setInputType(InputType.TYPE_CLASS_NUMBER);

        final MonitorItem.Format[] fmts = {MonitorItem.Format.S16, MonitorItem.Format.U16,
                MonitorItem.Format.HEX16, MonitorItem.Format.S32, MonitorItem.Format.FLOAT32};
        String[] fmtNames = new String[fmts.length];
        int fmtSel = 0;
        for (int i = 0; i < fmts.length; i++) {
            fmtNames[i] = fmts[i].label;
            if (fmts[i] == p.format) fmtSel = i;
        }
        LinearLayout rFmt = f.row("データ形式");
        final Spinner sFmt = f.spinner(rFmt, fmtNames, fmtSel);

        LinearLayout rDec = f.row("小数点以下の桁数（例: 1 なら 123 → 12.3）");
        final EditText eDec = f.text(rDec, String.valueOf(p.decimals), "0", false);
        eDec.setInputType(InputType.TYPE_CLASS_NUMBER);

        LinearLayout rUnit = f.row("単位");
        final EditText eUnit = f.text(rUnit, p.unit, "例: mm", false);

        LinearLayout rMinMax = f.row("入力の下限・上限（空欄で制限なし）");
        LinearLayout mm = new LinearLayout(this);
        final EditText eMin = new EditText(this);
        eMin.setHint("下限");
        eMin.setText(p.min);
        eMin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText eMax = new EditText(this);
        eMax.setHint("上限");
        eMax.setText(p.max);
        eMax.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        mm.addView(eMin, new LinearLayout.LayoutParams(0, -2, 1f));
        mm.addView(eMax, new LinearLayout.LayoutParams(0, -2, 1f));
        rMinMax.addView(mm);

        LinearLayout rOnText = f.row("ON時の文字・OFF時の文字（空欄なら表示名）");
        LinearLayout ot = new LinearLayout(this);
        final EditText eOnText = new EditText(this);
        eOnText.setHint("ON時");
        eOnText.setText(p.onText);
        eOnText.setSingleLine(true);
        final EditText eOffText = new EditText(this);
        eOffText.setHint("OFF時");
        eOffText.setText(p.offText);
        eOffText.setSingleLine(true);
        ot.addView(eOnText, new LinearLayout.LayoutParams(0, -2, 1f));
        ot.addView(eOffText, new LinearLayout.LayoutParams(0, -2, 1f));
        rOnText.addView(ot);

        LinearLayout rOnColor = f.row(t == Panel.Type.SWITCH ? "色" : "ON時の色");
        final Spinner sOn = f.spinner(rOnColor, Panel.COLOR_NAMES, Panel.colorIndex(p.onColor));
        LinearLayout rOffColor = f.row("OFF時の色");
        final Spinner sOff = f.spinner(rOffColor, Panel.COLOR_NAMES, Panel.colorIndex(p.offColor));
        LinearLayout rTextColor = f.row("文字の色");
        final Spinner sText = f.spinner(rTextColor, Panel.COLOR_NAMES, Panel.colorIndex(p.textColor));

        String[] screenNames = new String[panel.screens.size()];
        for (int i = 0; i < screenNames.length; i++) screenNames[i] = (i + 1) + ". " + panel.screens.get(i).name;
        LinearLayout rTarget = f.row("切替先の画面");
        final Spinner sTarget = f.spinner(rTarget, screenNames, p.target);

        final CheckBox cConfirm = new CheckBox(this);
        cConfirm.setText("操作前に確認ダイアログを出す");
        cConfirm.setChecked(p.confirm);
        f.root.addView(cConfirm);

        // 種類・動作に応じて項目を出し分け
        final Runnable vis = () -> {
            Panel.Action a = actions[sAction.getSelectedItemPosition()];
            boolean btn = t == Panel.Type.BUTTON;
            boolean num = t == Panel.Type.NUMBER || t == Panel.Type.INPUT;
            boolean wordBtn = btn && a == Panel.Action.WORD_SET;
            show(rAction, btn);
            show(rDev, t != Panel.Type.TEXT && t != Panel.Type.SWITCH);
            show(rSetValue, wordBtn);
            show(rLamp, btn);
            show(rMinOn, btn && a == Panel.Action.MOMENTARY);
            show(rFmt, num || wordBtn);
            show(rDec, num || wordBtn);
            show(rUnit, num);
            show(rMinMax, t == Panel.Type.INPUT);
            show(rOnText, t == Panel.Type.LAMP || btn);
            show(rOnColor, t == Panel.Type.LAMP || btn || t == Panel.Type.SWITCH);
            show(rOffColor, t == Panel.Type.LAMP || btn);
            show(rTextColor, t == Panel.Type.TEXT);
            show(rTarget, t == Panel.Type.SWITCH);
            show(cConfirm, (btn && a != Panel.Action.MOMENTARY) || t == Panel.Type.INPUT);
        };
        sAction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                vis.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        vis.run();

        ScrollView sv = new ScrollView(this);
        sv.addView(f.root);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle((isNew ? "追加：" : "設定：") + t.label)
                .setView(sv)
                .setPositiveButton(isNew ? "追加" : "OK", null)
                .setNegativeButton("キャンセル", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Panel.Part n = p.copy();
            n.label = eLabel.getText().toString();
            n.action = actions[sAction.getSelectedItemPosition()];
            n.device = normalizeDevice(eDev.getText().toString());
            n.setValue = eSetValue.getText().toString().trim();
            n.lampDevice = normalizeDevice(eLamp.getText().toString());
            n.minOnMs = parseInt(eMinOn.getText().toString(), 500, 0, 5000);
            n.format = fmts[sFmt.getSelectedItemPosition()];
            n.decimals = parseInt(eDec.getText().toString(), 0, 0, 6);
            n.unit = eUnit.getText().toString().trim();
            n.min = eMin.getText().toString().trim();
            n.max = eMax.getText().toString().trim();
            n.onText = eOnText.getText().toString();
            n.offText = eOffText.getText().toString();
            n.onColor = Panel.COLORS[sOn.getSelectedItemPosition()];
            n.offColor = t == Panel.Type.SWITCH ? n.onColor : Panel.COLORS[sOff.getSelectedItemPosition()];
            n.textColor = Panel.COLORS[sText.getSelectedItemPosition()];
            n.target = sTarget.getSelectedItemPosition();
            n.confirm = cConfirm.isChecked() && !(t == Panel.Type.BUTTON && n.action == Panel.Action.MOMENTARY);
            if (t == Panel.Type.BUTTON && !n.action.bitAction() && n.format == MonitorItem.Format.HEX16) n.decimals = 0;
            String err = n.validate(panel.screens.size());
            if (err != null) {
                message(err);
                return;
            }
            if (isNew) {
                cur().parts.add(n);
                view.select(n);
            } else {
                int i = cur().parts.indexOf(p);
                if (i >= 0) cur().parts.set(i, n);
                view.select(n);
            }
            d.dismiss();
        }));
        d.show();
    }

    private static String normalizeDevice(String s) {
        String t = s.trim();
        if (t.isEmpty()) return "";
        try {
            return Device.parse(t).toString();
        } catch (IllegalArgumentException e) {
            return t.toUpperCase(java.util.Locale.ROOT);   // validate で弾く
        }
    }

    private static int parseInt(String s, int def, int lo, int hi) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void show(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ 画面の管理

    private void addScreen() {
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText("画面" + (panel.screens.size() + 1));
        new AlertDialog.Builder(this).setTitle("画面を追加").setView(padded(name))
                .setPositiveButton("追加", (d, w) -> {
                    String n = name.getText().toString().trim();
                    panel.screens.add(new Panel.Screen(n.isEmpty() ? "画面" + (panel.screens.size() + 1) : n));
                    switchScreen(panel.screens.size() - 1);
                })
                .setNegativeButton("キャンセル", null).show();
    }

    private void manageScreens() {
        final Panel.Screen s = cur();
        List<String> items = new ArrayList<>();
        items.add("画面を追加");
        items.add("「" + s.name + "」の名前を変更");
        items.add("「" + s.name + "」を複製");
        items.add("「" + s.name + "」を左へ移動");
        items.add("「" + s.name + "」を削除");
        new AlertDialog.Builder(this).setTitle("画面の管理")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    switch (which) {
                        case 0:
                            addScreen();
                            break;
                        case 1: {
                            final EditText name = new EditText(this);
                            name.setSingleLine(true);
                            name.setText(s.name);
                            new AlertDialog.Builder(this).setTitle("名前を変更").setView(padded(name))
                                    .setPositiveButton("OK", (dd, w) -> {
                                        String n = name.getText().toString().trim();
                                        if (!n.isEmpty()) s.name = n;
                                        rebuildTabs();
                                        savePanel();
                                    })
                                    .setNegativeButton("キャンセル", null).show();
                            break;
                        }
                        case 2: {
                            Panel.Screen c = new Panel.Screen(s.name + " コピー");
                            for (Panel.Part p : s.parts) c.parts.add(p.copy());
                            panel.screens.add(screenIndex + 1, c);
                            fixTargetsAfterInsert(screenIndex + 1);
                            switchScreen(screenIndex + 1);
                            savePanel();
                            break;
                        }
                        case 3:
                            if (screenIndex > 0) {
                                panel.screens.remove(screenIndex);
                                panel.screens.add(screenIndex - 1, s);
                                swapTargets(screenIndex, screenIndex - 1);
                                switchScreen(screenIndex - 1);
                                savePanel();
                            }
                            break;
                        case 4:
                            if (panel.screens.size() <= 1) {
                                message("最後の画面は削除できません");
                                break;
                            }
                            new AlertDialog.Builder(this).setTitle("画面を削除")
                                    .setMessage("「" + s.name + "」と、その上の部品をすべて削除しますか？")
                                    .setPositiveButton("削除", (dd, w) -> {
                                        int removed = screenIndex;
                                        panel.screens.remove(removed);
                                        fixTargetsAfterRemove(removed);
                                        switchScreen(Math.max(0, removed - 1));
                                        savePanel();
                                    })
                                    .setNegativeButton("キャンセル", null).show();
                            break;
                        default:
                    }
                }).show();
    }

    private void fixTargetsAfterInsert(int inserted) {
        for (Panel.Screen sc : panel.screens)
            for (Panel.Part p : sc.parts)
                if (p.type == Panel.Type.SWITCH && p.target >= inserted) p.target++;
    }

    private void fixTargetsAfterRemove(int removed) {
        for (Panel.Screen sc : panel.screens)
            for (Panel.Part p : sc.parts)
                if (p.type == Panel.Type.SWITCH) {
                    if (p.target == removed) p.target = 0;
                    else if (p.target > removed) p.target--;
                }
    }

    private void swapTargets(int a, int b) {
        for (Panel.Screen sc : panel.screens)
            for (Panel.Part p : sc.parts)
                if (p.type == Panel.Type.SWITCH) {
                    if (p.target == a) p.target = b;
                    else if (p.target == b) p.target = a;
                }
    }

    private View padded(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void applyOrientation() {
        setRequestedOrientation(panel.landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    // ------------------------------------------------------------ 書き出し・読み込み

    private static final int REQ_OPEN_FILE = 201, REQ_SAVE_FILE = 202;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private void exportPanel() {
        savePanel();
        new AlertDialog.Builder(this).setTitle("書き出し")
                .setItems(new String[]{"ファイルに保存", "共有（メール・LINE・Driveなど）", "クリップボードにコピー"}, (d, which) -> {
                    if (which == 0) saveToFile();
                    else if (which == 1) shareJson();
                    else copyJson();
                }).show();
    }

    private void saveToFile() {
        String name = exportFileName();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        putInitialFolder(i);
        try {
            startActivityForResult(i, REQ_SAVE_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            message("ファイル保存の画面を開けません。共有かコピーを使ってください");
        }
    }

    /** 例: 操作画面_メイン・画面2_2026-10-31_0222.json */
    private String exportFileName() {
        StringBuilder names = new StringBuilder();
        for (Panel.Screen sc : panel.screens) {
            String n = sc.name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
            if (n.isEmpty()) continue;
            if (names.length() > 0) names.append("・");
            names.append(n);
            if (names.length() > 40) {
                names.setLength(40);
                names.append("ほか");
                break;
            }
        }
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.ROOT).format(new java.util.Date());
        return "操作画面_" + (names.length() > 0 ? names + "_" : "") + date + ".json";
    }

    private void shareJson() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "SLMPモニタ " + exportFileName().replace(".json", ""));
        send.putExtra(Intent.EXTRA_TEXT, panel.toJson());
        startActivity(Intent.createChooser(send, "操作画面データを送る"));
    }

    private void copyJson() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SLMPモニタ 操作画面", panel.toJson()));
        message("クリップボードにコピーしました");
    }

    private void importPanel() {
        new AlertDialog.Builder(this).setTitle("読み込み")
                .setItems(new String[]{"ファイルを選ぶ", "貼り付けて読み込む"}, (d, which) -> {
                    if (which == 0) openFile();
                    else importFromPaste();
                }).show();
    }

    private void openFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // .json は端末によって種類がばらばらなので全ファイルから選べるようにする
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream", "*/*"});
        putInitialFolder(i);
        try {
            startActivityForResult(i, REQ_OPEN_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            message("ファイル選択の画面を開けません。貼り付けを使ってください");
        }
    }

    /** 前回使ったファイルの場所から開く（Android 8以降） */
    private void putInitialFolder(Intent i) {
        String last = prefs.getString("panelLastFile", null);
        if (last != null && android.os.Build.VERSION.SDK_INT >= 26) {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(last));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        prefs.edit().putString("panelLastFile", uri.toString()).apply();
        if (requestCode == REQ_OPEN_FILE) {
            try {
                String json = readText(uri);
                Panel p = Panel.fromJson(json);
                confirmReplace(p, fileName(uri));
            } catch (java.io.IOException e) {
                message("ファイルを読めません: " + e.getMessage());
            } catch (JSONException | RuntimeException e) {
                message("読み込めません: 操作画面のデータではないか、形式が正しくありません");
            }
        } else if (requestCode == REQ_SAVE_FILE) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("保存先を開けません");
                out.write(panel.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                message("保存しました: " + fileName(uri));
            } catch (java.io.IOException | RuntimeException e) {
                message("保存できません: " + e.getMessage());
            }
        }
    }

    private String readText(android.net.Uri uri) throws java.io.IOException {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("開けません");
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_FILE_BYTES) throw new java.io.IOException("ファイルが大きすぎます");
            }
            String s = new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (s.startsWith("\uFEFF")) s = s.substring(1);   // BOM付きでも読めるように
            return s;
        }
    }

    private String fileName(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "ファイル" : last;
    }

    private void importFromPaste() {
        final EditText in = new EditText(this);
        in.setHint("書き出した操作画面データ（JSON）を貼り付け");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(4);
        in.setMaxLines(10);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (clip != null && clip.toString().trim().startsWith("{")) in.setText(clip);
        }
        new AlertDialog.Builder(this).setTitle("貼り付けて読み込む")
                .setView(padded(in))
                .setPositiveButton("読み込む", (d, w) -> {
                    try {
                        confirmReplace(Panel.fromJson(in.getText().toString()), "貼り付けたデータ");
                    } catch (JSONException | RuntimeException e) {
                        message("読み込めません: データの形式が正しくありません");
                    }
                })
                .setNegativeButton("キャンセル", null).show();
    }

    /** 中身を見せてから置き換える */
    private void confirmReplace(final Panel p, String from) {
        int parts = 0;
        StringBuilder names = new StringBuilder();
        for (Panel.Screen sc : p.screens) {
            parts += sc.parts.size();
            if (names.length() > 0) names.append("、");
            names.append(sc.name);
        }
        new AlertDialog.Builder(this).setTitle("操作画面を置き換えますか？")
                .setMessage(from + "\n\n画面 " + p.screens.size() + "（" + names + "）\n部品 " + parts + "個\n"
                        + (p.landscape ? "横向き" : "縦向き") + "\n\n今の操作画面は上書きされます。必要なら先に書き出してください。")
                .setPositiveButton("置き換える", (d, w) -> {
                    view.releaseMomentary();
                    panel = p;
                    screenIndex = 0;
                    applyOrientation();
                    view.setPanel(panel, 0);
                    rebuildTabs();
                    savePanel();
                    message("読み込みました（" + p.screens.size() + "画面）");
                })
                .setNegativeButton("キャンセル", null).show();
    }

    // ------------------------------------------------------------ メニュー

    private void showMenu() {
        final int[] ids = {M_EDIT, M_SCREENS, M_ORIENT, M_EXPORT, M_IMPORT, M_LIST};
        String[] names = {editMode ? "編集を終了" : "画面を編集", "画面の管理", panel.landscape ? "縦向きにする" : "横向きにする",
                "書き出し（バックアップ）", "読み込み", "一覧画面に戻る"};
        new AlertDialog.Builder(this).setTitle("メニュー")
                .setItems(names, (d, which) -> menuAction(ids[which])).show();
    }

    private void menuAction(int id) {
        switch (id) {
            case M_EDIT:
                setEditMode(!editMode);
                break;
            case M_SCREENS:
                manageScreens();
                break;
            case M_ORIENT:
                new AlertDialog.Builder(this).setTitle(panel.landscape ? "縦向きにする" : "横向きにする")
                        .setMessage("すべての画面の部品の位置と大きさを、比率で移し替えます。")
                        .setPositiveButton("変更", (d, w) -> {
                            panel.setLandscape(!panel.landscape);
                            applyOrientation();
                            view.setPanel(panel, screenIndex);
                            savePanel();
                        })
                        .setNegativeButton("キャンセル", null).show();
                break;
            case M_EXPORT:
                exportPanel();
                break;
            case M_IMPORT:
                importPanel();
                break;
            case M_LIST:
                finish();
                break;
            default:
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
