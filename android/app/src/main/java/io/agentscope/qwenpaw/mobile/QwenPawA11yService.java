package io.agentscope.qwenpaw.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Screen driver for the device bridge (Zafiro-style): exposes the
 * accessibility node tree, gesture dispatch and (API 30+) screenshots to the
 * proot agent over the local HTTP bridge. Stateless; one instance lives as
 * long as the user keeps the toggle on in system settings.
 */
public class QwenPawA11yService extends AccessibilityService {

    private static final AtomicReference<QwenPawA11yService> INSTANCE =
            new AtomicReference<>(null);
    private static final int MAX_NODES = 400;
    private static final int MAX_DEPTH = 24;
    private static final StringBuilder TREE_BUILDER = new StringBuilder(64 * 1024);

    public static boolean isConnected() {
        return INSTANCE.get() != null;
    }

    /** Formatted, depth/width-limited node tree of the active window. */
    public static synchronized String dumpTree(int displayId) {
        QwenPawA11yService svc = INSTANCE.get();
        if (svc == null) return "accessibility service not connected";
        AccessibilityNodeInfo root = null;
        if (displayId < 0) {
            root = svc.getRootInActiveWindow();
        } else {
            // Prefer getWindowsOnAllDisplays() (API 30+): getWindows() is not
            // guaranteed to carry windows from secondary displays.
            if (Build.VERSION.SDK_INT >= 30) {
                android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> byDisplay =
                        svc.getWindowsOnAllDisplays();
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins =
                        byDisplay == null ? null : byDisplay.get(displayId);
                if (wins != null) {
                    for (android.view.accessibility.AccessibilityWindowInfo win : wins) {
                        if (win.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                                && win.getRoot() != null) {
                            root = win.getRoot();
                            break;
                        }
                    }
                }
            }
            if (root == null) {
                for (android.view.accessibility.AccessibilityWindowInfo win : svc.getWindows()) {
                    if (win.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                            && win.getDisplayId() == displayId
                            && win.getRoot() != null) {
                        root = win.getRoot();
                    }
                }
            }
        }
        if (root == null) return "no active window on display " + displayId;
        StringBuilder sb = TREE_BUILDER;
        sb.setLength(0);
        walk(root, 0, new int[]{0});
        return sb.length() == 0 ? "(empty tree)" : sb.toString();
    }

    private static void walk(AccessibilityNodeInfo node, int depth, int[] budget) {
        if (node == null || depth > MAX_DEPTH || budget[0] > MAX_NODES) return;
        budget[0]++;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence cls = node.getClassName();
        StringBuilder line = TREE_BUILDER;
        for (int i = 0; i < depth; i++) line.append(' ');
        line.append('[').append(cls == null ? "?" : cls.toString().replaceFirst("^android\\.widget\\.", ""))
                .append("] (").append(b.left).append(',').append(b.top)
                .append('-').append(b.right).append(',').append(b.bottom).append(')');
        if (text != null && text.length() > 0) {
            line.append(" text=\"").append(abbreviate(text)).append('"');
        }
        if (desc != null && desc.length() > 0) {
            line.append(" desc=\"").append(abbreviate(desc)).append('"');
        }
        if (node.isClickable()) line.append(" *click");
        if (node.isScrollable()) line.append(" *scroll");
        line.append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), depth + 1, budget);
        }
    }

    private static String abbreviate(CharSequence value) {
        String s = value.toString().replace('\n', ' ');
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** Swipe gesture; duration 0 uses the system default. */
    public static boolean gesture(int displayId, float x1, float y1, float x2, float y2, long ms) {
        QwenPawA11yService svc = INSTANCE.get();
        if (svc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        if (displayId >= 0 && Build.VERSION.SDK_INT >= 33) {
            builder.setDisplayId(displayId);
        }
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x1, y1);
        if (x1 == x2 && y1 == y2) {
            path.lineTo(x1 + 1, y1 + 1); // tap: tiny move so the stroke is valid
        } else {
            path.lineTo(x2, y2);
        }
        builder.addStroke(new GestureDescription.StrokeDescription(
                path, 0, ms > 0 ? ms : 60));
        return svc.dispatchGesture(builder.build(), null, null);
    }

    /** Long-press gesture at (x,y): a held-down tap, for context menus etc. */
    public static boolean gestureLong(int displayId, float x, float y, long ms) {
        QwenPawA11yService svc = INSTANCE.get();
        if (svc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        if (displayId >= 0 && Build.VERSION.SDK_INT >= 33) {
            builder.setDisplayId(displayId);
        }
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        path.lineTo(x, y);
        builder.addStroke(new GestureDescription.StrokeDescription(
                path, 0, ms > 0 ? ms : 800));
        return svc.dispatchGesture(builder.build(), null, null);
    }

    /** Inject a hardware key via accessibility global actions (back/home etc.). */
    public static boolean globalAction(String action) {
        QwenPawA11yService svc = INSTANCE.get();
        if (svc == null) return false;
        if (action == null) return false;
        switch (action) {
            case "back":
                return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case "home":
                return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            case "recents":
                return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
            case "notifications":
                return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            default:
                return false;
        }
    }

    /** API 30+ accessibility screenshot; callback receives the bitmap or null. */
    public interface ShotCallback {
        void onResult(Bitmap bitmap);
    }

    /**
     * Find the first clickable node whose text/description contains the query
     * and return its tap coordinates as "x,y". Returns null if nothing found.
     * Lets the agent tap an element by name without parsing the whole tree.
     */
    public static synchronized String findElement(String query, int displayId) {
        QwenPawA11yService svc = INSTANCE.get();
        if (svc == null || query == null || query.trim().isEmpty()) return null;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        AccessibilityNodeInfo root = null;
        if (displayId < 0) {
            root = svc.getRootInActiveWindow();
        } else if (Build.VERSION.SDK_INT >= 30) {
            android.util.SparseArray<java.util.List<AccessibilityWindowInfo>> byDisplay =
                    svc.getWindowsOnAllDisplays();
            java.util.List<AccessibilityWindowInfo> wins =
                    byDisplay == null ? null : byDisplay.get(displayId);
            if (wins != null) {
                for (AccessibilityWindowInfo win : wins) {
                    if (win.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                            && win.getRoot() != null) {
                        root = win.getRoot();
                        break;
                    }
                }
            }
        }
        if (root == null) return null;
        Rect[] hit = new Rect[1];
        walkFind(root, q, hit, new int[]{0});
        if (hit[0] == null) return null;
        int cx = (hit[0].left + hit[0].right) / 2;
        int cy = (hit[0].top + hit[0].bottom) / 2;
        return cx + "," + cy;
    }

    private static void walkFind(AccessibilityNodeInfo node, String q, Rect[] hit, int[] budget) {
        if (node == null || hit[0] != null || budget[0] > 800) return;
        budget[0]++;
        String text = node.getText() == null ? "" : node.getText().toString();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        String hay = (text + " " + desc).toLowerCase(java.util.Locale.ROOT);
        if (hay.contains(q)) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (node.isClickable() || b.width() > 0) {
                hit[0] = b;
                return;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walkFind(node.getChild(i), q, hit, budget);
            if (hit[0] != null) return;
        }
    }

    public static void takeScreenshot(ShotCallback cb) {
        QwenPawA11yService svc = INSTANCE.get();
        if (svc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cb.onResult(null);
            return;
        }
        svc.takeScreenshot(1,
                svc.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                result.getHardwareBuffer(), result.getColorSpace());
                        if (bmp != null) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
                        cb.onResult(bmp);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        cb.onResult(null);
                    }
                });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE.set(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        INSTANCE.set(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // Not needed: the bridge polls the tree on demand.
    }

    @Override
    public void onInterrupt() {
    }
}