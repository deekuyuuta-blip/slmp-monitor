package jp.yuta.slmpmonitor;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PLCとの通信のしかた（共通部分）。
 * SlmpServer: スマホが待ち受け、PLCがシンプルCPU通信で来る。
 * PlcClient : スマホからPLCへSLMPで読み書きしに行く。
 */
public abstract class Link {

    /** 成功したやり取りの回数 */
    public final AtomicLong requestCount = new AtomicLong();
    /** 最後にPLCとやり取りできた時刻（System.currentTimeMillis()、0なら一度もなし） */
    public volatile long lastRequestMillis;

    private final ArrayDeque<String> log = new ArrayDeque<>();
    private String lastLogBody;
    private int lastLogRepeat;

    public abstract void start() throws IOException;

    public abstract void stop();

    /** 状態表示に出す相手の一覧 */
    public abstract List<String> clientIps();

    /** まだ一度もやり取りできていないときの表示 */
    public abstract String waitingText();

    // ---- ログ（同じ内容が続くときはまとめる） ----

    protected synchronized void addLog(String body) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        if (body.equals(lastLogBody) && !log.isEmpty()) {
            lastLogRepeat++;
            log.pollLast();
            log.addLast(time + " " + body + "  ×" + lastLogRepeat);
            return;
        }
        lastLogBody = body;
        lastLogRepeat = 1;
        log.addLast(time + " " + body);
        while (log.size() > 300) log.pollFirst();
    }

    /** 外からログに1行残す */
    public void note(String s) {
        addLog(s);
    }

    public synchronized List<String> logLines() {
        return new ArrayList<>(log);
    }
}
