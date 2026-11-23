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
| adb | `D:\WorkBuddy\android-sdk\android-sdk\platform-tools\adb.exe`（设备走无线调试，serial 为 mDNS 名；旧路径 `lunatv-port` 已迁移失效） |

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

### ✅ P0：Shizuku 虚拟屏闭环 —— **已真机验证通过（2026-11-22）**
- 目的：Mika 方案要求虚拟屏启动 app 必须 shell 权限（普通 startActivity 会被系统拉回主屏，虚拟屏无内容）
- **真机验收结果（Android 15 / HyperOS，无线调试激活 Shizuku，v14 versionCode 14 / 1.0.1）**：
  1. `curl 127.0.0.1:18080/shizuku/status` → `{"shizuku":true,"granted":true}` ✅
  2. `POST /screen/vdisplay/create` → `{"ok":true,"displayId":29}` ✅
  3. `POST /shell/exec` body=`am start --display 29 -n com.android.settings/.Settings` → `{"exit":0,"output":"Starting: Intent..."}` ✅
  4. `GET /screen/tree?display=29` → 完整设置界面 a11y 树 ✅；`GET /screen/screenshot?display=29` → 设置页 PNG ✅
  5. `POST /screen/tap?display=29&x=..&y=..` → 手势注入生效，界面真实跳转（设置→我的设备）✅
- **修复清单（相对 v4-shizuku 版）**：
  - `shizukuExec` binder：`SystemServiceHelper.getSystemService("shizuku")` 在 Android 13+ 从 app 进程返回 null → 改 `Shizuku.getBinder()`
  - 流读取：子进程退出后管道关闭抛 `IOException: Stream Closed` → EOF 容忍 + stdout/stderr 双流 + 真实 exit code
  - **虚拟屏必须 PUBLIC**：app uid 的 private 虚拟屏拿不到 `WindowsForAccessibilityObserver`，a11y 树读不到 → `createVirtualDisplay` 加 `VIRTUAL_DISPLAY_FLAG_PUBLIC`（对齐两仪 twin 屏配方）
  - `dumpTree` 跨 display：改用 API 30+ `getWindowsOnAllDisplays()`
- 备注：`vdisplay/create` 非幂等，重复调用产生新屏，先 `vdisplay/destroy`
- 参考：两仪（Twoyi）用 **shell uid (2000) host 进程**（app_process64）建 `twoyi-twin`（TRUSTED/OWN_DISPLAY_GROUP/OWN_FOCUS/ALWAYS_UNLOCKED/STEAL_TOP_FOCUS_DISABLED，touch VIRTUAL，1240x2444@520）；我们 app-uid PUBLIC 屏已达成同等能力（tree/tap/screenshot 全通）

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
> **产品定位（2026-11-23 定）**：只维护本机增强版，**不做官方纯净版**（官方以后会自己做移动端）。增强版 = 预装 proot 完整客户端 + 自研能力（设备桥/局域网/headless 浏览器），**锁死只连本机**：`src/api/embedded.ts` 的 `EMBEDDED_AUTO_CONNECT=true` 使连接页 mode=self、隐藏手动地址/Platform/“再配对”入口；`me.tsx` 同步隐藏多配对行。
>
> **v15（versionCode 15 / 1.0.2）已构建装机**，核心能力：
> 1. 虚拟屏闭环：`/shizuku/status` → `/screen/vdisplay/create` → `/shell/exec "am start --display N"` → `/screen/tree?display=N` → tap → screenshot，PUBLIC flag 是关键（private 屏 a11y 看不到）
> 2. `/app/list`：338 个应用清单（agent 先查再启动，不猜包名）
> 3. `/browser/*`：headless WebView（open/text/eval/close），AI 上网不装 Playwright
> 4. UI 三修：外观页安全区、键盘 padding、predictiveBackGesture=false
> 5. AGENTS.md 注入段 v2 版本标记（旧段可被替换，workspace 懒建已修）
>
> 构建命令：`cd /d/qpmob/android && TMPDIR=/d/tmp GRADLE_USER_HOME='D:\GradleHome\.gradle' ./gradle-dist-9.3.1/bin/gradle.bat --no-daemon assembleRelease`（**gradle.properties 里路径必须正斜杠**，反斜杠 `\t` 转义会炸 daemon）
> 真机验证需先停 Podroid（QEMU TCG 吃 4GB 内存+120% CPU，会把 qwenpaw 桥饿死）；无线调试端口每次断连都变，用 `adb mdns services` 找新端口。
> 后续（低优先）：更多主题、任务完成通知。