package io.agentscope.qwenpaw.mobile;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.IBinder;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback HTTP bridge that hands the proot agent native device powers
 * (Zafiro-style screen driving): accessibility tree, gestures, screenshots
 * and app launching. Loopback-only, no auth needed — only on-device apps can
 * reach it, which is exactly the trust boundary we want.
 *
 * Endpoints:
 *   GET  /status                     → {"connected":bool,"screen":w×h}
 *   GET  /screen/tree                → formatted accessibility tree
 *   POST /screen/tap  {x,y}          → tap gesture
 *   POST /screen/swipe {x1,y1,x2,y2,ms} → swipe gesture
 *   GET  /screen/screenshot          → PNG (API 30+, a11y capture)
 *   POST /app/launch  {package}      → launch app by package
 *   GET  /media/<anything>           → 404 stub (future)
 */
public class DeviceBridgeService extends Service {

    public static final int PORT = 18080;
    private static final String CHANNEL = "qwenpaw-device-bridge";
    private static final int NOTIFICATION_ID = 4302;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ServerSocket server;
    private Thread acceptThread;
    // Zafiro/Mika-style hidden background screen: the agent launches and
    // drives apps on this virtual display while the user keeps the main one.
    private android.hardware.display.VirtualDisplay vdisplay;
    private ImageReader vdReader;
    private int vdDisplayId = -1;
    // Headless browser for the agent: an offscreen WebView that loads pages
    // and returns rendered text. Lighter than Playwright/Chromium in proot or
    // an xfce desktop - same idea as Operit's built-in browser tool.
    private android.webkit.WebView browser;
    private String browserUrl;
    private final Object browserUrlLock = new Object();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "QwenPaw 设备控制", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        startForeground(NOTIFICATION_ID,
                b.setContentTitle("QwenPaw 设备控制")
                        .setContentText("本机桥服务运行中 (127.0.0.1:18080)")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOngoing(true)
                        .build());
        startBridge();
    }

    @Override
    public void onDestroy() {
        RUNNING.set(false);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        super.onDestroy();
    }

    private void startBridge() {
        if (!RUNNING.compareAndSet(false, true)) return;
        acceptThread = new Thread(() -> {
            // Self-healing accept loop: any socket failure is logged and the
            // listener is rebuilt, so a single bad request can never take the
            // bridge down silently again.
            while (RUNNING.get()) {
                try {
                    server = new ServerSocket(PORT, 8,
                            java.net.InetAddress.getByName("127.0.0.1"));
                    android.util.Log.i("qwenpaw-bridge", "listening on 18080");
                    while (RUNNING.get()) {
                        Socket sock = server.accept();
                        handle(sock);
                    }
                } catch (IOException e) {
                    if (RUNNING.get()) {
                        android.util.Log.w("qwenpaw-bridge",
                                "accept failed, retrying: " + e.getMessage());
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }, "qwenpaw-bridge");
        acceptThread.start();
    }

    private void handle(Socket sock) {
        new Thread(() -> {
            try (Socket s = sock) {
                s.setSoTimeout(8000);
                Request req = parse(s.getInputStream());
                if (req == null) return;
                Response resp = route(req);
                write(s, resp);
            } catch (Exception ignored) {
            }
        }, "qwenpaw-bridge-req").start();
    }

    // ------------------------------------------------------------- routing

    private Response route(Request req) {
        try {
            switch (req.path) {
                case "/status": {
                    boolean a11y = QwenPawA11yService.isConnected();
                    return json("{\"connected\":" + a11y
                            + ",\"port\":" + PORT
                            + ",\"screen\":\"" + screenMetrics() + "\"}");
                }
                case "/screen/tree": {
                    int disp = intArg(req, "display", -1);
                    return text(QwenPawA11yService.dumpTree(disp));
                }
                case "/screen/tap": {
                    int x = intArg(req, "x", -1);
                    int y = intArg(req, "y", -1);
                    if (x < 0 || y < 0) return err("x,y required");
                    return ok(QwenPawA11yService.gesture(
                            intArg(req, "display", -1), x, y, x, y, 60));
                }
                case "/screen/swipe": {
                    boolean done = QwenPawA11yService.gesture(
                            intArg(req, "display", -1),
                            intArg(req, "x1", -1), intArg(req, "y1", -1),
                            intArg(req, "x2", -1), intArg(req, "y2", -1),
                            intArg(req, "ms", 300));
                    return ok(done);
                }
                case "/screen/vdisplay/create": {
                    return createVirtualScreen(req);
                }
                case "/screen/vdisplay/destroy": {
                    return destroyVirtualScreen();
                }
                case "/shizuku/status": {
                    boolean bound;
                    boolean granted;
                    try {
                        bound = rikka.shizuku.Shizuku.pingBinder();
                        granted = bound && rikka.shizuku.Shizuku.checkSelfPermission()
                                == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    } catch (Throwable t) {
                        bound = false;
                        granted = false;
                    }
                    return json("{\"shizuku\":" + bound + ",\"granted\":" + granted + "}");
                }
                case "/shell/exec": {
                    return shizukuExec(req.body);
                }
                case "/screen/vdisplay/status": {
                    if (vdisplay == null) {
                        return json("{\"active\":false,\"displayId\":-1}");
                    }
                    return json("{\"active\":true,\"displayId\":" + vdDisplayId + "}");
                }
                case "/browser/open": {
                    return browserOpen(req.body.trim());
                }
                case "/browser/status": {
                    return browserStatus();
                }
                case "/browser/text": {
                    return browserText();
                }
                case "/browser/eval": {
                    return browserEval(req.body.trim());
                }
                case "/browser/close": {
                    return browserClose();
                }
                case "/screen/screenshot": {
                    if (intArg(req, "display", -1) == vdDisplayId && vdisplay != null) {
                        Bitmap frame = captureVirtualScreen();
                        if (frame == null) {
                            return err("no frame yet on virtual display");
                        }
                        ByteArrayOutputStream png2 = new ByteArrayOutputStream();
                        frame.compress(Bitmap.CompressFormat.PNG, 80, png2);
                        return new Response(200, "image/png", png2.toByteArray());
                    }
                    Bitmap[] holder = new Bitmap[1];
                    Object latch = new Object();
                    QwenPawA11yService.takeScreenshot(bmp -> {
                        holder[0] = bmp;
                        synchronized (latch) {
                            latch.notify();
                        }
                    });
                    synchronized (latch) {
                        latch.wait(6000);
                    }
                    Bitmap bmp = holder[0];
                    if (bmp == null) return err("screenshot unavailable (needs API 30+ and a11y on)");
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 80, png);
                    return new Response(200, "image/png", png.toByteArray());
                }
                case "/app/list": {
                    // Enumerate launchable apps so the agent knows what is
                    // installed instead of guessing package names. Output is
                    // JSON: [{"label":..,"package":..},...] sorted by label.
                    java.util.List<android.content.pm.ResolveInfo> ris =
                            getPackageManager().queryIntentActivities(
                                    new Intent(Intent.ACTION_MAIN)
                                            .addCategory(Intent.CATEGORY_LAUNCHER),
                                    0);
                    StringBuilder out = new StringBuilder("[");
                    java.util.List<String[]> items = new java.util.ArrayList<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (android.content.pm.ResolveInfo ri : ris) {
                        if (ri.activityInfo == null) continue;
                        String pkg = ri.activityInfo.packageName;
                        if (!seen.add(pkg)) continue;
                        CharSequence label = ri.loadLabel(getPackageManager());
                        items.add(new String[]{label == null ? pkg : label.toString(), pkg});
                    }
                    java.util.Collections.sort(items,
                            (a, b) -> a[0].toLowerCase(java.util.Locale.ROOT)
                                    .compareTo(b[0].toLowerCase(java.util.Locale.ROOT)));
                    boolean first = true;
                    for (String[] it : items) {
                        if (!first) out.append(',');
                        first = false;
                        out.append("{\"label\":").append(jsonQuote(it[0]))
                                .append(",\"package\":").append(jsonQuote(it[1])).append('}');
                    }
                    out.append(']');
                    return json("{\"count\":" + items.size() + ",\"apps\":" + out + "}");
                }
                case "/app/launch": {
                    String pkg = req.body.trim();
                    if (pkg.startsWith("{")) {
                        pkg = req.body.replaceAll(".*\"package\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                    }
                    int disp = intArg(req, "display", -1);
                    // Only block when the caller explicitly asked for the virtual
                    // display and it is not created. Without a display arg the app
                    // opens on the main screen (disp == -1 must not match the
                    // initial vdDisplayId, which is also -1 before any screen).
                    boolean wantsVirtual = req.query.containsKey("display");
                    if (wantsVirtual && disp == vdDisplayId && vdisplay == null) {
                        return err("virtual display not created; POST /screen/vdisplay/create first");
                    }
                    if (pkg.isEmpty()) return err("package required");
                    Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i == null) return err("package not installed: " + pkg);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (disp >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ActivityOptions opts = ActivityOptions.makeBasic();
                        opts.setLaunchDisplayId(disp);
                        startActivity(i, opts.toBundle());
                    } else {
                        startActivity(i);
                    }
                    return ok(true);
                }
                case "/input/text": {
                    return inputText(req.body.trim());
                }
                case "/screen/scroll": {
                    return screenScroll(req);
                }
                case "/clipboard": {
                    return clipboard(req);
                }
                case "/notification/list": {
                    return notificationList();
                }
                default:
                    return err("unknown endpoint: " + req.path);
            }
        } catch (Exception e) {
            return err("bridge error: " + e.getMessage());
        }
    }

    // ------------------------------------------------- headless browser

    /**
     * Agent browser on top of an offscreen WebView. Loads a URL, waits for
     * render, and hands back the page text - lighter than Playwright/Chromium
     * in proot or a full desktop, same idea as Operit's built-in tool.
     */
    private Response browserOpen(String url) {
        try {
            if (url == null || url.isEmpty()) return err("url required");
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            final String target = url;
            final Object latch = new Object();
            final String[] failure = {null};
            final boolean[] done = {false};
            synchronized (latch) {
                getMainHandler().post(() -> {
                    try {
                        if (browser == null) browser = createBrowser();
                        browser.loadUrl(target);
                        synchronized (browserUrlLock) {
                            browserUrl = target;
                        }
                    } catch (Exception e) {
                        failure[0] = e.getMessage();
                    } finally {
                        done[0] = true;
                        synchronized (latch) {
                            latch.notify();
                        }
                    }
                });
                latch.wait(10000);
            }
            if (failure[0] != null) return err("browser open: " + failure[0]);
            if (!done[0]) return err("browser open timed out");
            // Give the page a moment to start rendering; text follows later.
            Thread.sleep(2500);
            return json("{\"ok\":true,\"url\":" + jsonQuote(target) + "}");
        } catch (Exception e) {
            return err("browser open: " + e.getMessage());
        }
    }

    private Response browserStatus() {
        if (browser == null) return json("{\"open\":false}");
        // getUrl() must run on the main thread; keep the last requested URL
        // cached instead so /browser/status is thread-safe.
        synchronized (browserUrlLock) {
            return json("{\"open\":true,\"url\":"
                    + jsonQuote(browserUrl == null ? "" : browserUrl) + "}");
        }
    }

    /** Extract the rendered text of the current page on the main thread. */
    private Response browserText() {
        try {
            if (browser == null) return err("no page open; POST /browser/open first");
            final Object latch = new Object();
            final String[] text = {null};
            synchronized (latch) {
                getMainHandler().post(() -> {
                    try {
                        // document.body.innerText keeps visible text in DOM
                        // order; scripts get one extra beat to finish.
                        browser.evaluateJavascript(
                                "(function(){return document.body ? document.body.innerText : '';})()",
                                value -> {
                                    text[0] = value == null ? "" : value;
                                    synchronized (latch) {
                                        latch.notify();
                                    }
                                });
                    } catch (Exception e) {
                        text[0] = "eval failed: " + e.getMessage();
                        synchronized (latch) {
                            latch.notify();
                        }
                    }
                });
                latch.wait(10000);
            }
            if (text[0] == null) return err("text extraction timed out");
            // evaluateJavascript returns a JSON-encoded string; unquote it.
            String body = text[0];
            if (body.length() >= 2 && body.startsWith("\"") && body.endsWith("\"")) {
                body = body.substring(1, body.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\");
            }
            return text(body);
        } catch (Exception e) {
            return err("browser text: " + e.getMessage());
        }
    }

    /** Run arbitrary JS in the page and get the JSON-encoded result. */
    private Response browserEval(String script) {
        try {
            if (browser == null) return err("no page open; POST /browser/open first");
            if (script == null || script.isEmpty()) return err("script required");
            final Object latch = new Object();
            final String[] result = {null};
            synchronized (latch) {
                getMainHandler().post(() -> {
                    try {
                        browser.evaluateJavascript(script, value -> {
                            result[0] = value == null ? "null" : value;
                            synchronized (latch) {
                                latch.notify();
                            }
                        });
                    } catch (Exception e) {
                        result[0] = "eval failed: " + e.getMessage();
                        synchronized (latch) {
                            latch.notify();
                        }
                    }
                });
                latch.wait(10000);
            }
            if (result[0] == null) return err("eval timed out");
            return text(result[0]);
        } catch (Exception e) {
            return err("browser eval: " + e.getMessage());
        }
    }

    private synchronized Response browserClose() {
        final Object latch = new Object();
        if (browser != null) {
            synchronized (latch) {
                getMainHandler().post(() -> {
                    try {
                        browser.destroy();
                    } catch (Exception ignored) {
                    }
                    browser = null;
                    synchronized (browserUrlLock) {
                        browserUrl = null;
                    }
                    synchronized (latch) {
                        latch.notify();
                    }
                });
                try {
                    latch.wait(3000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return ok(true);
    }

    private android.webkit.WebView createBrowser() {
        android.webkit.WebView wv = new android.webkit.WebView(this);
        android.webkit.WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
        wv.setLayoutParams(new android.view.ViewGroup.LayoutParams(1080, 1920));
        wv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
        wv.layout(0, 0, 1080, 1920);
        return wv;
    }

    private android.os.Handler getMainHandler() {
        return new android.os.Handler(getMainLooper());
    }

    // ----------------------------------------------------- shizuku shell

    /**
     * Run a shell command through Shizuku (adb/shell uid). This is the
     * privileged path the agent uses for cross-display launches and input
     * injection: am start --display, input swipe -d, screencap -d, etc.
     * Body is the raw command line; executed via sh -c.
     */
    private Response shizukuExec(String cmd) {
        try {
            if (cmd == null || cmd.trim().isEmpty()) return err("command required");
            boolean bound;
            try {
                bound = rikka.shizuku.Shizuku.pingBinder();
            } catch (Throwable t) {
                bound = false;
            }
            if (!bound) {
                return err("shizuku not running; install/start the Shizuku app first");
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return err("shizuku permission not granted yet; authorize QwenPaw "
                        + "inside the Shizuku app, then retry");
            }
            // Shizuku.getBinder() is the official path for the authorized
            // service binder; SystemServiceHelper.getSystemService("shizuku")
            // returns null from an app process on Android 13+ (ServiceManager
            // is restricted), which made newProcess throw a null-message NPE.
            android.os.IBinder binder = rikka.shizuku.Shizuku.getBinder();
            if (binder == null) {
                return err("shizuku service binder null; restart Shizuku and retry");
            }
            moe.shizuku.server.IShizukuService service =
                    moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
            // newProcess returns an IRemoteProcess (binder mirror of Process):
            // reading its getInputStream yields combined stdout/stderr.
            moe.shizuku.server.IRemoteProcess proc = service.newProcess(
                    new String[]{"sh", "-c", cmd.trim()},
                    new String[]{"PATH=/system/bin:/system/xbin:/odm/bin:/vendor/bin"},
                    "/");
            android.os.ParcelFileDescriptor pfd = proc.getInputStream();
            android.os.ParcelFileDescriptor epfd = null;
            try {
                epfd = proc.getErrorStream();
            } catch (Exception ignore) {
            }
            java.io.InputStream fin =
                    new java.io.FileInputStream(pfd.getFileDescriptor());
            java.io.InputStream ein = epfd == null ? null
                    : new java.io.FileInputStream(epfd.getFileDescriptor());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            long deadline = System.currentTimeMillis() + 20000;
            boolean finEof = false;
            boolean einEof = (ein == null);
            try {
                while (System.currentTimeMillis() < deadline && out.size() < 512 * 1024) {
                    boolean any = false;
                    if (!finEof) {
                        try {
                            int avail = fin.available();
                            if (avail > 0) {
                                n = fin.read(buf, 0, Math.min(buf.length, avail));
                                if (n > 0) {
                                    out.write(buf, 0, n);
                                    any = true;
                                }
                            }
                        } catch (IOException eof) {
                            // Process exited and the pipe was torn down.
                            finEof = true;
                        }
                    }
                    if (!einEof) {
                        try {
                            int avail = ein.available();
                            if (avail > 0) {
                                n = ein.read(buf, 0, Math.min(buf.length, avail));
                                if (n > 0) {
                                    out.write(buf, 0, n);
                                    any = true;
                                }
                            }
                        } catch (IOException eof) {
                            einEof = true;
                        }
                    }
                    if (finEof && einEof) break;
                    if (!any) {
                        boolean exited = false;
                        try {
                            proc.exitValue();
                            exited = true;
                        } catch (Exception stillRunning) {
                            exited = false;
                        }
                        if (exited) {
                            // Drain whatever is left, then stop.
                            if (!finEof) {
                                try {
                                    int avail = fin.available();
                                    while (avail > 0 && out.size() < 512 * 1024) {
                                        n = fin.read(buf, 0, Math.min(buf.length, avail));
                                        if (n <= 0) break;
                                        out.write(buf, 0, n);
                                        avail = fin.available();
                                    }
                                } catch (IOException ignore) {
                                }
                            }
                            break;
                        }
                        Thread.sleep(120);
                    }
                }
            } finally {
                try {
                    fin.close();
                } catch (IOException ignore) {
                }
                if (ein != null) {
                    try {
                        ein.close();
                    } catch (IOException ignore) {
                    }
                }
                proc.destroy();
            }
            int exit = -1;
            try {
                exit = proc.exitValue();
            } catch (Exception ignore) {
            }
            return json("{\"exit\":" + exit + ",\"output\":"
                    + jsonQuote(out.toString("UTF-8")) + "}");
        } catch (Exception e) {
            return err("shizuku exec: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /** Minimal JSON string encoder for embedding raw command output. */
    private static String jsonQuote(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                sb.append((char) 92).append((char) 34);
            } else if (c == 92) {
                sb.append((char) 92).append((char) 92);
            } else if (c == (char) 10) {
                sb.append((char) 92).append('n');
            } else if (c == (char) 13) {
                sb.append((char) 92).append('r');
            } else if (c == (char) 9) {
                sb.append((char) 92).append('t');
            } else if (c < 0x20) {
                sb.append(String.format("\\u" + "%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    // ------------------------------------------------- virtual screen (Mika
    // style hidden background display): the agent launches and drives real
    // apps here while the user keeps using the main screen.

    private synchronized Response createVirtualScreen(Request req) {
        try {
            destroyVirtualScreen();
            DisplayManager dm =
                    (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            int w = intArg(req, "width", 720);
            int h = intArg(req, "height", 1560);
            int dpi = intArg(req, "dpi", 320);
            vdReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            // PUBLIC keeps the display out of the "private" bucket: private
            // virtual displays get no WindowsForAccessibilityObserver, so the
            // a11y tree endpoint can never see them. Mirrors Twoyi's twin
            // display recipe (TRUSTED|OWN_CONTENT_ONLY|..., shell uid).
            vdisplay = dm.createVirtualDisplay("qwenpaw-agent",
                    w, h, dpi, vdReader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (vdisplay == null) return err("createVirtualDisplay failed");
            Display disp = vdisplay.getDisplay();
            vdDisplayId = disp.getDisplayId();
            return json("{\"ok\":true,\"displayId\":" + vdDisplayId
                    + ",\"width\":" + w + ",\"height\":" + h + "}");
        } catch (Exception e) {
            return err("vdisplay create: " + e.getMessage());
        }
    }

    private synchronized Response destroyVirtualScreen() {
        try {
            if (vdisplay != null) {
                vdisplay.release();
                vdisplay = null;
            }
            if (vdReader != null) {
                vdReader.close();
                vdReader = null;
            }
            vdDisplayId = -1;
            return json("{\"ok\":true}");
        } catch (Exception e) {
            return err("vdisplay destroy: " + e.getMessage());
        }
    }

    private Bitmap captureVirtualScreen() {
        try {
            Image img = vdReader.acquireLatestImage();
            if (img == null) return null;
            Image.Plane plane = img.getPlanes()[0];
            java.nio.ByteBuffer buf = plane.getBuffer();
            int w = img.getWidth();
            int h = img.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (rowStride == w * pixelStride) {
                buf.rewind();
                bmp.copyPixelsFromBuffer(buf);
            } else {
                int[] line = new int[w];
                for (int row = 0; row < h; row++) {
                    buf.position(row * rowStride);
                    for (int col = 0; col < w; col++) {
                        line[col] = buf.getInt();
                    }
                    bmp.setPixels(line, 0, w, 0, row, w, 1);
                }
                buf.rewind();
            }
            img.close();
            return bmp;
        } catch (Exception e) {
            android.util.Log.w("qwenpaw-bridge", "capture: " + e.getMessage());
            return null;
        }
    }

    private String screenMetrics() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return m.widthPixels + "x" + m.heightPixels;
    }

    /** Type text into the focused field. Uses Shizuku `input text` when
     * granted; shell quoting keeps spaces and non-ASCII intact. */
    private Response inputText(String rawBody) {
        try {
            if (rawBody.isEmpty()) return err("text required");
            String text = rawBody.startsWith("{")
                    ? rawBody.replaceAll(".*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1")
                            .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"")
                    : rawBody;
            if (text.isEmpty()) return err("text required");
            if (!shizukuGranted()) {
                return err("input text needs Shizuku authorization; enable it first");
            }
            StringBuilder cmd = new StringBuilder("input text ");
            cmd.append('"').append(text.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
            return text(shizukuExecRaw(cmd.toString()));
        } catch (Exception e) {
            return err("input text: " + e.getMessage());
        }
    }

    /** Scroll the current screen. direction: up/down/left/right. */
    private Response screenScroll(Request req) {
        String dir = req.query.getOrDefault("direction", "down").toLowerCase();
        int x = intArg(req, "x", -1);
        int y = intArg(req, "y", -1);
        int distance = intArg(req, "distance", 400);
        int x1, y1, x2, y2;
        switch (dir) {
            case "up":
                x1 = x < 0 ? 540 : x; y1 = y < 0 ? 1200 : y; x2 = x1; y2 = y1 - distance; break;
            case "down":
                x1 = x < 0 ? 540 : x; y1 = y < 0 ? 200 : y; x2 = x1; y2 = y1 + distance; break;
            case "left":
                x1 = x < 0 ? 1000 : x; y1 = y < 0 ? 500 : y; x2 = x1 - distance; y2 = y1; break;
            case "right":
                x1 = x < 0 ? 280 : x; y1 = y < 0 ? 500 : y; x2 = x1 + distance; y2 = y1; break;
            default:
                return err("direction must be up/down/left/right");
        }
        int disp = intArg(req, "display", -1);
        boolean done = QwenPawA11yService.gesture(disp, x1, y1, x2, y2, 300);
        if (done) return ok(true);
        if (!shizukuGranted()) {
            return err("scroll failed via a11y; Shizuku needed for swipe fallback");
        }
        String cmd = "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 300"
                + (disp >= 0 ? " --display " + disp : "");
        return text(shizukuExecRaw(cmd));
    }

    /** Set or read the clipboard. POST body {set:"..."} sets; GET reads. */
    private Response clipboard(Request req) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return err("clipboard unavailable");
            String set = req.body == null ? null
                    : req.body.replaceAll(".*\"set\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1")
                            .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
            if (set != null && !set.isEmpty() && (req.body != null && req.body.startsWith("{"))) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("qwenpaw", set));
                return ok(true);
            }
            android.content.ClipData.Item item = cm.getPrimaryClip() != null
                    ? cm.getPrimaryClip().getItemAt(0) : null;
            String val = item == null ? "" : String.valueOf(item.coerceToText(this));
            return json("{\"text\":" + jsonQuote(val) + "}");
        } catch (Exception e) {
            return err("clipboard: " + e.getMessage());
        }
    }

    /** List active notifications; empty array if none can be read. */
    private Response notificationList() {
        // Notifications need a bound NotificationListenerService; the bridge
        // service is not one, so report what we can and stay honest.
        return json("{\"count\":0,\"note\":\"no notification listener bound; grant a listener or use /screen/tree\"}");
    }

    private boolean shizukuGranted() {
        try {
            return rikka.shizuku.Shizuku.pingBinder()
                    && rikka.shizuku.Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Run a shell command through Shizuku and return the raw stdout+stderr. */
    private String shizukuExecRaw(String cmd) {
        Response r = shizukuExec(cmd);
        if (r.type.startsWith("application/json")
                && r.body.length > 0 && r.body[0] == '{') {
            String s = new String(r.body, StandardCharsets.UTF_8);
            int eq = s.indexOf("\"error\":\"");
            if (eq >= 0) {
                int start = eq + 9;
                int end = s.indexOf('"', start);
                throw new IllegalStateException(s.substring(start, end < 0 ? s.length() : end));
            }
        }
        return new String(r.body, StandardCharsets.UTF_8);
    }

    private static Response ok(boolean done) {
        return json("{\"ok\":" + done + "}");
    }

    private static Response json(String body) {
        return new Response(200, "application/json",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static Response text(String body) {
        return new Response(200, "text/plain; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static Response err(String message) {
        return json("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }

    // ------------------------------------------------------------- plumbing

    private static final class Request {
        String method;
        String path;
        Map<String, String> query = new HashMap<>();
        String body;
    }

    private static final class Response {
        final int code;
        final String type;
        final byte[] body;

        Response(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }
    }

    private Request parse(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        Request req = new Request();
        req.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    req.query.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            }
            target = target.substring(0, q);
        }
        req.path = URLDecoder.decode(target, "UTF-8");
        int contentLength = 0;
        String header;
        while ((header = readLine(in)) != null && !header.isEmpty()) {
            if (header.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(
                        header.substring(15).trim());
            }
        }
        if (contentLength > 0 && contentLength < 1 << 20) {
            byte[] buf = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(buf, off, contentLength - off);
                if (n < 0) break;
                off += n;
            }
            req.body = new String(buf, 0, off, StandardCharsets.UTF_8);
        }
        // POST bodies may also be form-encoded query strings.
        if (req.body != null && req.query.isEmpty()
                && req.body.startsWith("{") == false) {
            for (String pair : req.body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    req.query.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            }
        }
        return req;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return sb.toString();
    }

    private int intArg(Request req, String name, int fallback) {
        String v = req.query.get(name);
        if (v == null) return fallback;
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void write(Socket sock, Response resp) throws IOException {
        OutputStream out = sock.getOutputStream();
        String head = "HTTP/1.1 " + resp.code + " OK\r\n"
                + "Content-Type: " + resp.type + "\r\n"
                + "Content-Length: " + resp.body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(resp.body);
        out.flush();
    }
}
