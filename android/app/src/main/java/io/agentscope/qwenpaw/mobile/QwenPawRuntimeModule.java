package io.agentscope.qwenpaw.mobile;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Bridge for the "后台与局域网" card on the workbench screen.
 *
 * <p>The server is launched by RuntimeService from RuntimePrefs (port + LAN
 * flag), so changing the setting here only needs to persist the prefs and
 * bounce the service — the guest process keeps the already-extracted rootfs.
 */
public final class QwenPawRuntimeModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;

    public QwenPawRuntimeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "QwenPawRuntime";
    }

    @ReactMethod
    public void getLanStatus(Promise promise) {
        try {
            RuntimePrefs prefs = new RuntimePrefs(reactContext);
            WritableMap m = Arguments.createMap();
            m.putBoolean("enabled", prefs.lanEnabled());
            m.putInt("port", prefs.port());
            m.putString("ip", localIpv4());
            promise.resolve(m);
        } catch (Exception e) {
            promise.reject("lan_status", e);
        }
    }

    @ReactMethod
    public void setLan(boolean enabled, int port, Promise promise) {
        try {
            RuntimePrefs prefs = new RuntimePrefs(reactContext);
            prefs.setLanEnabled(enabled);
            if (port >= 1024 && port <= 65535) {
                prefs.setPort(port);
            }
            bounceService();
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("lan_set", e);
        }
    }

    /**
     * Stop the service (its onDestroy kills the proot server), wait for the
     * in-flight installAndRun thread to notice the port is gone and reset
     * RUNNING, then start again with the new prefs.
     */
    private void bounceService() {
        reactContext.stopService(new Intent(reactContext, RuntimeService.class));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            RuntimeService.ensureRunning(reactContext);
        }, 4000);
    }

    private static String localIpv4() {
        try {
            for (NetworkInterface ni :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "127.0.0.1";
    }
}