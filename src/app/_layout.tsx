import { router, Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";
import { BackHandler } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";

import { MobileAlertHost } from "../components/MobileAlert";
import { MetroConnectionGuard } from "../dev/MetroConnectionGuard";
import { EmbeddedPlatformOAuthHost } from "../features/platform/EmbeddedPlatformOAuth";
import { openNotificationTarget } from "../notifications/navigation";
import { startNotificationNavigation } from "../notifications/runtime";
import { syncPushSubscription } from "../notifications/service";
import { useAppStore } from "../store/app";
import { ThemeProvider, useAppTheme } from "../theme/ThemeProvider";
import { colors } from "../theme/tokens";

export default function RootLayout() {
  return (
    <ThemeProvider>
      <AppNavigator />
    </ThemeProvider>
  );
}

function AppNavigator() {
  const bootstrap = useAppStore((state) => state.bootstrap);
  const status = useAppStore((state) => state.status);
  const connection = useAppStore((state) => state.connection);
  const { resolvedTheme } = useAppTheme();

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);

  useEffect(() => {
    if (status === "disconnected") router.replace("/");
  }, [status]);

  useEffect(() => {
    if (status !== "ready") return;
    let dispose: (() => void) | undefined;
    void startNotificationNavigation(openNotificationTarget).then((next) => {
      dispose = next;
    });
    return () => dispose?.();
  }, [status]);

  // Global hardware back handling. We always consume the back press so the
  // app never falls through to the native default (which would move the task
  // to the background / exit to the desktop). On an inner screen we pop the
  // navigation stack; at the root there is nothing to pop, so back is a
  // no-op and the user stays in the app.
  useEffect(() => {
    const subscription = BackHandler.addEventListener(
      "hardwareBackPress",
      () => {
        if (router.canGoBack()) {
          router.back();
        }
        return true;
      },
    );
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    if (status !== "ready" || !connection) return;
    void syncPushSubscription(connection).catch(() => undefined);
  }, [connection, status]);

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <StatusBar style={resolvedTheme === "dark" ? "light" : "dark"} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: colors.canvas },
          animation: "fade",
        }}
      />
      <EmbeddedPlatformOAuthHost />
      <MobileAlertHost />
      {__DEV__ ? <MetroConnectionGuard /> : null}
    </GestureHandlerRootView>
  );
}
