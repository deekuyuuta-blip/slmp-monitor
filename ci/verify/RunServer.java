import jp.yuta.slmpmonitor.*;

/** アプリの通信部分（SlmpServer）だけをPCで動かす。PLC役の外部ライブラリから試験するため */
public class RunServer {
    public static void main(String[] a) throws Exception {
        DeviceMemory m = new DeviceMemory();
        // スマホ→PLCの値（画面から書いた想定）
        new MonitorItem("", "D100", MonitorItem.Format.S16, true).writeValue(m, "555");
        new MonitorItem("", "D101", MonitorItem.Format.S16, true).writeValue(m, "-2");
        new MonitorItem("", "M2000", MonitorItem.Format.BIT, true).writeBit(m, true);
        new MonitorItem("", "M2002", MonitorItem.Format.BIT, true).writeBit(m, true);
        SlmpServer s = new SlmpServer(m, Integer.parseInt(a[0]), "");
        s.start();
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(Long.parseLong(a[1]));
        java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(a[2]), "UTF-8"));
        w.println("requests=" + s.requestCount.get());
        // PLC役が書いた値
        w.println("D10=" + m.readWords(0xA8, 10, 3)[0] + "," + m.readWords(0xA8, 11, 1)[0] + "," + m.readWords(0xA8, 12, 1)[0]);
        boolean[] b = m.readBits(0x90, 10, 3);
        w.println("M10=" + b[0] + "," + b[1] + "," + b[2]);
        for (String l : s.logLines()) w.println("log: " + l);
        w.close();
        s.stop();
    }
}
