# QwenPaw Mobile

Native Android and iOS client for QwenPaw, built with Expo and React Native.

> **This repository is the community-maintained enhanced Android port.**
> It embeds the full QwenPaw Python server via **proot + Ubuntu 24.04 ARM64 rootfs**
> directly into the APK, plus self-built capabilities: device control bridge
> (`127.0.0.1:18080` — a11y tree / tap / screenshot / app launch & install),
> Shizuku virtual-display closed loop, headless WebView browser, task-complete
> notifications, voice transcription (faster-whisper). The app is locked to its
> embedded local server only.
>
> The APK is ~750 MB because of the embedded rootfs and is not hosted on GitHub
> Releases (too large for free hosting) — build it with the commands in
> `HANDOVER.md`. The official upstream is agentscope-ai/QwenPaw.

## Requirements

- Node.js 22.13 or newer LTS
- Android Studio for Android builds
- Xcode 26.4 or newer for iOS builds

## Run

```bash
npm install --registry=https://registry.npmjs.org
npm run ios
npm run android
```

Use a physical device to test QR pairing. In QwenPaw Console, select
`Pair mobile` in the top bar and scan the displayed code.

## Checks

```bash
npm run typecheck
npm run lint
npm test
npx expo-doctor
```

The mobile app uses Expo SecureStore for credentials. Pairing tickets expire
after two minutes and are accepted only once.
