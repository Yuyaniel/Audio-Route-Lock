# Audio Route Lock（音频路由锁定）

面向 Android 15+ 的 LSPosed 模块。它可以把一个或多个作用域内应用锁定到指定输出设备（例如蓝牙耳机）；当该设备断开时，通过**静音**（把相关轨道/播放器音量压到 0，或对网页音频注入静音脚本）让目标应用保持无声，而播放本身照常继续——因此播放时钟、进度条都正常，不会卡顿或快进。浏览器（Via 等）的网页音频也覆盖，见[浏览器网页音频](#浏览器网页音频chromium--webview)。

界面使用 Jetpack Compose 与 [Miuix](https://github.com/YuKongA/miuix)（HyperOS/MIUI 风格）组件库编写，底部导航栏分三个页面：

- **首页**：MoonHook 风格状态卡片（模块运行正常 / 已激活但未注入 / 未加载 三态）+ 模块远程运行状态，下方是「运行概览」「锁定设置」卡片。
- **应用**：每个目标应用一张卡片，含图标、名称、包名、作用域/注入状态（已生效 / 未运行 / 待授权 / 待重启 / 待设置设备），以及**每个应用各自**的输出设备选择器（显示在行右侧）。移除应用需先勾选复选框并在弹窗中二次确认；长按应用图标/名称弹出「打开应用 / 强制重启」。右上角按钮刷新设备列表。
- **日志**：应用内运行日志，与首页「输出调试日志」开关联动，等宽字体展示，支持刷新/清空。

所有修改都会**自动保存**：切换开关、勾选应用、切换某应用的输出设备都会立即持久化并镜像给框架。新目标应用的作用域从应用页申请。

## 设计原理

蓝牙断开时 Android 会重新计算音频路由，应用级的 `setPreferredDevice` 并不是内核级的硬路由锁。所以模块组合了以下几种行为：

- 设备在线时，对 `AudioTrack` / `MediaPlayer` 调用 `setPreferredDevice(...)` 把输出锁定到目标设备。
- 设备缺失时，静音已记录的轨道/播放器（`setVolume(0)`），并钩住 `setVolume` 强制保持为 0，而不是吞掉 `write` 数据（吞数据会破坏 `AudioTrack` 播放时钟，导致卡顿 + 进度条快进）。
- 通过 `AudioDeviceCallback` 监听设备增删，断开时立即静音，而不是等到下一次 `write`/`start` 才处理。
- 浏览器等 Chromium 内核应用的网页音频不经过 Java 的 `AudioTrack` / `MediaPlayer`，改为在页面里静音，详见[浏览器网页音频](#浏览器网页音频chromium--webview)。

## 静音实现

- **静音手段**：Java 轨道/播放器用 `setVolume(0)`；网页音频注入**页面脚本**（元素 + Web Audio，见下）。都不丢弃音频数据，播放时钟与进度条不受影响。
- **触发时机**：设备增删回调 + 新对象创建时（构造函数钩子）+ `play()`/`start()` 兜底 + 页面加载完成。
- **保持静音**：钩 `setVolume` 在设备缺失期间把参数强制钳制为 0，防止应用自己恢复音量；页面侧由原型级音量写入口钳制 + 粘性事件监听保持。
- **恢复**：设备重新在线时，只恢复「被本模块静音过」的对象，音量回到 1。
- **开关语义**：「锁定设备不可用时静音」关闭时不会执行任何静音动作（Java 轨道/播放器与网页静音走的都是同一处判断）。
- **应用类型一致**：浏览器（Via 等）与其它应用判定相同——锁定设备在线就出声，离线就静音；区别只在静音手段。
- **省电**：播放热路径 `write()` 不挂钩子（零开销）；`AudioManager.getDevices()` 只在建轨道 / play / start / 设备变化 / 设置变化时调用；网页侧全部由事件驱动（进入静音 / 页面加载完成 / 新 WebView），没有任何定时器。

## 浏览器网页音频（Chromium / WebView）

Via、Chrome 这类 Chromium 内核应用的网页音频**不会**创建 Java 层的 `AudioTrack` / `MediaPlayer`：Chromium 的普通播放由 native AAudio / OpenSL ES 直接输出（Java `AudioTrack` 只在 HDMI 码流直通时才用，见 `media/audio/android/audio_manager_android.cc` 的 `MakeBitstreamOutputStream`）。所以：

- 只钩 `AudioTrack` / `MediaPlayer` 对网页音频完全无效；
- 把 WebView 提供方（`com.google.android.webview` 等）加进作用域也没有意义：音频输出发生在浏览器进程，也就是目标应用自身的进程里，与提供方进程无关。

模块在**页面层**接管（`WebViewAudioMuter`）：

- 把页面 `<audio>` / `<video>` 元素压成静音（播放继续，只是没有声音）；
- 拦截 Web Audio：包装 `AudioNode.prototype.connect`，把连向 `AudioContext.destination` 的连接改道经过一个增益为 0 的 GainNode；
- 用**原型级钳制**把 `HTMLMediaElement` 的 `volume`/`muted` 写入口堵死，并在 `play()` 之前先置静音：页面点「取消静音」也写不进有声状态，因此不会出现「页面写回音量 → native 吐出一小段」的十几毫秒漏音。
- 注入时机全部是事件：进入静音、`WebView` 构造、`loadUrl` / `setWebViewClient`、以及**页面加载完成**（钩应用 `WebViewClient.onPageFinished` / `WebChromeClient.onProgressChanged`，应用覆写时挂它的实现类，没覆写才挂基类）——跳转会换掉 document，靠这个事件补注入，而不是靠定时重注入。

**为什么不用别的路子**（都实测过，写在这里免得后人重走）：

- **Chromium 自己的 `setMute`**：入口是 `AudioManagerAndroidJni.setMute(nativePtr, muted)`。实测在真实 WebView 构建上不可用——R8 会把 `AudioManagerAndroidJni`（jni_zero 生成的胶水类）裁掉、把 `mNativeAudioManagerAndroid` 改名（日志：`AudioManagerAndroidJni 缺失: ClassNotFoundException`、字段变成 `long b`）；而且 `setMute` 本身只是 `startObservingVolumeChanges()` 里音量观察器在 `volume == 0` 时调用的内部入口。这条路已从代码里移除（备份见 `.codebuddy/backup-native-and-chromium-channel/`）。
- **AAudio / OpenSL ES 层的 native 钩子**：曾实现过（hook `AAudioStreamBuilder_setDataCallback` / `AAudioStream_write` / OpenSL ES 的 `Enqueue`，数据层清零、页面无感、覆盖跨域 iframe）。在本机实测native 能成功装载并挂钩，但 `AAudioStreamBuilder_setDataCallback` 从未被调用（Via 的播放没有走该回调路径），收益与维护成本不成比例，最终**已从代码里移除**；完整实现备份在 `.codebuddy/backup-native-and-chromium-channel/`（含 `arl_audio.cpp`、`CMakeLists.txt`、`NativeAudioMute.java` 与 Gradle 配置），将来若要覆盖跨域 iframe 可以从那里捡回来。
- **路由**：native 输出无法用 `setPreferredDevice` 指定设备，浏览器音频依赖系统自身的蓝牙路由；模块负责的是「锁定设备消失后不再从扬声器出声」——与其它应用是同一套语义。

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

工程不含 native 代码，也不需要 NDK / CMake 与第三方 hook 库——纯 Java + 页面脚本。

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
7. 浏览器（Via 等）单独验证：把浏览器加入目标并设置蓝牙耳机 → 重启浏览器 → 在网页里播放音频（如音乐站的 `<audio>`、视频站的 `<video>`）。**耳机在线时应有声**（走系统蓝牙路由），断开耳机后应保持无声（playback 继续、进度条照常走动），重新连上耳机后恢复。开启「输出调试日志」后，「日志」页应出现「已静音网页音频（锁定设备不在线）」，以及形如 `【网页静音】1|1|1|0` 的脚本返回值（已静音元素数/元素数/iframe 数/WebAudio 数）。
8. 排查时可在首页开启「输出调试日志」，在「日志」页查看，或按 `AudioRouteLock` 标签过滤 logcat。

## 当前限制

- 浏览器（Chromium / WebView）网页音频无法用 `setPreferredDevice` 指定输出设备，设备在线时依赖系统自身的蓝牙路由；模块保证的是「锁定设备离线时不出声」。
- 网页静音作用在页面层，覆盖不到两处：**跨域 iframe** 里的播放器、以及脚本注入前就已经连好线的 Web Audio 图（这两处只有 native 钩子能覆盖，见上）。
- 网页静音会改动页面的音量控件状态（播放器音量条会显示静音）；页面若自己反复改回音量，会被原型级钳制挡掉，但音量条的显示仍以页面自身逻辑为准。
- 其它自行使用 native AAudio / OpenSL ES 输出的应用（部分游戏、native 播放器）与 DRM / offload 直通路径不在覆盖范围内。
- 目标应用已在运行时启用/改动，仍需重启该应用；首页/应用页的状态会提示运行进程是否已加载当前模块代次。
- 某些 ROM 上设备地址可能为空或不稳定，地址匹配失败时可清空已存地址或改用设备别名策略。

## 功耗

模块只在目标应用进程内运行：不新增线程、不持 wakelock、不访问网络/传感器、没有常驻定时器、没有 native 代码。播放热路径 `write()` 不挂钩子，`getDevices()` 只在低频事件触发；网页侧只在「进入静音 / 页面加载完成 / 新建 WebView」这些事件上注入脚本或压一次元素，页面静止时完全没有动作。作为「兜底防蓝牙切回扬声器」的软件，平时几乎零开销，功耗可忽略。

## 日志

**只有开启首页的「输出调试日志」时，模块才会产生日志**：日志页的内容、跨进程写文件、以及 INFO 级的 logcat 输出全部受这个开关控制；关闭时 `appendToAppLog()` 直接返回，模块不写任何日志文件，也不会因为日志产生跨进程调用。

关闭该开关时，只有真正的异常（挂钩失败一类的 `Log.ERROR`）会留在 logcat，不写日志页、不落盘。
