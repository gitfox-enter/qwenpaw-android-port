import { Network, RadioTower } from "lucide-react-native";
import { useCallback, useEffect, useState } from "react";
import {
  Clipboard,
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
 * "本地服务" — the server keeps listening on loopback by default. Flip the
 * LAN switch to bind 0.0.0.0 so any device on the same Wi-Fi can open the
 * Console in a browser. The page follows a form-style layout: edits are
 * staged locally and applied with the bottom "应用" button, no password is
 * shown anywhere.
 */
export function LanSettings() {
  const [server, setServer] = useState<LanState | null>(null);
  const [draft, setDraft] = useState<LanState | null>(null);
  const [saving, setSaving] = useState(false);
  const [portModal, setPortModal] = useState(false);
  const [portDraft, setPortDraft] = useState("");

  const load = useCallback(async () => {
    try {
      const status = await NativeModules.QwenPawRuntime.getLanStatus();
      setServer(status);
      setDraft(status);
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

  const apply = async () => {
    if (!draft) return;
    setSaving(true);
    try {
      await NativeModules.QwenPawRuntime.setLan(draft.enabled, draft.port);
      MobileAlert.alert("已保存", "服务正在重启，访问地址稍后生效");
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
    setDraft((prev) => (prev ? { ...prev, enabled: next } : prev));
  };

  const confirmPort = () => {
    const port = Number.parseInt(portDraft, 10);
    if (!draft || Number.isNaN(port) || port < 1024 || port > 65535) {
      MobileAlert.alert("端口无效", "请输入 1024–65535 之间的端口号");
      return;
    }
    setPortModal(false);
    setDraft((prev) => (prev ? { ...prev, port } : prev));
  };

  const copyUrl = (label: string, url: string) => {
    if (!url || url.startsWith("未检测到")) return;
    Clipboard.setString(url);
    MobileAlert.alert("已复制", `${label}：${url}`);
  };

  if (!server || !draft) return <ModuleLoading />;
  const localUrl = `http://127.0.0.1:${draft.port}`;
  const lanUrl = draft.ip && draft.ip !== "127.0.0.1"
    ? `http://${draft.ip}:${draft.port}`
    : "未检测到局域网地址";
  const changed =
    draft.enabled !== server.enabled || draft.port !== server.port;

  return (
    <View style={styles.wrap}>
      <Text maxFontSizeMultiplier={1.3} style={styles.pageTitle}>
        本地服务
      </Text>

      <IosGroup title="内置服务">
        <IosRow
          icon={RadioTower}
          iconTone="orange"
          label="本机地址"
          trailing={localUrl}
          onPress={() => copyUrl("本机地址", localUrl)}
        />
        <IosRow
          icon={draft.enabled ? RadioTower : Network}
          iconTone="ink"
          label="局域网地址"
          subtitle={
            draft.enabled
              ? "同一 Wi-Fi 下用浏览器打开，点击可复制"
              : "关闭时仅本机可访问"
          }
          trailing={lanUrl}
          onPress={() => copyUrl("局域网地址", lanUrl)}
          accessory={
            <Switch
              disabled={saving}
              onValueChange={(value) => toggleLan(value)}
              value={draft.enabled}
            />
          }
        />
      </IosGroup>

      <IosGroup title="端口">
        <IosRow
          icon={Network}
          label="服务端口"
          subtitle="修改后点击底部「应用」生效"
          trailing={String(draft.port)}
          onPress={() => {
            setPortDraft(String(draft.port));
            setPortModal(true);
          }}
        />
      </IosGroup>

      <Pressable
        disabled={saving || !changed}
        onPress={apply}
        style={({ pressed }) => [
          styles.apply,
          (!changed || saving) && styles.applyDisabled,
          pressed && styles.applyPressed,
        ]}
      >
        <Text style={styles.applyText}>
          {saving ? "应用中…" : "应用"}
        </Text>
      </Pressable>

      <ModuleFooter>
        开启局域网后，同一网络内的电脑/手机可以用上面的地址直接进入
        QwenPaw 网页版。修改端口或开关后点击「应用」，服务会重启一次（约 30
        秒）。
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
                <Text style={styles.cardButtonPrimaryText}>确定</Text>
              </Pressable>
            </View>
          </Pressable>
        </Pressable>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.md, paddingBottom: spacing.xxl },
  pageTitle: {
    color: colors.ink,
    fontSize: 26,
    fontWeight: "700",
    marginTop: spacing.sm,
  },
  apply: {
    borderRadius: radius.md,
    backgroundColor: colors.accent,
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: spacing.md + 2,
    marginTop: spacing.sm,
  },
  applyPressed: { opacity: 0.86 },
  applyDisabled: { backgroundColor: colors.muted },
  applyText: { color: "#FFFFFF", fontSize: 17, fontWeight: "600" },
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
  cardButtonPrimary: { backgroundColor: colors.accent },
  cardButtonText: { color: colors.ink, fontSize: 15 },
  cardButtonPrimaryText: { color: "#FFFFFF", fontSize: 15, fontWeight: "600" },
});
