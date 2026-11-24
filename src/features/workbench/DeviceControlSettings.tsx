import {
  Accessibility,
  Clipboard,
  Hand,
  MonitorSmartphone,
  Zap,
} from "lucide-react-native";
import { useCallback, useEffect, useState } from "react";
import {
  Linking,
  PermissionsAndroid,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import type { Permission } from "react-native";

import { IosGroup, IosRow } from "../../components/IosList";
import { ModuleFooter, ModuleLoading } from "./ModuleState";
import { colors, radius, spacing } from "../../theme/tokens";

interface BridgeStatus {
  ok: boolean;
  connected?: boolean;
  screen?: string;
  error?: string;
}

interface ShizukuStatus {
  shizuku: boolean;
  granted: boolean;
}

const BRIDGE_URL = "http://127.0.0.1:18080";

const DANGEROUS_PERMISSIONS: Array<{ name: Permission; label: string }> = [
  { name: PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS, label: "通知" },
  { name: PermissionsAndroid.PERMISSIONS.RECORD_AUDIO, label: "麦克风" },
  { name: PermissionsAndroid.PERMISSIONS.CAMERA, label: "相机" },
  { name: PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES, label: "图片" },
  { name: PermissionsAndroid.PERMISSIONS.READ_MEDIA_AUDIO, label: "音频" },
  { name: PermissionsAndroid.PERMISSIONS.READ_MEDIA_VIDEO, label: "视频" },
];

/**
 * 设备控制 — the bridge hands the on-device agent native screen powers
 * (accessibility tree, gestures, screenshots, app launching) over loopback
 * HTTP, so no adb pairing or root is needed. The user only has to turn the
 * accessibility toggle on once in system settings.
 */
export function DeviceControlSettings() {
  const [state, setState] = useState<BridgeStatus | null>(null);
  const [checking, setChecking] = useState(false);
  const [shizuku, setShizuku] = useState<ShizukuStatus | null>(null);
  const [vdisplay, setVdisplay] = useState<{ active: boolean; displayId: number } | null>(
    null,
  );
  const [pendingPermissions, setPendingPermissions] = useState<string[]>([]);
  const [granting, setGranting] = useState(false);

  const loadBridge = useCallback(async () => {
    setChecking(true);
    try {
      const resp = await fetch(`${BRIDGE_URL}/status`, {
        method: "GET",
        headers: { Connection: "close" },
      });
      const data = (await resp.json()) as BridgeStatus;
      setState({ ...data, ok: resp.ok });
    } catch (reason) {
      setState({
        ok: false,
        error: reason instanceof Error ? reason.message : "桥服务未运行",
      });
    } finally {
      setChecking(false);
    }
  }, []);

  const loadShizuku = useCallback(async () => {
    try {
      const resp = await fetch(`${BRIDGE_URL}/shizuku/status`, {
        method: "GET",
        headers: { Connection: "close" },
      });
      setShizuku((await resp.json()) as ShizukuStatus);
    } catch {
      setShizuku({ shizuku: false, granted: false });
    }
  }, []);

  const loadVdisplay = useCallback(async () => {
    try {
      const resp = await fetch(`${BRIDGE_URL}/screen/vdisplay/status`, {
        method: "GET",
        headers: { Connection: "close" },
      });
      setVdisplay((await resp.json()) as { active: boolean; displayId: number });
    } catch {
      setVdisplay({ active: false, displayId: -1 });
    }
  }, []);

  const load = useCallback(async () => {
    await Promise.all([loadBridge(), loadShizuku(), loadVdisplay()]);
  }, [loadBridge, loadShizuku, loadVdisplay]);

  useEffect(() => {
    const task = setTimeout(() => void load(), 0);
    return () => clearTimeout(task);
  }, [load]);

  const checkPermissions = useCallback(async () => {
    const missing: string[] = [];
    for (const perm of DANGEROUS_PERMISSIONS) {
      const granted = await PermissionsAndroid.check(perm.name);
      if (!granted) missing.push(perm.label);
    }
    setPendingPermissions(missing);
  }, []);

  useEffect(() => {
    const task = setTimeout(() => void checkPermissions(), 0);
    return () => clearTimeout(task);
  }, [checkPermissions]);

  const requestPermissions = useCallback(async () => {
    setGranting(true);
    try {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
      );
      await Promise.all(
        DANGEROUS_PERMISSIONS.map((perm) =>
          PermissionsAndroid.request(perm.name).catch(() => false),
        ),
      );
      await checkPermissions();
    } finally {
      setGranting(false);
    }
  }, [checkPermissions]);

  const openA11ySettings = () => {
    Linking.openSettings();
  };

  const openShizuku = () => {
    // Try the Shizuku app's authorization page; fall back to the docs site.
    Linking.openURL("https://shizuku.rikka.app")
      .catch(() => Linking.openURL("https://shizuku.rikka.app/"));
  };

  if (!state) return <ModuleLoading />;

  const a11yOn = Boolean(state.connected);
  const shizukuGranted = Boolean(shizuku?.shizuku && shizuku?.granted);
  const vdisplayOn = Boolean(vdisplay?.active);

  return (
    <>
      {pendingPermissions.length > 0 ? (
        <View style={styles.permCard}>
          <View style={styles.permHead}>
            <Zap color={colors.accent} size={18} />
            <Text maxFontSizeMultiplier={1.3} style={styles.permTitle}>
              需要授权 {pendingPermissions.length} 项权限
            </Text>
          </View>
          <Text maxFontSizeMultiplier={1.3} style={styles.permBody}>
            首次使用需要：{pendingPermissions.join("、")}。点下方按钮一次性授权，AI 才能完整使用对应能力。
          </Text>
          <Pressable
            accessibilityRole="button"
            disabled={granting}
            onPress={() => void requestPermissions()}
            style={({ pressed }) => [styles.permBtn, pressed && styles.permPressed]}
          >
            <Text maxFontSizeMultiplier={1.3} style={styles.permBtnLabel}>
              {granting ? "授权中…" : "一键授权"}
            </Text>
          </Pressable>
        </View>
      ) : null}
      <IosGroup title="设备控制桥">
        <IosRow
          icon={MonitorSmartphone}
          iconTone={state.ok ? "orange" : "ink"}
          label="桥服务"
          subtitle={
            state.ok
              ? `运行中 · 端口 18080 · 屏幕 ${state.screen ?? ""}`
              : `未运行：${state.error ?? "未知原因"}`
          }
          trailing={state.ok ? "在线" : "离线"}
        />
        <IosRow
          icon={Accessibility}
          iconTone="ink"
          label="屏幕驱动（无障碍）"
          onPress={a11yOn ? undefined : openA11ySettings}
          subtitle={
            a11yOn
              ? "已授权 · AI 可读屏/点击/截屏"
              : "需要一次性授权，点此打开系统设置"
          }
          trailing={a11yOn ? "已开启" : "未开启"}
        />
        <IosRow
          icon={Zap}
          iconTone={shizukuGranted ? "orange" : "ink"}
          label="Shizuku 提权"
          onPress={shizukuGranted ? undefined : openShizuku}
          subtitle={
            shizukuGranted
              ? "已授权 · AI 可执行 shell 级命令（安装/输入/复杂操作）"
              : shizuku?.shizuku
                ? "Shizuku 已连接但未授权，点此前往授权"
                : "未连接 · 需安装 Shizuku App 并用无线调试激活"
          }
          trailing={
            shizukuGranted ? "已开启" : shizuku?.shizuku ? "待授权" : "未连接"
          }
        />
        <IosRow
          icon={MonitorSmartphone}
          iconTone={vdisplayOn ? "orange" : "ink"}
          label="虚拟后台屏"
          subtitle={
            vdisplayOn
              ? `运行中 · display ${vdisplay?.displayId} · AI 可后台驱动应用`
              : "未创建 · 对 AI 说「创建后台屏」即可启用"
          }
          trailing={vdisplayOn ? "已开启" : "未开启"}
        />
        <IosRow
          icon={Clipboard}
          iconTone="ink"
          label="能力清单"
          subtitle="读屏/点击/滑动/截屏、启动应用、输入文字、剪贴板、内置浏览器、后台屏"
        />
        <IosRow
          icon={Hand}
          iconTone="ink"
          label="如何使用"
          subtitle="对 AI 说「打开微信给我爸发条消息」即可；能力说明已注入 AGENTS.md，AI 出厂即知"
        />
      </IosGroup>
      <ModuleFooter>
        桥服务仅监听本机回环地址（127.0.0.1），不暴露到局域网。屏幕驱动基于系统无障碍服务，
        Shizuku 提供 shell 级提权，虚拟屏支持后台自动化，内置浏览器让 AI 直接上网——均已内置，无需额外安装。
      </ModuleFooter>
      {checking ? (
        <Text style={styles.checking}>检测中…</Text>
      ) : (
        <View style={styles.refreshRow}>
          <Text
            maxFontSizeMultiplier={1.3}
            onPress={() => void load()}
            style={styles.refresh}
          >
            重新检测
          </Text>
        </View>
      )}
    </>
  );
}

const styles = StyleSheet.create({
  checking: {
    color: colors.faint,
    fontSize: 12,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
  },
  refreshRow: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
  },
  refresh: {
    color: colors.accent,
    fontSize: 14,
    fontWeight: "600",
  },
  permCard: {
    backgroundColor: colors.surfaceSoft,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.accent,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.sm,
  },
  permHead: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.sm,
  },
  permTitle: {
    color: colors.ink,
    fontSize: 15,
    fontWeight: "600",
  },
  permBody: {
    color: colors.muted,
    fontSize: 13,
    lineHeight: 20,
  },
  permBtn: {
    alignSelf: "flex-start",
    backgroundColor: colors.accent,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderRadius: radius.sm,
  },
  permPressed: {
    opacity: 0.85,
  },
  permBtnLabel: {
    color: "#FFFFFF",
    fontSize: 14,
    fontWeight: "600",
  },
});