import { Accessibility, Hand, MonitorSmartphone } from "lucide-react-native";
import { useCallback, useEffect, useState } from "react";
import { Linking, StyleSheet, Text, View } from "react-native";

import { IosGroup, IosRow } from "../../components/IosList";
import { MobileAlert } from "../../components/MobileAlert";
import { ModuleFooter, ModuleLoading } from "./ModuleState";
import { colors, radius, spacing } from "../../theme/tokens";

interface BridgeStatus {
  ok: boolean;
  connected?: boolean;
  screen?: string;
  error?: string;
}

const BRIDGE_URL = "http://127.0.0.1:18080";

/**
 * 设备控制 — the bridge hands the on-device agent native screen powers
 * (accessibility tree, gestures, screenshots, app launching) over loopback
 * HTTP, so no adb pairing or root is needed. The user only has to turn the
 * accessibility toggle on once in system settings.
 */
export function DeviceControlSettings() {
  const [state, setState] = useState<BridgeStatus | null>(null);
  const [checking, setChecking] = useState(false);

  const load = useCallback(async () => {
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

  useEffect(() => {
    const task = setTimeout(() => void load(), 0);
    return () => clearTimeout(task);
  }, [load]);

  const openA11ySettings = () => {
    Linking.openSettings();
  };

  if (!state) return <ModuleLoading />;

  const a11yOn = Boolean(state.connected);
  return (
    <>
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
          icon={Hand}
          iconTone="ink"
          label="如何使用"
          subtitle="对 AI 说「打开微信给我爸发条消息」即可；能力说明在 /opt/qwenpaw/DEVICE_BRIDGE.md"
        />
      </IosGroup>
      <ModuleFooter>
        桥服务仅监听本机回环地址（127.0.0.1），不暴露到局域网。屏幕驱动基于系统无障碍服务，
        不需要 root、adb 或 Shizuku；后续版本将加入虚拟后台屏与 Shizuku 提权两种可选路线。
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
});