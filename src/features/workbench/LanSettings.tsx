import { Network, RadioTower, Unplug } from "lucide-react-native";
import { useCallback, useEffect, useState } from "react";
import {
  Modal,
  NativeModules,
  Pressable,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";

import { IosGroup, IosRow } from "../../components/IosList";
import { MobileAlert } from "../../components/MobileAlert";
import { ModuleFooter, ModuleLoading } from "./ModuleState";
import { colors, radius, spacing } from "../../theme/tokens";

interface LanState {
  enabled: boolean;
  port: number;
  ip: string;
}

/**
 * "后台与局域网" — the server keeps listening on loopback only unless the
 * user flips the LAN switch, which makes it bind 0.0.0.0 so any device on
 * the same Wi-Fi can open the Console in a browser. Changing the port or
 * the switch bounces RuntimeService with the new prefs.
 */
export function LanSettings() {
  const [state, setState] = useState<LanState | null>(null);
  const [saving, setSaving] = useState(false);
  const [portModal, setPortModal] = useState(false);
  const [portDraft, setPortDraft] = useState("");

  const load = useCallback(async () => {
    try {
      const status = await NativeModules.QwenPawRuntime.getLanStatus();
      setState(status);
    } catch (reason) {
      MobileAlert.alert(
        "读取失败",
        reason instanceof Error ? reason.message : "无法读取局域网设置",
      );
    }
  }, []);

  useEffect(() => {
    const task = setTimeout(() => void load(), 0);
    return () => clearTimeout(task);
  }, [load]);

  const save = async (enabled: boolean, port: number) => {
    setSaving(true);
    try {
      await NativeModules.QwenPawRuntime.setLan(enabled, port);
      MobileAlert.alert("已保存", "服务正在重启，局域网地址稍后生效");
      setTimeout(() => void load(), 2500);
    } catch (reason) {
      MobileAlert.alert(
        "保存失败",
        reason instanceof Error ? reason.message : "无法写入设置",
      );
    } finally {
      setSaving(false);
    }
  };

  const toggleLan = (next: boolean) => {
    if (!state) return;
    if (!next) {
      void save(false, state.port);
      return;
    }
    if (state.ip === "127.0.0.1") {
      MobileAlert.alert(
        "未检测到局域网地址",
        "请确认手机已连接 Wi-Fi，然后重试。",
      );
      return;
    }
    void save(true, state.port);
  };

  const confirmPort = () => {
    const port = Number.parseInt(portDraft, 10);
    if (!state || Number.isNaN(port) || port < 1024 || port > 65535) {
      MobileAlert.alert("端口无效", "请输入 1024–65535 之间的端口号");
      return;
    }
    setPortModal(false);
    void save(state.enabled, port);
  };

  if (!state) return <ModuleLoading />;
  const accessUrl = state.enabled
    ? `http://${state.ip}:${state.port}`
    : `http://127.0.0.1:${state.port}`;

  return (
    <>
      <IosGroup title="后台与局域网">
        <IosRow
          accessory={
            <Switch
              disabled={saving}
              onValueChange={(value) => toggleLan(value)}
              value={state.enabled}
            />
          }
          icon={RadioTower}
          iconTone="orange"
          label="局域网访问"
          subtitle={
            state.enabled
              ? "其他设备可通过 Wi-Fi 打开网页版"
              : "仅本机可访问（默认）"
          }
        />
        <IosRow
          icon={Network}
          label="服务端口"
          onPress={() => {
            setPortDraft(String(state.port));
            setPortModal(true);
          }}
          subtitle="修改后服务会自动重启"
          trailing={String(state.port)}
        />
        <IosRow
          icon={state.enabled ? RadioTower : Unplug}
          iconTone="ink"
          label="访问地址"
          subtitle="同一 Wi-Fi 下用浏览器打开"
          trailing={accessUrl}
        />
      </IosGroup>
      <ModuleFooter>
        开启局域网后，同一网络内的电脑/手机可以用上面的地址直接进入 QwenPaw 网页
        版。修改端口或开关会重启一次服务（约 30 秒）。
      </ModuleFooter>

      <Modal
        animationType="fade"
        onRequestClose={() => setPortModal(false)}
        transparent
        visible={portModal}
      >
        <Pressable
          onPress={() => setPortModal(false)}
          style={styles.backdrop}
        >
          <Pressable onPress={() => undefined} style={styles.card}>
            <Text maxFontSizeMultiplier={1.4} style={styles.cardTitle}>
              服务端口
            </Text>
            <TextInput
              autoFocus
              keyboardType="number-pad"
              onChangeText={setPortDraft}
              onSubmitEditing={confirmPort}
              placeholder="1024 – 65535"
              selectTextOnFocus
              style={styles.input}
              value={portDraft}
            />
            <View style={styles.cardActions}>
              <Pressable
                onPress={() => setPortModal(false)}
                style={styles.cardButton}
              >
                <Text style={styles.cardButtonText}>取消</Text>
              </Pressable>
              <Pressable
                onPress={confirmPort}
                style={[styles.cardButton, styles.cardButtonPrimary]}
              >
                <Text style={styles.cardButtonPrimaryText}>保存</Text>
              </Pressable>
            </View>
          </Pressable>
        </Pressable>
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    backgroundColor: "rgba(0,0,0,0.45)",
    flex: 1,
    justifyContent: "center",
    paddingHorizontal: spacing.lg,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
  },
  cardTitle: {
    color: colors.ink,
    fontSize: 17,
    fontWeight: "700",
    marginBottom: spacing.md,
  },
  input: {
    backgroundColor: colors.searchBackground,
    borderColor: colors.line,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    color: colors.ink,
    fontSize: 16,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  cardActions: {
    flexDirection: "row",
    gap: spacing.md,
    justifyContent: "flex-end",
    marginTop: spacing.lg,
  },
  cardButton: {
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
  },
  cardButtonPrimary: {
    backgroundColor: colors.accent,
  },
  cardButtonText: {
    color: colors.ink,
    fontSize: 15,
  },
  cardButtonPrimaryText: {
    color: "#FFFFFF",
    fontSize: 15,
    fontWeight: "600",
  },
});