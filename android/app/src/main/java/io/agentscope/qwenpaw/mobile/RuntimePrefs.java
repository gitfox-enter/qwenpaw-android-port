package io.agentscope.qwenpaw.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-tunable runtime settings.
 *
 * The port and LAN exposure live here rather than in RuntimeConfig because
 * those are build-time facts, while these are choices the user can change
 * without reinstalling.
 */
final class RuntimePrefs {

    private static final String FILE = "qwenpaw-runtime-prefs";

    private static final String KEY_PORT = "port";
    private static final String KEY_LAN = "lan";
    private static final String KEY_AUTH = "require_auth";
    private static final String KEY_EXTRACT_PROGRESS = "extract_progress";
    private static final String KEY_EXTRACT_PART = "extract_part";

    static final int DEFAULT_PORT = 8088;

    private final SharedPreferences sp;

    RuntimePrefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    int port() {
        int v = sp.getInt(KEY_PORT, DEFAULT_PORT);
        return v >= 1024 && v <= 65535 ? v : DEFAULT_PORT;
    }

    void setPort(int port) {
        sp.edit().putInt(KEY_PORT, port).apply();
    }

    /**
     * When true the server binds 0.0.0.0 so other devices on the same network
     * can open the Console in a browser. Off by default: loopback only.
     */
    boolean lanEnabled() {
        return sp.getBoolean(KEY_LAN, false);
    }

    void setLanEnabled(boolean on) {
        sp.edit().putBoolean(KEY_LAN, on).apply();
    }

    boolean requireAuth() {
        return sp.getBoolean(KEY_AUTH, false);
    }

    void setRequireAuth(boolean on) {
        sp.edit().putBoolean(KEY_AUTH, on).apply();
    }

    // ------------------------------------------------------- extract resume

    /** Byte offset inside the tar stream that has already been extracted. */
    long extractProgress() {
        return sp.getLong(KEY_EXTRACT_PROGRESS, 0L);
    }

    void setExtractProgress(long bytes) {
        // commit() rather than apply(): the process may be killed any moment.
        sp.edit().putLong(KEY_EXTRACT_PROGRESS, bytes).commit();
    }

    /** Index of the last asset part consumed. */
    int extractPart() {
        return sp.getInt(KEY_EXTRACT_PART, 0);
    }

    void setExtractPart(int index) {
        sp.edit().putInt(KEY_EXTRACT_PART, index).commit();
    }

    void clearExtractState() {
        sp.edit().remove(KEY_EXTRACT_PROGRESS).remove(KEY_EXTRACT_PART).commit();
    }
}