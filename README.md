# Audio Route Lock（音频路由锁定）

面向 Android 15+ 的 LSPosed 模块。它可以把一个或多个作用域内应用锁定到指定输出设备（例如蓝牙耳机）；当该设备断开时，通过**静音**（把相关轨道/播放器音量压到 0）让目标应用保持无声，而播放本身照常继续——因此播放时钟、进度条都正常，不会卡顿或快进。

界面使用 Jetpack Compose 与 [Miuix](https://github.com/YuKongA/miuix)（HyperOS/MIUI 风格）组件库编写，底部导航栏分三个页面：

- **首页**：MoonHook 风格状态卡片（模块运行正常 / 已激活但未注入 / 未加载 三态）+ 模块远程运行状态，下方是「运行概览」「锁定设置」卡片，以及「恢复默认设置」。
- **应用**：每个目标应用一张卡片，含图标、名称、包名、作用域/注入状态（已生效 / 未运行 / 待授权 / 待重启 / 待设置设备），以及**每个应用各自**的输出设备选择器（显示在行右侧）。移除应用需先勾选复选框并在弹窗中二次确认；长按应用图标/名称弹出「打开应用 / 强制重启」。右上角按钮刷新设备列表。
- **日志**：应用内运行日志，与首页「输出调试日志」开关联动，等宽字体展示，支持刷新/清空。

所有修改都会**自动保存**：切换开关、勾选应用、切换某应用的输出设备都会立即持久化并镜像给框架。新目标应用的作用域从应用页申请。

## 设计原理

蓝牙断开时 Android 会重新计算音频路由，应用级的 `setPreferredDevice` 并不是内核级的硬路由锁。所以模块组合了以下几种行为：

- 设备在线时，对 `AudioTrack` / `MediaPlayer` 调用 `setPreferredDevice(...)` 把输出锁定到目标设备。
- 设备缺失时，静音已记录的轨道/播放器（`setVolume(0)`），并钩住 `setVolume` 强制保持为 0，而不是吞掉 `write` 数据（吞数据会破坏 `AudioTrack` 播放时钟，导致卡顿 + 进度条快进）。
- 通过 `AudioDeviceCallback` 监听设备增删，断开时立即静音，而不是等到下一次 `write`/`start` 才处理。

## 静音实现

- **静音手段**：`setVolume(0)`（轨道/播放器层面），不丢弃音频数据。
- **触发时机**：设备增删回调 + 新对象创建时（构造函数钩子）+ `play()`/`start()` 兜底。
- **保持静音**：钩 `setVolume` 在设备缺失期间把参数强制钳制为 0，防止应用自己恢复音量。
- **恢复**：设备重新在线时，只恢复「被本模块静音过」的对象，音量回到 1。
- **省电**：播放热路径 `write()` 不挂钩子（零开销）；`AudioManager.getDevices()` 只在建轨道 / play / start / 设备变化 / 设置变化时调用。

## LSPosed 现代 API

本工程使用现代 libxposed API，遵循官方指南
[Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)：

- Java 入口：`app/src/main/resources/META-INF/xposed/java_init.list`
- 模块配置：`app/src/main/resources/META-INF/xposed/module.prop`（`minApiVersion`、`targetApiVersion`、`staticScope`、`autoHotReload`）
- 作用域种子：`app/src/main/resources/META-INF/xposed/scope.list`（为空：作用域在运行时申请）
- 模块名称/描述来自 `android:label` / `android:description` 资源
- 入口类：`dev.codex.audioroutelock.ModuleMain extends XposedModule`
- 跨进程设置：libxposed `RemotePreferences`

## 设置存储

模块 App 自身的 `SharedPreferences` 是唯一数据源，因此无论框架状态如何，设置都不会因重启丢失。只要 LSPosed 服务可用，`RouteSettingsStore` 会用同步 `commit()` 把设置镜像到框架侧 `RemotePreferences`（LSPosed 数据库），供注入进程读取；服务稍后连上时也会补推一次，所以「先保存、后连接」也没问题。

- 目标应用以列表存储（`target_packages`，`;` 分隔）。
- 每个目标应用各自保存输出设备（`device_map`，编码为 `package=type@name@address|...`）。
- 早期单应用、单设备的设置会在读取时自动迁移；应用只有在选定设备后才会被锁定。
- 首页「恢复默认设置」会清空本地与框架侧的全部设置与日志。

设备名显示规则：蓝牙设备显示「蓝牙 + 名称」（如「蓝牙 oppo enco air5pro」），其余设备显示中文类型名（内置扬声器、有线耳机、USB 耳机、HDMI 输出……），并过滤掉手机型号（如 rmx3800）这类无意义名称。

## 构建

工程面向 AGP 9，Kotlin 支持已内置：**不要**再应用 `org.jetbrains.kotlin.android`。通过根 `buildscript` classpath 提升 KGP 版本到 Miuix 所需的 2.3.21：

```kotlin
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}
```

依赖：

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")
implementation("io.github.libxposed:service:102.0.0")

implementation("androidx.activity:activity-compose:1.13.0")
implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.1")
implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.1")
implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.1")
```

Release 构建保留模块入口类：

```
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule { public <init>(); }
```

## 测试流程

1. 安装 APK 并在 LSPosed 中启用模块（本应用自身不能加入作用域）。
2. 打开应用，首页状态卡片应显示「模块运行正常」。
3. 切到「应用」，打开「选择目标应用」，搜索并勾选要锁定到耳机的应用。
4. 若出现「加入模块作用域」卡片则点击授权，然后重启目标应用。
5. 在「应用」页点开某个目标应用卡片内的设备行，选择蓝牙耳机（选择立即保存），该行状态变为「已生效」。
6. 播放媒体后断开耳机：播放应保持静音，而不是切回扬声器。
7. 排查时可在首页开启「输出调试日志」，在「日志」页查看，或按 `AudioRouteLock` 标签过滤 logcat。

## 当前限制

- 部分 DRM / 低延迟播放器走 native/offload 直通路径，可能需要额外钩子。
- 目标应用已在运行时启用/改动，仍需重启该应用；首页/应用页的状态会提示运行进程是否已加载当前模块代次。
- 某些 ROM 上设备地址可能为空或不稳定，地址匹配失败时可清空已存地址或改用设备别名策略。

## 功耗

模块只在目标应用进程内、事件驱动地运行：不新增线程、不持 wakelock、不轮询、不访问网络/传感器。播放热路径 `write()` 不挂钩子，`getDevices()` 只在低频事件触发。作为「兜底防蓝牙切回扬声器」的软件，平时几乎零开销，功耗可忽略。
