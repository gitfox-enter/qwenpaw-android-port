package io.agentscope.qwenpaw.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs the bundled Linux runtime on first launch and keeps the local
 * QwenPaw server alive afterwards.
 *
 * The unpack is genuinely long (roughly a gigabyte), so it runs in a
 * foreground service and reports real progress rather than a fake animation.
 */
public class RuntimeService extends Service {

    /** One line in the boot checklist. */
    static final class Step {
        enum State { PENDING, ACTIVE, DONE }

        final String label;
        State state = State.PENDING;

        Step(String label) {
            this.label = label;
        }
    }

    /** Everything the boot screen needs, read without IPC. */
    static final class State {
        volatile float progress;
        volatile String headline = "准备中";
        volatile boolean ready;
        volatile boolean failed;
        volatile String error;
        volatile int port = RuntimePrefs.DEFAULT_PORT;
        volatile boolean lan;

        final List<Step> steps = new ArrayList<>();
    }

    private static final State STATE = new State();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Object STEP_LOCK = new Object();

    private static final String CHANNEL = "qwenpaw-runtime";
    private static final int NOTIFICATION_ID = 4301;

    static State snapshot() {
        return STATE;
    }

    static void ensureRunning(Context ctx) {
        Intent bridge = new Intent(ctx, DeviceBridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(bridge);
        } else {
            ctx.startService(bridge);
        }
        Intent i = new Intent(ctx, RuntimeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /**
     * Stops the running server and starts it again with the current prefs.
     * The extracted rootfs is kept; only the process restarts.
     */
    static void restart(Context ctx) {
        STATE.ready = false;
        STATE.failed = false;
        STATE.error = null;
        STATE.progress = 0.55f;
        STATE.headline = "正在重启服务";
        synchronized (SERVER_LOCK) {
            if (ACTIVE_SERVER != null) {
                ACTIVE_SERVER.destroy();
                ACTIVE_SERVER = null;
            }
        }
        Intent i = new Intent(ctx, RuntimeService.class);
        i.setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    static final String ACTION_RESTART = "io.agentscope.qwenpaw.mobile.RESTART";
    private static final Object SERVER_LOCK = new Object();
    private static Process ACTIVE_SERVER;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("正在准备运行环境"));
        if (RUNNING.compareAndSet(false, true)) {
            new Thread(this::installAndRun, "qwenpaw-runtime").start();
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------- install

    private void installAndRun() {
        try {
            File files = getFilesDir();
            File root = new File(files, RuntimeConfig.DIR_ROOT);
            File rootfs = new File(files, RuntimeConfig.DIR_ROOTFS);
            File stamp = new File(files, RuntimeConfig.STAMP_READY);

            synchronized (STEP_LOCK) {
                STATE.steps.clear();
                STATE.steps.add(new Step("校验安装包"));
                STATE.steps.add(new Step("解压 Linux 系统"));
                STATE.steps.add(new Step("配置 Python 环境"));
                STATE.steps.add(new Step("启动 QwenPaw 服务"));
                STATE.steps.add(new Step("等待服务就绪"));
            }

            if (!stamp.exists()) {
                root.mkdirs();
                stepActive(0);
                stepDone(0);

                stepActive(1);
                android.util.Log.i("qwenpaw-runtime", "extract begin");
                extractParts(rootfs);
                android.util.Log.i("qwenpaw-runtime", "extract done");
                stepDone(1);

stepActive(2);
        preparePython(rootfs);
        android.util.Log.i("qwenpaw-runtime", "prepare done");
        stepDone(2);

        installToolsLayer(rootfs);
        android.util.Log.i("qwenpaw-runtime", "tools layer done");

                stamp.getParentFile().mkdirs();
                if (!stamp.createNewFile()) {
                    throw new IOException("cannot write install stamp");
                }
            } else {
                for (int i = 0; i < 3; i++) {
                    stepDone(i);
                }
                // Permissions may have been lost by an older build, and Android
                // does not persist POSIX modes reliably; re-apply every launch.
                restoreLinks(rootfs);
                restoreExecutables(rootfs);
                prepareNetwork(rootfs);
                installToolsLayer(rootfs);
                setProgress(0.6f, "环境已就绪");
            }

            stepActive(3);
            setProgress(0.62f, "启动 QwenPaw 服务");
            Process server = launchServer(rootfs);
            synchronized (SERVER_LOCK) {
                ACTIVE_SERVER = server;
            }
            stepDone(3);

            stepActive(4);
            boolean up = waitForServer(180_000);
            stepDone(4);

            if (!up) {
                // Surface the last server output so the failure is actionable.
                String tail = ServerLog.tail();
                throw new IOException(tail.isEmpty()
                        ? "服务未在规定时间内就绪"
                        : "服务启动超时：" + tail);
            }
            STATE.progress = 1f;
            STATE.headline = "准备完成";
            STATE.ready = true;
            notify("QwenPaw 已在运行");

            // Keep the process reference alive for the lifetime of the service.
            Runtime.getRuntime().addShutdownHook(new Thread(server::destroy));
        } catch (Exception e) {
            STATE.failed = true;
            STATE.error = e.getMessage();
            STATE.headline = "启动失败：" + e.getMessage();
            android.util.Log.e("qwenpaw-runtime", "install/start failed", e);
            notify("启动失败");
        } finally {
            RUNNING.set(false);
            android.util.Log.i("qwenpaw-runtime", "phase finished: ready=" + STATE.ready
                    + " failed=" + STATE.failed + " progress=" + STATE.progress);
        }
    }

    private static String partName(int index) {
        return String.format("%s%02d", RuntimeConfig.PART_PREFIX, index);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) {
            android.util.Log.w("qwenpaw-runtime", "cleanup failed: " + f);
        }
    }

    /**
     * Unpacks every segment into the shared rootfs tree.
     *
     * Each asset segment is its own complete tar archive, so a process killed
     * mid-run resumes exactly at the first segment it had not finished. The
     * completed-segment count is committed to disk after each segment, which is
     * the only checkpoint tar permits without re-parsing the stream.
     */
    private void extractParts(File rootfs) throws IOException {
        RuntimePrefs prefs = new RuntimePrefs(this);
        rootfs.mkdirs();

        int done = prefs.extractPart();
        if (done < 0 || done >= RuntimeConfig.SEGMENT_COUNT) {
            done = 0;
        }
        if (done == 0) {
            // Nothing trustworthy on disk: start from a clean tree.
            File[] existing = rootfs.listFiles();
            if (existing != null && existing.length > 0) {
                deleteTree(rootfs);
                rootfs.mkdirs();
            }
        } else {
            android.util.Log.i("qwenpaw-runtime", "resuming after segment " + done);
        }

        long total = RuntimeConfig.TOTAL_BYTES;
        long consumed = 0;

        for (int i = 0; i < RuntimeConfig.SEGMENT_COUNT; i++) {
            long segBytes = 0;
            try (InputStream in = getAssets().open(partName(i))) {
                if (i < done) {
                    // Already extracted: only advance the progress counter.
                    byte[] skip = new byte[1 << 16];
                    int n;
                    while ((n = in.read(skip)) > 0) {
                        consumed += n;
                        segBytes += n;
                    }
                    setProgress(0.1f + (float) consumed / total * 0.45f,
                            "校验已解压分段 " + (i + 1) + "/" + RuntimeConfig.SEGMENT_COUNT);
                    continue;
                }

                TarUnpacker unpacker = new TarUnpacker(rootfs);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    unpacker.write(buf, 0, n);
                    consumed += n;
                    segBytes += n;
                    if (segBytes % (8L << 20) < n) {
                        float frac = total > 0
                                ? Math.min(1f, (float) consumed / total)
                                : 0.5f;
                        setProgress(0.1f + frac * 0.45f,
                                "解压 Linux 系统 " + Math.round(frac * 100) + "% ("
                                        + (i + 1) + "/" + RuntimeConfig.SEGMENT_COUNT + ")");
                    }
                }
                unpacker.finish();
            }
            // Only a fully parsed segment is a valid checkpoint.
            prefs.setExtractPart(i + 1);
            android.util.Log.i("qwenpaw-runtime",
                    "segment " + i + " done, total=" + (consumed >> 20) + "MB");
        }
        prefs.clearExtractState();
        android.util.Log.i("qwenpaw-runtime", "extract finished");
    }

    private void preparePython(File rootfs) throws IOException {
        File py = new File(rootfs, "opt/qwenpaw/python/bin/python3.12");
        if (!py.exists()) {
            throw new IOException("Python 运行时缺失");
        }
        setProgress(0.62f, "配置执行权限");

        // Android's tar extractor does not restore POSIX modes, so every
        // executable and shared object needs its +x bit put back explicitly.
        restoreLinks(rootfs);
        restoreExecutables(rootfs);

        setProgress(0.66f, "配置网络与证书");
        File home = new File(getFilesDir(), RuntimeConfig.DIR_HOME);
        home.mkdirs();
        new File(getFilesDir(), RuntimeConfig.DIR_TMP).mkdirs();
        prepareNetwork(rootfs);
        writeDeviceBridgeGuide(rootfs);
    }

    /** Agent-facing capability doc for the loopback device bridge. */
    private void writeDeviceBridgeGuide(File rootfs) {
        try {
            String nl = "\n";
            StringBuilder sb = new StringBuilder();
            sb.append("QwenPaw Device Bridge is live on 127.0.0.1:18080.").append(nl);
            sb.append("Endpoints (loopback only, no auth):").append(nl);
            sb.append("- GET  /status                service + a11y authorization state").append(nl);
            sb.append("- GET  /app/list              launchable apps as JSON (label+package)").append(nl);
            sb.append("- GET  /screen/tree           accessibility tree (text/coords/clickable)").append(nl);
            sb.append("- POST /screen/tap   {x,y}    tap at coordinates (JSON body)").append(nl);
            sb.append("- POST /screen/swipe {x1,y1,x2,y2,ms}  swipe or long-press").append(nl);
            sb.append("- POST /screen/scroll {direction}  up/down/left/right scroll").append(nl);
            sb.append("- GET  /screen/screenshot     PNG screenshot (Android 11+)").append(nl);
            sb.append("- POST /app/launch   {package} launch app by package name").append(nl);
            sb.append("- POST /input/text  {text}    type text into the focused field").append(nl);
            sb.append("- POST /clipboard   {set}     set clipboard (GET reads it)").append(nl);
            sb.append("- POST /browser/*  headless WebView (open/status/text/eval/close)").append(nl);
            sb.append("- POST /shell/exec  shell command via Shizuku (needs grant)").append(nl);
            sb.append("Workflow: GET /app/list before launching any app - never guess package").append(nl);
            sb.append("names. /screen/tree to locate an element -> /screen/tap -> /screen/tree").append(nl);
            sb.append("to confirm. If connected=false, guide the user to enable the").append(nl);
            sb.append("accessibility toggle in system settings.").append(nl);
            writeText(new File(rootfs, "opt/qwenpaw/DEVICE_BRIDGE.md"), sb.toString());
            // AGENTS.md is concatenated into every workspace's system prompt;
            // keeping the bridge there means the agent knows its device
            // powers from the first turn, with no user teaching required.
            File home = new File(getFilesDir(), RuntimeConfig.DIR_HOME);
            File wsRoot = new File(home, ".qwenpaw/workspaces");
            if (!wsRoot.exists() && !wsRoot.mkdirs()) {
                android.util.Log.w("qwenpaw-runtime",
                        "bridge guide: workspace dir unavailable, skip AGENTS.md");
                return;
            }
            File[] agents = wsRoot.listFiles();
            if (agents != null) {
                for (File agent : agents) {
                    if (!agent.isDirectory()) continue;
                    appendBridgeSection(new File(agent, "AGENTS.md"));
                }
            }
            // Fresh installs boot with the default workspace created lazily.
            appendBridgeSection(new File(wsRoot, "default/AGENTS.md"));
        } catch (IOException e) {
            android.util.Log.w("qwenpaw-runtime", "bridge guide write failed", e);
        }
    }

    private void appendBridgeSection(File agentsMd) {
        try {
            String existing = agentsMd.exists()
                    ? new String(java.nio.file.Files.readAllBytes(agentsMd.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8)
                    : "";
            String MARKER = "## Device Bridge (built-in) v4";
            // Replace any previous bridge section instead of skipping on the
            // old marker: an old injected block (pre-/app/list) must be
            // refreshed when the bridge guide is upgraded.
            int old = existing.indexOf("## Device Bridge (built-in)");
            if (old >= 0) {
                int end = existing.indexOf("\n## ", old + 1);
                existing = end >= 0 ? existing.substring(0, old) + existing.substring(end + 1)
                        : existing.substring(0, old);
            }
            if (existing.contains(MARKER)) return;
            String NL = "\n";
            StringBuilder sec = new StringBuilder();
            if (!existing.isEmpty()) sec.append(existing).append(NL).append(NL);
                        sec.append(MARKER).append(NL).append(NL);
            sec.append("You can drive this phone directly - read the screen, tap, swipe,")
                .append("launch apps, type text, browse the web, and run hidden background")
                .append("screens - all over loopback HTTP on 127.0.0.1:18080. No adb or")
                .append("root required. Endpoints:").append(NL);
            sec.append("- GET  /status                a11y authorization + screen size").append(NL);
            sec.append("- GET  /app/list              launchable apps as JSON (label+package)")
                .append("; add ?q=<substring> to filter and ?limit=N to cap").append(NL);
            sec.append("- GET  /app/current           the foreground app (package+process)").append(NL);
            sec.append("- GET  /screen/tree           current screen as an accessibility tree").append(NL);
            sec.append("- GET  /screen/text           current screen as plain text (fast read)").append(NL);
            sec.append("- POST /screen/tap   JSON body: x and y (optional display=N)").append(NL);
            sec.append("- POST /screen/find  JSON body: text to locate+tap directly").append(NL);
            sec.append("- POST /screen/longpress JSON body: x,y (optional ms) - hold a tap").append(NL);
            sec.append("- POST /screen/key   JSON body: key=back|home|enter|escape|delete|<code>").append(NL);
            sec.append("- POST /screen/swipe  JSON body: x1,y1,x2,y2,ms (optional display)").append(NL);
            sec.append("- POST /screen/scroll JSON body: direction=up/down/left/right").append(NL);
            sec.append("- GET  /screen/screenshot   PNG of the screen (or ?display=N)").append(NL);
            sec.append("- POST /app/launch   JSON body: package (optional display=N)").append(NL);
            sec.append("- POST /app/install  JSON body: {apk: local path or http url} (needs Shizuku)").append(NL);
            sec.append("- POST /app/uninstall JSON body: {package: ...} (needs Shizuku)").append(NL);
            sec.append("- POST /input/text  JSON body: text to type into the focused field").append(NL);
            sec.append("- POST /clipboard   JSON body: {\"set\":\"...\"} to set; GET to read").append(NL);
            sec.append("- GET  /notification/list active notifications as JSON").append(NL);
            sec.append("- POST /shell/exec  raw body is a shell command run through Shizuku")
                .append(" or self-ADB (auto fallback)").append(NL);
            sec.append("        (privileged: am start/input/install; needs Shizuku granted")
                .append(" or self-ADB connected)").append(NL);
            sec.append(NL);
            sec.append("SELF-ADB (wireless debugging, no Shizuku app needed): the app can")
                .append("pair with its own adbd over loopback and run shell at uid 2000.")
                .append("Use these to activate the self-ADB channel:").append(NL);
            sec.append("- POST /adb/pair  JSON body: {host, port, code} pairs with the").append(NL);
            sec.append("        wireless-debug pairing port (6-digit code from the phone)").append(NL);
            sec.append("- POST /adb/connect JSON body: {host, port} connects to the wireless")
                .append("        debug connect port (the one shown on the wireless")
                .append("        debugging home page, different from the pairing port)").append(NL);
            sec.append("- GET  /adb/status whether the keypair exists and the channel is")
                .append("        active").append(NL);
            sec.append("- POST /adb/exec   raw body is a shell command run through self-ADB").append(NL);
            sec.append("Typical one-time activation: open 开发者选项→无线调试, run /adb/pair")
                .append("with the pairing IP:port+code, then /adb/connect with the connect")
                .append("IP:port. After that /shell/exec and privileged actions auto-fallback")
                .append("to self-ADB if Shizuku is unavailable.").append(NL);
            sec.append(NL);
            sec.append("APP INVENTORY FIRST (hard rule): whenever the user asks what apps are")
                .append("installed or asks to open an app, call GET /app/list FIRST and pick")
                .append("the exact package from the returned list. NEVER guess package names")
                .append("- guessing fails (e.g. the real package for 中国移动 is")
                .append("com.chinamworld.main). Then POST /app/launch {package}.").append(NL);
            sec.append("By default /app/launch opens the app on the MAIN screen (user visible).").append(NL);
            sec.append("Pass display=<id> only when the user wants it in a background screen.").append(NL);
            sec.append(NL);
            sec.append("SCREEN AUTOMATION flow: for a quick tap by visible text, use")
                .append("POST /screen/find {\"text\":\"按钮文字\"} - it locates the element and")
                .append("taps it in one call. Otherwise GET /screen/tree to locate an element")
                .append("and its coordinates, POST /screen/tap {x,y} to click it, then GET")
                .append("/screen/tree again to confirm. Use /screen/scroll or /screen/swipe")
                .append(NL);
            sec.append("to move through lists. Take a PNG via /screen/screenshot when you need")
                .append("visual proof.").append(NL);
            sec.append(NL);
            sec.append("WEB BROWSING (built-in headless browser, no downloads needed):").append(NL);
            sec.append("- POST /browser/open   body: url to load (https:// added if missing)").append(NL);
            sec.append("- GET  /browser/status current page url").append(NL);
            sec.append("- GET  /browser/text   rendered page text (read this after open)").append(NL);
            sec.append("- POST /browser/eval   body: JS to run in the page, returns result").append(NL);
            sec.append("- POST /browser/close release the page").append(NL);
            sec.append("For anything on the web use the browser endpoints; do NOT download")
                .append("Playwright or a desktop browser - the bridge already renders pages")
                .append("with the system WebView. Typical flow: /browser/open -> wait a few")
                .append("seconds -> /browser/text (repeat until the content has loaded).").append(NL);
            sec.append(NL);
            sec.append("BACKGROUND SCREEN (hidden virtual display, like Twoyi/Mika):").append(NL);
            sec.append("- POST /screen/vdisplay/create  JSON: width,height,dpi -> displayId").append(NL);
            sec.append("- POST /screen/vdisplay/destroy  release it").append(NL);
            sec.append("- GET  /screen/vdisplay/status   is it active").append(NL);
            sec.append("Flow: create -> launch apps with display=<id> -> tap/swipe/tree/").append(NL);
            sec.append("screenshot with display=<id>. The user keeps the main screen free while")
                .append("apps run hidden. Prefer this for background automation. Remember to")
                .append("destroy the display when done (create is not idempotent).").append(NL);
            sec.append(NL);
            sec.append("SETUP CHECKS: if /status reports connected=false, tell the user to")
                .append("enable the QwenPaw accessibility service in system settings")
                .append("(设置→无障碍→已安装的服务→QwenPaw 屏幕驱动→打开). If")
                .append("/shizuku/status reports granted=false and a step needs shell power,")
                .append("either ask the user to grant Shizuku, or use the self-ADB path")
                .append("(/adb/pair + /adb/connect with the wireless-debugging pairing and")
                .append("connect ports) - both give uid 2000 shell power, self-ADB needs no")
                .append("extra app.").append(NL);
writeText(agentsMd, sec.toString());
            android.util.Log.i("qwenpaw-runtime",
                    "bridge section written: " + agentsMd.getPath());
        } catch (IOException e) {
            android.util.Log.w("qwenpaw-runtime", "AGENTS.md merge failed", e);
        }
    }

    /**
     * Walk the tree and make binaries, shared objects and scripts executable.
     * The pattern list is deliberately narrow: making everything executable
     * would also mark data files, which confuses some tools.
     */
    private void restoreExecutables(File root) {
        File[] bins = {
                new File(root, "bin"),
                new File(root, "usr/bin"),
                new File(root, "usr/sbin"),
                new File(root, "sbin"),
        };
        for (File dir : bins) {
            markExecutable(dir, true);
        }
        markExecutable(new File(root, "opt/qwenpaw/python/bin"), true);
        markExecutable(new File(root, "opt/qwenpaw/python/lib"), false);
        markExecutable(new File(root, "lib"), false);
        markExecutable(new File(root, "usr/lib"), false);
    }

    /**
     * @param deep whether regular files inside count too (bin dirs), or only
     *             entries ending in ".so*" (library dirs).
     */
    private void markExecutable(File f, boolean deep) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) {
            boolean want = deep || f.getName().contains(".so")
                    || f.getName().endsWith(".sh");
            if (want) {
                //noinspection ResultOfMethodCallIgnored
                f.setExecutable(true, false);
            }
            return;
        }
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            markExecutable(k, deep);
        }
    }

    /**
     * One-shot probe of every plausible exec chain, each logged with its exit
     * status and first output line. Runs once per install; results decide which
     * launcher the real server uses.
     */
    private void execMatrix(File rootfs, File home, File tmp, int port) {
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        File sysLoader = new File(nativeDir, "libld-linux.so");
        File guestLoader = new File(rootfs, "lib/ld-linux-aarch64.so.1");
        File python = new File(rootfs, "opt/qwenpaw/python/bin/python3.12");
        File echo = new File(rootfs, "bin/echo");

        String guestLibs = String.join(":",
                rootfs.getAbsolutePath() + "/opt/qwenpaw/python/lib",
                rootfs.getAbsolutePath() + "/lib",
                rootfs.getAbsolutePath() + "/lib/aarch64-linux-gnu",
                rootfs.getAbsolutePath() + "/usr/lib",
                rootfs.getAbsolutePath() + "/usr/lib/aarch64-linux-gnu");

        // 1. direct guest echo (app-data execve, no loader needed? echo is dyn)
        probe("A guest /bin/echo direct",
                new String[]{echo.getAbsolutePath(), "A-OK"}, null);

        // 2. direct guest loader + python --version
        probe("B guest loader direct",
                new String[]{guestLoader.getAbsolutePath(), "--library-path",
                        guestLibs, python.getAbsolutePath(), "--version"}, null);

        // 3. native loader + python --version
        probe("C native loader + python",
                new String[]{sysLoader.getAbsolutePath(), "--library-path",
                        guestLibs, python.getAbsolutePath(), "--version"}, null);

        // 4. proot + guest /bin/echo
        probe("D proot + guest echo",
                new String[]{proot.getAbsolutePath(), "--link2symlink",
                        "-r", rootfs.getAbsolutePath(),
                        "-b", "/dev", "-b", "/proc", "-b", "/sys",
                        "-b", home.getAbsolutePath() + ":/root",
                        "-b", tmp.getAbsolutePath() + ":/tmp",
                        "-w", "/root",
                        echo.getAbsolutePath(), "D-OK"},
                prootEnv(nativeDir, tmp));

        // 5. proot + native loader + python
        probe("E proot + native loader + python",
                new String[]{proot.getAbsolutePath(), "--link2symlink",
                        "-r", rootfs.getAbsolutePath(),
                        "-b", "/dev", "-b", "/proc", "-b", "/sys",
                        "-b", home.getAbsolutePath() + ":/root",
                        "-b", tmp.getAbsolutePath() + ":/tmp",
                        "-w", "/root",
                        sysLoader.getAbsolutePath(), "--library-path",
                        guestLibs, python.getAbsolutePath(), "--version"},
                prootEnv(nativeDir, tmp));

        // 6. proot + guest loader + python
        probe("F proot + guest loader + python",
                new String[]{proot.getAbsolutePath(), "--link2symlink",
                        "-r", rootfs.getAbsolutePath(),
                        "-b", "/dev", "-b", "/proc", "-b", "/sys",
                        "-b", home.getAbsolutePath() + ":/root",
                        "-b", tmp.getAbsolutePath() + ":/tmp",
                        "-w", "/root",
                        "/lib/ld-linux-aarch64.so.1", "--library-path",
                        guestLibs, python.getAbsolutePath(), "--version"},
                prootEnv(nativeDir, tmp));
    }

    private java.util.Map<String, String> prootEnv(File nativeDir, File tmp) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        return env;
    }

    private void probe(String label, String[] cmd, java.util.Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (env != null) pb.environment().putAll(env);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                int kept = 0;
                while ((line = r.readLine()) != null && kept++ < 2) {
                    out.append(line).append(" | ");
                }
            }
            int code = p.waitFor();
            android.util.Log.i("qwenpaw-runtime", "PROBE " + label
                    + " -> exit=" + code + " out=" + out);
        } catch (Exception e) {
            android.util.Log.i("qwenpaw-runtime", "PROBE " + label
                    + " -> EXC " + e.getMessage());
        }
    }

    private Process launchServer(File rootfs) throws IOException {
        File python = new File(rootfs, "opt/qwenpaw/python/bin/python3.12");
        if (!python.exists()) {
            throw new IOException("缺少 Python 运行时: " + python);
        }

        File home = new File(getFilesDir(), RuntimeConfig.DIR_HOME);
        File tmp = new File(getFilesDir(), RuntimeConfig.DIR_TMP);
        home.mkdirs();
        tmp.mkdirs();

        RuntimePrefs prefs = new RuntimePrefs(this);
        int port = prefs.port();
        boolean lan = prefs.lanEnabled();
        STATE.port = port;
        STATE.lan = lan;

        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        if (!proot.exists()) {
            throw new IOException("缺少 proot: " + proot);
        }

        // This invocation replicates AlmaDroid's proven launcher byte for byte
        // (same proot build, same flag set): proot translates guest paths and
        // intercepts the syscalls a glibc binary probes, which would otherwise
        // die with SIGSYS under Android's app seccomp filter.
        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-0");                       // fake root inside the guest
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("--kill-on-exit");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(tmp.getAbsolutePath() + ":/tmp");
        cmd.add("-b");
        cmd.add(home.getAbsolutePath() + ":/root");

        // The guest script mirrors Alma's: exports first, then exec replaces
        // the shell so the server is PID 1 of the guest session.
        String guestLibs = String.join(":",
                "/opt/qwenpaw/python/lib",
                "/lib",
                "/lib/aarch64-linux-gnu",
                "/usr/lib",
                "/usr/lib/aarch64-linux-gnu");
        String script = String.join("; ",
                "export HOME=/root",
                "export PATH=/opt/qwenpaw/python/bin:/usr/local/sbin:/usr/local/bin"
                        + ":/usr/sbin:/usr/bin:/sbin:/bin",
                "export LD_LIBRARY_PATH=" + guestLibs,
                "export QWENPAW_HOME=/root/.qwenpaw",
                "export LANG=C.UTF-8",
                "export QWENPAW_CORS_ORIGINS="
                        + (lan ? "*" : "http://127.0.0.1:" + port),
                "exec /opt/qwenpaw/python/bin/python3.12 -m qwenpaw app --host "
                        + (lan ? "0.0.0.0" : "127.0.0.1")
                        + " --port " + port);
        cmd.add("/bin/sh");
        cmd.add("-c");
        cmd.add(script);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootfs);
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        // The patched proot execve's this static loader (which lives in the
        // executable nativeLibraryDir) instead of the app-data interpreter,
        // sidestepping W^X on app storage. Without it every guest exec fails
        // with EACCES.
        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").getAbsolutePath());
        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            android.util.Log.e("qwenpaw-runtime", "ProcessBuilder.start failed", e);
            throw new IOException("无法启动服务进程: " + e.getMessage(), e);
        }
        android.util.Log.i("qwenpaw-runtime", "process started");
        drain(p.getInputStream(), "qwenpaw-server");

        // Surface an immediate exit instead of waiting the full timeout.
        new Thread(() -> {
            try {
                int code = p.waitFor();
                android.util.Log.w("qwenpaw-runtime", "server exited with " + code);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "qwenpaw-exit").start();
        return p;
    }

    /** Hard-link if possible, otherwise copy; destination is replaced. */
    private static void linkOrCopy(File src, File dst) throws IOException {
        if (!src.exists()) return;
        if (dst.exists() && dst.length() == src.length()) {
            dst.setReadable(true, false);
            dst.setExecutable(true, false);
            return;
        }
        dst.delete();
        boolean linked = false;
        try {
            java.nio.file.Files.createLink(dst.toPath(), src.toPath());
            linked = true;
        } catch (Exception ignored) {
            // Different filesystems (or no permission): fall back to a copy.
        }
        if (!linked) {
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream o = new FileOutputStream(dst)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
        }
        // The dynamic linker needs read permission and the exec bit to map it.
        dst.setReadable(true, false);
        dst.setExecutable(true, false);
    }

    private boolean waitForServer(long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        String url = "http://127.0.0.1:" + STATE.port + "/api/auth/status";
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            // Keep the bar moving so a slow first start never looks hung.
            float frac = Math.min(0.99f,
                    0.68f + (float) (System.currentTimeMillis() - start) / timeoutMs * 0.3f);
            setProgress(frac, "等待服务就绪 " + attempt + "s");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 200) return true;
            } catch (Exception ignored) {
                // Server not listening yet.
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void drain(InputStream in, String tag) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) {
                    android.util.Log.i(tag, line);
                    ServerLog.add(line);
                    if (STATE.error == null && line.toLowerCase().contains("error")) {
                        STATE.headline = "启动中：" + line.substring(0, Math.min(90, line.length()));
                    }
                }
            } catch (IOException ignored) {
                // Process ended.
            }
        }, tag + "-drain").start();
    }

    /** Ring buffer of the server's own output, used for failure messages. */
    static final class ServerLog {
        private static final ArrayDeque<String> LINES = new ArrayDeque<>();
        private static final int KEEP = 40;

        static synchronized void add(String line) {
            LINES.addLast(line);
            while (LINES.size() > KEEP) LINES.removeFirst();
        }

        static synchronized String tail() {
            StringBuilder sb = new StringBuilder();
            for (String l : LINES) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l);
            }
            String s = sb.toString();
            return s.length() > 400 ? s.substring(s.length() - 400) : s;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setProgress(float p, String headline) {
        STATE.progress = p;
        STATE.headline = headline;
        notify(headline);
    }

    private void stepActive(int index) {
        synchronized (STEP_LOCK) {
            if (index < STATE.steps.size()) {
                STATE.steps.get(index).state = Step.State.ACTIVE;
            }
        }
    }

    private void stepDone(int index) {
        synchronized (STEP_LOCK) {
            if (index < STATE.steps.size()) {
                STATE.steps.get(index).state = Step.State.DONE;
            }
        }
    }

    private static final String[][] REQUIRED_LINKS = {
            // guest scripts start with /bin/sh, which Ubuntu ships as a
            // symlink to usr/bin/dash
            {"bin/sh", "usr/bin/sh"},
            {"bin/dash", "usr/bin/dash"},
            {"bin/bash", "usr/bin/bash"},
    };

    /** Materialise essential symlinks that the extractor skipped. */
    private void restoreLinks(File rootfs) {
        for (String[] pair : REQUIRED_LINKS) {
            File link = new File(rootfs, pair[0]);
            File target = new File(rootfs, pair[1]);
            if (link.exists() || !target.exists()) continue;
            try {
                copyIfAbsent(target, link);
                //noinspection ResultOfMethodCallIgnored
                link.setExecutable(true, false);
                android.util.Log.i("qwenpaw-runtime", "restored " + pair[0]);
            } catch (IOException e) {
                android.util.Log.w("qwenpaw-runtime", "link " + pair[0] + " failed", e);
            }
        }
    }

    private void prepareNetwork(File rootfs) {
        try {
            File etc = new File(rootfs, "etc");
            File resolv = new File(etc, "resolv.conf");
            // Android itself resolves via netd, so the guest gets public
            // resolvers; 8.8.8.8 alone is unreliable on CN networks.
            writeText(resolv, "nameserver 223.5.5.5\nnameserver 1.1.1.1\n"
                    + "nameserver 8.8.8.8\n");

            File certifi = new File(rootfs,
                    "opt/qwenpaw/python/lib/python3.12/site-packages/certifi/cacert.pem");
            if (!certifi.exists()) {
                android.util.Log.w("qwenpaw-runtime", "certifi bundle missing");
                return;
            }
            File ssl = new File(etc, "ssl");
            File certsDir = new File(ssl, "certs");
            certsDir.mkdirs();
            // Python's ssl module reads openssl_cafile=/etc/ssl/cert.pem first;
            // the conventional Debian location is also written for curl-like
            // tools that may be installed later.
            copyIfAbsent(certifi, new File(certsDir, "ca-certificates.crt"));
            copyIfAbsent(certifi, new File(ssl, "cert.pem"));
            android.util.Log.i("qwenpaw-runtime", "network/ca prepared");
        } catch (Exception e) {
            android.util.Log.w("qwenpaw-runtime", "prepareNetwork failed", e);
        }
    }

    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void copyIfAbsent(File src, File dst) throws IOException {
        if (dst.exists() && dst.length() == src.length()) return;
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream o = new FileOutputStream(dst)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }

    /**
     * Unpack the optional tools layer (ffmpeg / adb / faster-whisper model and
     * wheels) into the rootfs once, then stamp it so upgrades are cheap. The
     * tar is gzip-compressed; TarUnpacker consumes the gunzipped stream.
     */
    private void installToolsLayer(File rootfs) {
        // STAMP_TOOLS is relative to filesDir; under rootfs the marker is just
        // the bare name (avoids doubling the qwenpaw/rootfs path segments).
        File stamp = new File(rootfs, ".tools-v5");
        if (stamp.exists()) return;
        try {
            android.util.Log.i("qwenpaw-runtime", "tools layer: extracting");
            try (InputStream in = getAssets().open(RuntimeConfig.TOOLS_ASSET);
                 java.util.zip.GZIPInputStream gin = new java.util.zip.GZIPInputStream(in)) {
                TarUnpacker tu = new TarUnpacker(rootfs);
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = gin.read(b)) > 0) tu.write(b, 0, n);
                tu.finish();
            }
            // Modes are not persisted by Android; the new bins need +x, and the
            // freshly unpacked .so files need it too or dlopen() refuses them.
            markExecutable(new File(rootfs, "usr/bin"), true);
            markExecutable(new File(rootfs, "usr/lib"), false);
            markExecutable(new File(rootfs, "lib"), false);
            markExecutable(new File(rootfs, "opt/qwenpaw/python/lib"), false);
            if (!stamp.createNewFile()) {
                throw new IOException("cannot write tools stamp");
            }
            android.util.Log.i("qwenpaw-runtime", "tools layer done");
        } catch (Exception e) {
            android.util.Log.w("qwenpaw-runtime", "tools layer failed", e);
        }
    }

    private void notify(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "QwenPaw 运行状态", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("QwenPaw")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }
}