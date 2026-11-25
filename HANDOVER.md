# QwenPaw Android 移植项目 — 接手文档

最后更新：2026-11-26。本项目为开源维护版，欢迎社区继续。

---

## 1. 项目是什么

把开源项目 **QwenPaw**（agentscope-ai/QwenPaw，Linux 版 Python AI Agent 服务）整体移植到 Android：

- **服务端**：官方 Python 服务端跑在 **proot + Ubuntu 24.04 ARM64 rootfs** 里（内嵌 APK）
- **前端**：官方 fork 的 **React Native 原生界面**（apps/qwenpaw-mobile），替换早期 WebView 壳
- **首启**：Boot 进度页（深色主题）显示真实解压/启动进度，就绪后进主界面
- **能力**：语音转写（faster-whisper）、按住说话、局域网访问/改端口、设备控制桥（无障碍读屏/点击/截屏/启动应用）、Shizuku shell 提权、headless WebView 浏览器

**产品定位**：本机增强版 = 预装 proot 完整客户端 + 自研能力，连接锁死只连本机（`src/api/embedded.ts` 的 `EMBEDDED_AUTO_CONNECT=true` 使连接页 mode=self、隐藏手动地址/Platform/“再配对”入口；`src/app/(tabs)/me.tsx` 同步隐藏多配对行）。

---

## 2. 工程结构与构建

| 项 | 值 |
|---|---|
| 工程根 | 仓库根（勿移动——长路径会让 RN C++ 构建 ninja 崩溃） |
| RN 前端源码 | `src`（Expo SDK 57 / expo-router） |
| Android 工程 | `android`（Gradle 9.3.1 本地发行版） |
| Java 原生层 | `android/app/src/main/java/io/agentscope/qwenpaw/mobile/` |
| rootfs 打包源 | 外部构建（1.6GB Ubuntu ARM64，10 段 assets，被 .gitignore 排除） |
| 工具层资产 | `android/app/src/main/assets/tools.part`（212MB gzip tar：ffmpeg/adb/faster-whisper 模型，被 .gitignore 排除） |
| JDK | 17（Expo 强制） |
| 构建命令 | `cd android && GRADLE_USER_HOME=<你自己的 .gradle 目录> ./gradle-dist-9.3.1/bin/gradle.bat --console=plain --no-daemon assembleRelease` |
| APK 产物 | `android/app/build/outputs/apk/release/app-release.apk`（约 753MB，因内嵌 rootfs） |

**装机法**（无线 adb）：`adb push <apk> /data/local/tmp/x.apk && adb shell pm install -r /data/local/tmp/x.apk`。MSYS 会改路径，务必 `MSYS2_ARG_CONV_EXCL='*'` 前缀；release 包无法 run-as。

**注意**：所有 Java 字符串含 `\n` 时**不要用 Git Bash heredoc 写**（会变真实换行炸编译），用 Python 的 `chr(92)+'n'` 组装或编辑器直接改。

---

## 3. 核心能力（已完成并真机验证）

1. **RN 合体**：官方原生界面 + 内嵌 proot 服务端，真机验收通过
2. **Boot 页**：深色主题、进度自洽、失败重试按钮
3. **语音转写**：faster-whisper（base int8）+ ffmpeg/ffprobe 静态 + av.libs，`/api/workspace/local-whisper-status` 返回 available:true
4. **按住说话**：聊天页麦克风按钮，按住录（expo-audio）→ 松开自动上传转写
5. **局域网卡片**：工作台→连接与自动化→后台与局域网，开关+改端口+显示局域网 IP，改后自动重启服务
6. **设备控制桥**（`DeviceBridgeService`，端口 **18080**，仅 loopback）：
   - `/status` `/screen/tree` `/screen/text` `/screen/tap` `/screen/swipe` `/screen/longpress` `/screen/key` `/screen/screenshot` `/app/launch` `/app/list`（340+ 应用清单，支持 ?q= 过滤）`/app/install` `/app/uninstall` `/notification/list` `/shell/exec` `/shizuku/status`
   - 无障碍授权走系统设置（A11yService 注册名"QwenPaw 设备控制"）
   - **AI 出厂即知**：桥文档自动注入每个 workspace 的 `AGENTS.md`（v2 版本标记，可覆盖旧段），无需用户教学
7. **虚拟后台屏**：`/screen/vdisplay/create`（VirtualDisplay + ImageReader）实测创建成功，`/app/launch?display=N` 可把 app 投到虚拟屏；**必须 PUBLIC flag**（private 屏拿不到 WindowsForAccessibilityObserver，a11y 树读不到）
8. **Shizuku 虚拟屏闭环**（真机 Android 15 / HyperOS 验证通过）：
   - `curl 127.0.0.1:18080/shizuku/status` → `{"shizuku":true,"granted":true}`
   - `POST /screen/vdisplay/create` → `{"ok":true,"displayId":N}`
   - `POST /shell/exec` body=`am start --display N -n <pkg>/.Activity` → 虚拟屏起 app
   - `GET /screen/tree?display=N` → 完整 a11y 树；`/screen/screenshot?display=N` → PNG；`/screen/tap?display=N` → 手势注入生效
   - 关键坑：`vdisplay/create` 非幂等，重复调用产生新屏，先 `vdisplay/destroy`；Shizuku binder 用 `Shizuku.getBinder()`（Android 13+ 从 app 进程 `getSystemService("shizuku")` 返回 null）；子进程退出后管道关闭抛 Stream Closed → EOF 容忍 + stdout/stderr 双流
9. **headless WebView 浏览器**：`/browser/open` `/browser/text` `/browser/status` `/browser/eval` `/browser/close`，AI 上网不装 Playwright（WebView 必须主线程调用，/browser/status 用缓存 URL + 锁）
10. **返回键修复**：预测性返回回调 + JS 全局 BackHandler，任意界面返回不再退出桌面
11. **任务完成通知**：聊天流结束后若不在当前屏幕则弹本地通知（expo-notifications，无需 EAS）
12. **self-ADB**：`/shell/exec` 在 Shizuku 不可用时自动回退 self-ADB（无线调试自配对，AdbClient）

---

## 4. 关键架构与坑位

- **proot 全链路坑**：W^X → targetSdk 28、PROOT_LOADER、soname、aapt 解压 .gz、faster-whisper 三连环、RN Fabric UI 自动化三连坑
- **核心服务**：
  - `RuntimeService`：解压→restoreLinks/Executables→prepareNetwork→launchServer（proot 启动 Python）
  - `RuntimePrefs`：port/lan 用户设置
  - `DeviceBridgeService`：18080 桥（loopback HTTP，自愈 accept 循环）
  - `QwenPawA11yService`：无障碍多屏驱动
  - 工具层注入：`tools.part` gzip tar 解到 rootfs，`.tools-vN` stamp 版本号控制重解
- **gradle.properties 坑**：JVM 临时目录路径必须用正斜杠 `D:/tmp`（反斜杠 `\t` 被 properties 转义成制表符 → `ClassNotFoundException: mp` 的诡异构建失败）

---

## 5. 构建与验证备注

- 构建命令见 §2；首次构建需先生成 `tools.part` 与 rootfs assets（脚本与产物未入库，见 .gitignore）
- 真机验证需先停占用 CPU/内存的 VM（如 Podroid/QEMU，会把 qwenpaw 桥饿死）
- 无线调试端口每次断连都变，用 `adb mdns services` 找新端口
- 后续可做（低优先）：更多主题、通知增强、rootfs assets 的 CI 化构建