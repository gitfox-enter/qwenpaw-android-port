# QwenPaw Android 移植项目 — 接手文档

最后更新：2026-11-22。上一任执行者：AI（ZCode 会话），与用户（需求方/真机操作方）协作完成。

---

## 1. 项目是什么

把开源项目 **QwenPaw**（agentscope-ai/QwenPaw，Linux 版 Python AI Agent 服务）整体移植到 Android：

- **服务端**：官方 Python 服务端跑在 **proot + Ubuntu 24.04 ARM64 rootfs** 里（内嵌 APK）
- **前端**：官方 fork 的 **React Native 原生界面**（apps/qwenpaw-mobile），替换早期 WebView 壳
- **首启**：Boot 进度页（深色主题）显示真实解压/启动进度，就绪后进主界面
- **能力**：语音转写（faster-whisper）、按住说话、局域网访问/改端口、设备控制桥（无障碍读屏/点击/截屏/启动应用）、Shizuku shell 提权（进行中）

真机已验证可日常使用：AI 聊天、讯飞 MaaS 模型端到端、语音转写全绿、设备控制桥在线。

---

## 2. 工程结构与构建

| 项 | 值 |
|---|---|
| 工程根 | `D:\qpmob`（**物理短路径**，勿移动——长路径会让 RN C++ 构建 ninja 崩溃） |
| RN 前端源码 | `D:\qpmob\src`（Expo SDK 57 / expo-router） |
| Android 工程 | `D:\qpmob\android`（Gradle 9.3.1 本地发行版） |
| Java 原生层 | `D:\qpmob\android\app\src\main\java\io\agentscope\qwenpaw\mobile\` |
| rootfs 打包源 | `D:\WorkBuddy\qwenpaw-android\build\rootfs\`（1.6GB Ubuntu ARM64，10 段 assets） |
| 工具层资产 | `android\app\src\main\assets\tools.part`（212MB gzip tar：ffmpeg/adb/faster-whisper 模型） |
| JDK | `D:\WorkBuddy\jdk-17.0.21+9`（Expo 强制 17） |
| Gradle 用户目录 | `D:\GradleHome\.gradle`（C 盘满的迁移成果，勿动） |
| 构建命令 | `cd /d/qpmob/android && GRADLE_USER_HOME='D:\GradleHome\.gradle' ./gradle-dist-9.3.1/bin/gradle.bat --console=plain --no-daemon assembleRelease` |
| APK 产物 | `android\app\build\outputs\apk\release\app-release.apk`（约 753MB） |
| adb | `D:\WorkBuddy\lunatv-port\tools\android-sdk\platform-tools\adb.exe`（设备走无线调试，serial 为 mDNS 名） |

**装机法**（无线 adb）：`adb push <apk> /data/local/tmp/x.apk && adb shell pm install -r /data/local/tmp/x.apk`。MSYS 会改路径，务必 `MSYS2_ARG_CONV_EXCL='*'` 前缀；release 包无法 run-as。

**注意**：所有 Java 字符串含 `\n` 时**不要用 Git Bash heredoc 写**（会变真实换行炸编译），用 Python 的 `chr(92)+'n'` 组装或 Edit 工具。

---

## 3. 已完成并验证

1. **RN 合体**：官方原生界面 + 内嵌 proot 服务端，真机验收通过（用户确认满意）
2. **Boot 页**：深色主题、进度自洽（"%·第 N/5 步"）、失败重试按钮
3. **语音转写**：faster-whisper（base int8）+ ffmpeg/ffprobe 静态 + av.libs，`/api/workspace/local-whisper-status` 返回 available:true
4. **按住说话**：聊天页麦克风按钮，按住录（expo-audio）→ 松开自动上传转写（RECORD_AUDIO 已加）
5. **局域网卡片**：工作台→连接与自动化→后台与局域网，开关+改端口+显示局域网 IP，改后自动重启服务
6. **设备控制桥**（`DeviceBridgeService`，端口 **18080**，仅 loopback）：
   - `/status` `/screen/tree` `/screen/tap` `/screen/swipe` `/screen/screenshot` `/app/launch`
   - 无障碍授权走系统设置（A11yService 注册名"QwenPaw 设备控制"）
   - **AI 出厂即知**：桥文档自动注入每个 workspace 的 `AGENTS.md`（system prompt 拼接），无需用户教学
7. **虚拟后台屏**：`/screen/vdisplay/create`（VirtualDisplay + ImageReader）实测创建成功返回 displayId（21），`/app/launch?display=N` 可把 app 投到虚拟屏
8. **C 盘治理**：npm/pip 缓存迁 D 盘，临时目录清理（`network-android-env-tips.md` 有详情）

---

## 4. 未完成任务（按优先级）

### 🔴 P0：Shizuku `/shell/exec` 端点真机验证（**编译已完成，2026-11-22 build48 SUCCESS**）
- 目的：Mika 方案要求虚拟屏启动 app 必须 shell 权限（普通 startActivity 会被系统拉回主屏，虚拟屏无内容）
- 现状：`DeviceBridgeService.shizukuExec()` 用 `IShizukuService.newProcess`（返回 IRemoteProcess）→ `getInputStream()` 返回 ParcelFileDescriptor → FileInputStream 读输出。**编译已通过，APK 已生成**（`D:\Movies\Download\QwenPaw-Mobile-RN-v4-shizuku.apk`，753MB），但设备离线未装机，真机行为未验证
- 接入点：`app/build.gradle` 已加 `dev.rikka.shizuku:api/provider:13.1.5`；Manifest 已注册 ShizukuProvider
- 验证剧本：
  1. 手机装 **Shizuku App**（酷安/官网），adb 激活或无线调试激活
  2. 装 v4 APK 后 `curl 127.0.0.1:18080/shizuku/status` → granted:true
  3. `curl -X POST -d 'am start --display 21 -n com.chinamworld.main/.MainActivity' .../shell/exec`
  4. `curl ".../screen/tree?display=21"` 应见中国移动界面；`/screen/screenshot?display=21` 应有图
- 成功后把虚拟屏用法写进 AGENTS.md 注入段（RuntimeService.writeDeviceBridgeGuide 已含 vdisplay 段）
- 注意：shizukuExec 是 20 秒超时轮询读 pty，长命令会被截断；后续可改 IRemoteProcess.waitFor 版本

### 🟡 P1：虚拟屏端到端验证
- 无障碍树跨 display 需要 A11yService `getWindows()` 过滤 displayId（已实现，未真机验证）
- ImageReader 截帧已通（display=21 实测返回 JSON ok，帧捕获待有 app 在屏后验证）
- 用户在手机上**没有安装 Shizuku App**——需引导

### 🟢 P2：新版本装机+评审
- 每次大改后按用户常驻流程：装机 → 截图 → 挑剔 PM 子 Agent 评审 → 改到只剩小问题

---

## 5. 关键架构与坑位（务必读）

详见记忆文件（ZCode 会话里自动加载）：
- `qwenpaw-android-port.md` / `android-proot-integration-pitfalls.md`：proot 全链路坑（W^X→targetSdk 28、PROOT_LOADER、soname、aapt 解压 .gz、faster-whisper 三连环、RN Fabric UI 自动化三连坑）
- `operit-ai-reference.md`：Zafiro/Mika/Twoyi 三参考判定（adb 用 Ubuntu deb 闭包非 Termux bionic；虚拟屏走原生 VirtualDisplay 不做容器）
- `network-android-env-tips.md`：镜像、C 盘治理、无线 adb 重连

**核心服务**：
- `RuntimeService`：解压→restoreLinks/Executables→prepareNetwork→launchServer（proot 启动 Python）
- `RuntimePrefs`：port/lan 用户设置（局域网卡片直接读写）
- `DeviceBridgeService`：18080 桥（loopback HTTP，自愈 accept 循环）
- `QwenPawA11yService`：无障碍多屏驱动
- 工具层注入：`tools.part` gzip tar 解到 rootfs，`.tools-vN` stamp 版本号控制重解

---

## 6. 提示词（给下一位接手者）

> 你是 QwenPaw Android 移植项目的接手开发者。工程在 D:\qpmob（勿移动，长路径会炸 ninja），构建命令与全部背景见 D:\qpmob\HANDOVER.md 及 ZCode 记忆索引（qwenpaw-android-port / android-proot-integration-pitfalls / operit-ai-reference / qwenpaw-device-bridge）。
>
> 当前唯一进行中的任务：完成 Shizuku 虚拟屏提权闭环。步骤：
> 1) 确认 `DeviceBridgeService.java` 的 shizukuExec 已用 `IShizukuService.newProcess` + `IRemoteProcess.getInputStream` 并编译通过（GRADLE_USER_HOME='D:\GradleHome\.gradle' ./gradle-dist-9.3.1/bin/gradle.bat assembleRelease）
> 2) 装到真机（adb push + pm install -r，MSYS2_ARG_CONV_EXCL='*' 前缀），引导用户装 Shizuku App 并授权
> 3) 验证链路：/shizuku/status → /screen/vdisplay/create → /shell/exec "am start --display N -n <pkg>" → /screen/tree?display=N
> 4) 通过后把虚拟屏用法写进 RuntimeService 的 AGENTS.md 注入段，重打 APK
> 不要开展新功能；完成后按用户常驻流程截图+挑剔 PM 评审。