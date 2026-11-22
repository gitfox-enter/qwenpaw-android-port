package io.agentscope.qwenpaw.mobile;

/**
 * Name of the payload shipped inside the APK, and where it lands at runtime.
 * Kept in one place so the build script and the Java side cannot drift apart.
 */
final class RuntimeConfig {

    private RuntimeConfig() {}

    /** Independent tar segments inside assets/, e.g. rootfs.part00 ... */
    static final String PART_PREFIX = "rootfs.part";

    /**
     * How many segments the build produced. Each one is a self-contained tar
     * archive so extraction can resume at a segment boundary.
     */
    static final int SEGMENT_COUNT = 10;

    /**
     * Uncompressed size of every part in bytes. The assets are deflated inside
     * the APK, so the runtime cannot ask the stream how big it is; the build
     * script records the real number instead.
     */
    static final long TOTAL_BYTES = 1_585_930_240L;

    /** Loopback port the bundled server binds to. */
    static final int SERVER_PORT = 8088;

    /** proot entry point packaged as a native lib so it stays executable. */
    static final String PROOT_LIB = "libproot.so";

    /**
     * Optional post-install layer: ffmpeg/adb/faster-whisper model etc. Kept
     * separate from the rootfs segments so tool upgrades don't re-ship 1.6GB.
     * The runtime unpacks it once and marks the rootfs with STAMP_TOOLS.
     */
    static final String TOOLS_ASSET = "tools.part";

    // Layout inside the app's private files directory.
    static final String DIR_ROOT = "qwenpaw";
    static final String DIR_ROOTFS = DIR_ROOT + "/rootfs";
    static final String DIR_HOME = DIR_ROOT + "/home";
    static final String DIR_TMP = DIR_ROOT + "/tmp";
    static final String STAMP_READY = DIR_ROOT + "/.installed";
    /** Marker that the tools layer was merged into this rootfs. */
    static final String STAMP_TOOLS = DIR_ROOTFS + "/.tools-v5";
}