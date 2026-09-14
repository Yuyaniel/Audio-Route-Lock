package dev.codex.audioroutelock

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.Collator
import java.util.Locale

private const val ICON_BITMAP_SIZE = 96

private enum class AppTab { Home, Apps, Log }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }) {
                AudioRouteLockApp()
            }
        }
    }
}

private data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
)

private data class DeviceEntry(
    val device: AudioDeviceInfo,
    val label: String,
)

private data class AppState(
    val label: String,
    val active: Boolean,
)

@Composable
private fun AudioRouteLockApp() {
    val context = LocalContext.current

    val initial = remember { RouteSettingsStore.load(context) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var muteWhenMissing by remember { mutableStateOf(initial.muteWhenMissing) }
    var debugLog by remember { mutableStateOf(initial.debug) }
    var targets by remember { mutableStateOf(initial.targetPackages.toList()) }
    var deviceMap by remember { mutableStateOf(initial.deviceMap) }
    var devices by remember { mutableStateOf(loadOutputDevices(context)) }

    var connected by remember { mutableStateOf(false) }
    var remoteAvailable by remember { mutableStateOf(false) }
    var scopePackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadedProcesses by remember { mutableStateOf<List<String>>(emptyList()) }
    var staleProcesses by remember { mutableStateOf<List<String>>(emptyList()) }

    var tab by remember { mutableStateOf(AppTab.Home) }
    var picking by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var appActionsTarget by remember { mutableStateOf<String?>(null) }
    var pendingReset by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    fun refreshStatus() {
        val service = App.getXposedService()
        if (service == null) {
            connected = false
            remoteAvailable = false
            scopePackages = emptySet()
            loadedProcesses = emptyList()
            staleProcesses = emptyList()
            return
        }

        connected = true
        remoteAvailable = try {
            (service.getFrameworkProperties() and XposedService.PROP_CAP_REMOTE) != 0L
        } catch (t: Throwable) {
            false
        }
        scopePackages = try {
            service.getScope().toSet()
        } catch (t: Throwable) {
            emptySet()
        }

        val loaded = mutableListOf<String>()
        val stale = mutableListOf<String>()
        try {
            for (target in service.getRunningTargets()) {
                val process = target.getProcessName() ?: continue
                loaded.add(process)
                if (target.getState() != HookedTarget.State.UP_TO_DATE) {
                    stale.add(process)
                }
            }
        } catch (ignored: Throwable) {
            // 框架不支持查询运行中的目标时忽略
        }
        loadedProcesses = loaded
        staleProcesses = stale
    }

    fun persist() {
        RouteSettingsStore.save(
            context,
            RouteSettings(enabled, targets, deviceMap, muteWhenMissing, debugLog),
        )
    }

    fun resetAll() {
        RouteSettingsStore.reset(context)
        enabled = false
        muteWhenMissing = true
        debugLog = false
        targets = emptyList()
        deviceMap = emptyMap()
        devices = loadOutputDevices(context)
        pendingReset = false
        refreshStatus()
        AppLog.append(context, "已恢复默认设置")
        Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) {
        val listener = Runnable { Handler(Looper.getMainLooper()).post { refreshStatus() } }
        App.addServiceListener(listener)
        onDispose { App.removeServiceListener(listener) }
    }

    LaunchedEffect(tab, picking) {
        if (!picking) {
            refreshStatus()
            if (tab == AppTab.Log) {
                logText = AppLog.read(context)
            }
        }
    }

    LaunchedEffect(picking) {
        if (picking && apps == null) {
            apps = withContext(Dispatchers.Default) { loadInstalledApps(context) }
        }
    }

    if (picking) {
        BackHandler { picking = false }
        AppPickerScreen(
            apps = apps,
            selected = targets,
            onToggle = { packageName ->
                if (targets.contains(packageName)) {
                    targets = targets.filterNot { it == packageName }
                    deviceMap = deviceMap - packageName
                } else {
                    // 新加入的应用继承已有应用配置的设备，没有配置则留空待设置。
                    val inherited = targets.firstNotNullOfOrNull { deviceMap[it] }
                    targets = targets + packageName
                    if (inherited != null) {
                        deviceMap = deviceMap + (packageName to inherited)
                    }
                }
                persist()
            },
            onBack = {
                picking = false
                refreshStatus()
            },
        )
        return
    }

    val configuredCount = targets.count { deviceMap.containsKey(it) }
    val effectiveCount = targets.count {
        appState(context, it, deviceMap, scopePackages, loadedProcesses, staleProcesses).active
    }
    val pendingScope = targets.filterNot { scopePackages.contains(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = when (tab) {
                    AppTab.Home -> stringResource(R.string.app_name)
                    AppTab.Apps -> stringResource(R.string.tab_apps)
                    AppTab.Log -> stringResource(R.string.tab_log)
                },
                actions = {
                    IconButton(onClick = {
                        devices = loadOutputDevices(context)
                        AppLog.append(context, "已刷新输出设备列表，共 ${devices.size} 个")
                    }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.refresh_devices),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.Home,
                    onClick = { tab = AppTab.Home },
                    icon = MiuixIcons.Info,
                    label = stringResource(R.string.tab_home),
                )
                NavigationBarItem(
                    selected = tab == AppTab.Apps,
                    onClick = { tab = AppTab.Apps },
                    icon = MiuixIcons.GridView,
                    label = stringResource(R.string.tab_apps),
                )
                NavigationBarItem(
                    selected = tab == AppTab.Log,
                    onClick = { tab = AppTab.Log },
                    icon = MiuixIcons.Notes,
                    label = stringResource(R.string.tab_log),
                )
            }
        },
    ) { padding ->
        when (tab) {
            AppTab.Home -> HomeTab(
                modifier = Modifier.fillMaxSize().padding(padding),
                connected = connected,
                remoteAvailable = remoteAvailable,
                enabled = enabled,
                muteWhenMissing = muteWhenMissing,
                debugLog = debugLog,
                targetCount = targets.size,
                effectiveCount = effectiveCount,
                configuredCount = configuredCount,
                onEnabledChange = {
                    enabled = it
                    persist()
                },
                onMuteChange = {
                    muteWhenMissing = it
                    persist()
                },
                onDebugChange = {
                    debugLog = it
                    persist()
                },
                onResetRequest = { pendingReset = true },
            )

            AppTab.Apps -> AppsTab(
                modifier = Modifier.fillMaxSize().padding(padding),
                devices = devices,
                targets = targets,
                deviceMap = deviceMap,
                scopePackages = scopePackages,
                loadedProcesses = loadedProcesses,
                staleProcesses = staleProcesses,
                pendingScopeCount = pendingScope.size,
                onPickApps = { picking = true },
                onRequestScope = { requestScope(context, pendingScope) { refreshStatus() } },
                onRemoveRequest = { pendingRemoval = it },
                onAppActionsRequest = { appActionsTarget = it },
                onDeviceSelected = { packageName, entry ->
                    deviceMap = deviceMap + (packageName to entry.toDeviceRef())
                    persist()
                },
            )

            AppTab.Log -> LogTab(
                modifier = Modifier.fillMaxSize().padding(padding),
                debugEnabled = debugLog,
                logText = logText,
                onRefresh = { logText = AppLog.read(context) },
                onClear = {
                    AppLog.clear(context)
                    logText = ""
                },
            )
        }

        pendingRemoval?.let { packageName ->
            OverlayDialog(
                show = true,
                title = stringResource(R.string.remove_app_title),
                summary = stringResource(R.string.remove_app_message, resolveAppLabel(context, packageName)),
                onDismissRequest = { pendingRemoval = null },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { pendingRemoval = null },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            targets = targets.filterNot { it == packageName }
                            deviceMap = deviceMap - packageName
                            persist()
                            pendingRemoval = null
                            refreshStatus()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }

        appActionsTarget?.let { packageName ->
            OverlayDialog(
                show = true,
                title = resolveAppLabel(context, packageName),
                onDismissRequest = { appActionsTarget = null },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            openApp(context, packageName)
                            appActionsTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.app_action_open))
                    }
                    TextButton(
                        text = stringResource(R.string.app_action_force_restart),
                        onClick = {
                            appActionsTarget = null
                            forceRestart(context, packageName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (pendingReset) {
            OverlayDialog(
                show = true,
                title = stringResource(R.string.reset_settings),
                summary = stringResource(R.string.reset_settings_message),
                onDismissRequest = { pendingReset = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { pendingReset = false },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { resetAll() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTab(
    modifier: Modifier,
    connected: Boolean,
    remoteAvailable: Boolean,
    enabled: Boolean,
    muteWhenMissing: Boolean,
    debugLog: Boolean,
    targetCount: Int,
    effectiveCount: Int,
    configuredCount: Int,
    onEnabledChange: (Boolean) -> Unit,
    onMuteChange: (Boolean) -> Unit,
    onDebugChange: (Boolean) -> Unit,
    onResetRequest: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        when {
            !connected -> StatusCard(
                container = Color(0xFFFDEAEA),
                circle = Color(0xFFF9D2D2),
                accent = Color(0xFFDC2626),
                icon = "✕",
                title = stringResource(R.string.module_status_error),
                subtitle = stringResource(R.string.module_status_error_sub),
            )

            effectiveCount > 0 -> StatusCard(
                container = Color(0xFFE7F6EC),
                circle = Color(0xFFCDEEDB),
                accent = Color(0xFF15803D),
                icon = "✓",
                title = stringResource(R.string.module_status_normal),
                subtitle = stringResource(R.string.module_status_loaded_sub),
            )

            else -> StatusCard(
                container = Color(0xFFFFF6DF),
                circle = Color(0xFFFFEDB8),
                accent = Color(0xFFB45309),
                icon = "!",
                title = stringResource(R.string.module_status_warning),
                subtitle = stringResource(R.string.module_status_warning_sub),
            )
        }

        StatusCard(
            container = if (remoteAvailable) Color(0xFFE7F6EC) else Color(0xFFFDEAEA),
            circle = if (remoteAvailable) Color(0xFFCDEEDB) else Color(0xFFF9D2D2),
            accent = if (remoteAvailable) Color(0xFF15803D) else Color(0xFFDC2626),
            icon = if (remoteAvailable) "✓" else "✕",
            title = stringResource(R.string.remote_status_title),
            subtitle = stringResource(
                if (remoteAvailable) R.string.remote_status_available else R.string.remote_status_unavailable,
            ),
        )

        SmallTitle(stringResource(R.string.section_overview))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            InfoRow(
                title = stringResource(R.string.overview_route_lock),
                value = stringResource(
                    if (enabled) R.string.overview_route_lock_on else R.string.overview_route_lock_off,
                ),
                valueColor = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            InfoRow(
                title = stringResource(R.string.overview_target_apps),
                value = stringResource(R.string.overview_target_apps_value, targetCount, effectiveCount),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            InfoRow(
                title = stringResource(R.string.overview_output_devices),
                value = stringResource(
                    R.string.overview_device_configured,
                    configuredCount,
                    targetCount,
                ),
                valueColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        SmallTitle(stringResource(R.string.section_lock))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchPreference(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                title = stringResource(R.string.enable_route_lock),
            )
            SwitchPreference(
                checked = muteWhenMissing,
                onCheckedChange = onMuteChange,
                title = stringResource(R.string.silence_when_missing),
            )
            SwitchPreference(
                checked = debugLog,
                onCheckedChange = onDebugChange,
                title = stringResource(R.string.debug_log),
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            ArrowPreference(
                title = stringResource(R.string.reset_settings),
                summary = stringResource(R.string.reset_settings_summary),
                onClick = onResetRequest,
            )
        }

        Text(
            text = stringResource(R.string.auto_save_hint),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AppsTab(
    modifier: Modifier,
    devices: List<DeviceEntry>,
    targets: List<String>,
    deviceMap: Map<String, RouteSettings.DeviceRef>,
    scopePackages: Set<String>,
    loadedProcesses: List<String>,
    staleProcesses: List<String>,
    pendingScopeCount: Int,
    onPickApps: () -> Unit,
    onRequestScope: () -> Unit,
    onRemoveRequest: (String) -> Unit,
    onAppActionsRequest: (String) -> Unit,
    onDeviceSelected: (String, DeviceEntry) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SmallTitle(stringResource(R.string.section_effective_apps))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            ArrowPreference(
                title = stringResource(R.string.pick_target_app),
                summary = if (targets.isEmpty()) {
                    stringResource(R.string.selected_apps_empty)
                } else {
                    stringResource(R.string.target_app_count, targets.size)
                },
                onClick = onPickApps,
            )
        }

        if (targets.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_effective_apps),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        targets.forEach { packageName ->
            val label = remember(packageName) { resolveAppLabel(context, packageName) }
            val icon = remember(packageName) {
                resolveAppIcon(context, packageName)?.toImageBitmap(ICON_BITMAP_SIZE)
            }
            val state = appState(context, packageName, deviceMap, scopePackages, loadedProcesses, staleProcesses)
            val ref = deviceMap[packageName]
            val unavailableLabel = remember(ref) {
                ref?.let { context.getString(R.string.device_unavailable_label, disconnectedDeviceLabel(context, it)) }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                insideMargin = PaddingValues(0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 长按图标 + 名称区域弹出操作菜单（打开 / 强制重启）
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .pointerInput(packageName) {
                                detectTapGestures(onLongPress = { onAppActionsRequest(packageName) })
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(icon, 36.dp)
                        Column(
                            modifier = Modifier.padding(start = 12.dp),
                        ) {
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.body1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = packageName,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = state.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (state.active) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                    // 只有点这个复选框才会进入移除确认，点行内其它位置不会取消。
                    Checkbox(
                        state = ToggleableState.On,
                        onClick = { onRemoveRequest(packageName) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                AppDeviceSelector(
                    devices = devices,
                    ref = ref,
                    unavailableLabel = unavailableLabel,
                    onDeviceSelected = { entry -> onDeviceSelected(packageName, entry) },
                )
            }
        }

        if (pendingScopeCount > 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                onClick = onRequestScope,
            ) {
                Text(
                    text = stringResource(R.string.request_scope),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.request_scope_summary, pendingScopeCount),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.auto_save_hint),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AppDeviceSelector(
    devices: List<DeviceEntry>,
    ref: RouteSettings.DeviceRef?,
    unavailableLabel: String?,
    onDeviceSelected: (DeviceEntry) -> Unit,
) {
    val deviceIndex = indexOfDevice(devices, ref)
    val placeholder = if (ref == null) {
        stringResource(R.string.app_device_unset)
    } else {
        unavailableLabel ?: stringResource(R.string.app_device_unset)
    }
    val items = remember(devices, deviceIndex, placeholder) {
        if (deviceIndex >= 0) {
            devices.map { DropdownItem(text = it.label) }
        } else {
            listOf(DropdownItem(text = placeholder)) + devices.map { DropdownItem(text = it.label) }
        }
    }

    OverlaySpinnerPreference(
        items = items,
        selectedIndex = deviceIndex.coerceAtLeast(0),
        title = stringResource(R.string.app_device_label),
        summary = if (ref == null) {
            null
        } else {
            stringResource(if (deviceIndex >= 0) R.string.device_available else R.string.device_unavailable)
        },
        maxHeight = 420.dp,
        onSelectedIndexChange = { index ->
            val realIndex = if (deviceIndex >= 0) index else index - 1
            if (realIndex >= 0) {
                devices.getOrNull(realIndex)?.let(onDeviceSelected)
            }
        },
    )
}

@Composable
private fun StatusCard(
    container: Color,
    circle: Color,
    accent: Color,
    icon: String,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(container)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(circle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = icon,
                color = accent,
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = title,
                color = Color(0xFF1F2937),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = Color(0xFF4B5563),
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LogTab(
    modifier: Modifier,
    debugEnabled: Boolean,
    logText: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = modifier) {
        if (!debugEnabled) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.log_disabled_hint),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else if (logText.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = logText,
                        style = MiuixTheme.textStyles.footnote1,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.log_refresh),
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.log_clear),
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    title: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 200.dp)
                .padding(start = 12.dp),
        )
    }
}

@Composable
private fun AppPickerScreen(
    apps: List<AppEntry>?,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.pick_target_app),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.search_app_hint),
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (apps == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(R.string.loading_apps), style = MiuixTheme.textStyles.body2)
                }
                return@Column
            }

            val keyword = query.trim().lowercase(Locale.ROOT)
            val filtered = if (keyword.isEmpty()) {
                apps
            } else {
                apps.filter {
                    it.label.lowercase(Locale.ROOT).contains(keyword) ||
                        it.packageName.lowercase(Locale.ROOT).contains(keyword)
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(R.string.no_matching_app), style = MiuixTheme.textStyles.body2)
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val iconBitmap = remember(app.icon) { app.icon?.toImageBitmap(ICON_BITMAP_SIZE) }
                    Card(insideMargin = PaddingValues(0.dp)) {
                        CheckboxPreference(
                            title = app.label,
                            summary = app.packageName,
                            checked = selected.contains(app.packageName),
                            onCheckedChange = { onToggle(app.packageName) },
                            startAction = { AppIcon(iconBitmap, 40.dp) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, size: Dp) {
    if (icon == null) {
        Spacer(modifier = Modifier.width(0.dp))
    } else {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    }
}

private fun DeviceEntry.toDeviceRef(): RouteSettings.DeviceRef =
    RouteSettings.DeviceRef(device.type, AudioDeviceMatcher.displayName(device), device.address ?: "")

private fun indexOfDevice(devices: List<DeviceEntry>, ref: RouteSettings.DeviceRef?): Int {
    if (ref == null) {
        return -1
    }
    return devices.indexOfFirst { entry ->
        entry.device.type == ref.type && (ref.address.isEmpty() || ref.address == entry.device.address)
    }
}

private fun appState(
    context: Context,
    packageName: String,
    deviceMap: Map<String, RouteSettings.DeviceRef>,
    scopePackages: Set<String>,
    loadedProcesses: List<String>,
    staleProcesses: List<String>,
): AppState {
    if (!deviceMap.containsKey(packageName)) {
        return AppState(context.getString(R.string.app_state_no_device), false)
    }
    if (!scopePackages.contains(packageName)) {
        return AppState(context.getString(R.string.app_state_not_scoped), false)
    }
    val processes = loadedProcesses.filter { it == packageName || it.startsWith("$packageName:") }
    if (processes.isEmpty()) {
        return AppState(context.getString(R.string.app_state_not_running), false)
    }
    return if (processes.any { staleProcesses.contains(it) }) {
        AppState(context.getString(R.string.app_state_stale), false)
    } else {
        AppState(context.getString(R.string.app_state_active), true)
    }
}

private fun requestScope(context: Context, packages: List<String>, onDone: () -> Unit) {
    val service = App.getXposedService() ?: return
    if (packages.isEmpty()) {
        onDone()
        return
    }
    try {
        service.requestScope(
            packages,
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_scope_approved, approved.joinToString("、")),
                            Toast.LENGTH_LONG,
                        ).show()
                        AppLog.append(context, "已将 ${approved.joinToString("、")} 加入模块作用域")
                        onDone()
                    }
                }
            },
        )
        Toast.makeText(context, context.getString(R.string.toast_scope_requested), Toast.LENGTH_LONG).show()
    } catch (t: Throwable) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_scope_failed, t.message ?: ""),
            Toast.LENGTH_LONG,
        ).show()
        AppLog.append(context, "申请作用域失败：${t.message}")
    }
}

private fun openApp(context: Context, packageName: String): Boolean = try {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent != null) {
        context.startActivity(intent)
        true
    } else {
        Toast.makeText(context, R.string.toast_no_launcher, Toast.LENGTH_SHORT).show()
        false
    }
} catch (t: Throwable) {
    Toast.makeText(context, R.string.toast_no_launcher, Toast.LENGTH_SHORT).show()
    false
}

private fun forceRestart(context: Context, packageName: String) {
    Toast.makeText(context, R.string.toast_force_restarting, Toast.LENGTH_SHORT).show()
    Thread {
        var stopped = false
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
            process.waitFor()
            stopped = process.exitValue() == 0
        } catch (t: Throwable) {
        }
        if (!stopped) {
            try {
                context.getSystemService(ActivityManager::class.java)
                    .killBackgroundProcesses(packageName)
            } catch (t: Throwable) {
            }
        }
        try {
            Thread.sleep(400)
        } catch (ignored: InterruptedException) {
        }
        Handler(Looper.getMainLooper()).post { openApp(context, packageName) }
    }.start()
}

private fun loadOutputDevices(context: Context): List<DeviceEntry> {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    return audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.isSink }
        .map { DeviceEntry(it, deviceLabel(context, it)) }
}

private fun deviceLabel(context: Context, device: AudioDeviceInfo): String {
    return if (isBluetoothType(device.type)) {
        val name = AudioDeviceMatcher.displayName(device)
        if (isMeaningfulDeviceName(name)) {
            "蓝牙 $name"
        } else {
            deviceTypeName(context, device.type)
        }
    } else {
        deviceTypeName(context, device.type)
    }
}

private fun disconnectedDeviceLabel(context: Context, ref: RouteSettings.DeviceRef): String {
    return if (isBluetoothType(ref.type)) {
        if (isMeaningfulDeviceName(ref.name)) {
            "蓝牙 ${ref.name}"
        } else {
            deviceTypeName(context, ref.type)
        }
    } else {
        deviceTypeName(context, ref.type)
    }
}

/** 过滤掉手机型号（如 rmx3800）这类无意义的“设备名”。 */
private fun isMeaningfulDeviceName(name: String): Boolean =
    name.isNotBlank() && name != "unknown" && !name.equals(Build.MODEL, ignoreCase = true)

private fun isBluetoothType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> true

    else -> false
}

private fun deviceTypeName(context: Context, type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.device_type_speaker)
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.device_type_earpiece)
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> context.getString(R.string.device_type_bluetooth_a2dp)
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> context.getString(R.string.device_type_bluetooth_sco)
    AudioDeviceInfo.TYPE_BLE_HEADSET -> context.getString(R.string.device_type_ble_headset)
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> context.getString(R.string.device_type_ble_speaker)
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.device_type_wired_headset)
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> context.getString(R.string.device_type_wired_headphones)
    AudioDeviceInfo.TYPE_USB_DEVICE -> context.getString(R.string.device_type_usb_device)
    AudioDeviceInfo.TYPE_USB_HEADSET -> context.getString(R.string.device_type_usb_headset)
    AudioDeviceInfo.TYPE_HDMI -> context.getString(R.string.device_type_hdmi)
    AudioDeviceInfo.TYPE_LINE_ANALOG -> context.getString(R.string.device_type_line_analog)
    AudioDeviceInfo.TYPE_AUX_LINE -> context.getString(R.string.device_type_aux_line)
    AudioDeviceInfo.TYPE_TELEPHONY -> context.getString(R.string.device_type_telephony)
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> context.getString(R.string.device_type_remote_submix)
    AudioDeviceInfo.TYPE_HEARING_AID -> context.getString(R.string.device_type_hearing_aid)
    AudioDeviceInfo.TYPE_DOCK -> context.getString(R.string.device_type_dock)
    AudioDeviceInfo.TYPE_FM -> context.getString(R.string.device_type_fm)
    AudioDeviceInfo.TYPE_IP -> context.getString(R.string.device_type_ip)
    AudioDeviceInfo.TYPE_BUS -> context.getString(R.string.device_type_bus)
    else -> context.getString(R.string.device_type_other, type)
}

private fun loadInstalledApps(context: Context): List<AppEntry> {
    val packageManager = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val entries = LinkedHashMap<String, AppEntry>()

    for (info in packageManager.queryIntentActivities(launcher, 0)) {
        val activityInfo = info.activityInfo ?: continue
        val packageName = activityInfo.packageName
        if (packageName == context.packageName || entries.containsKey(packageName)) {
            continue
        }
        val label = info.loadLabel(packageManager)?.toString()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: packageName
        val icon = try {
            info.loadIcon(packageManager)
        } catch (t: Throwable) {
            null
        }
        entries[packageName] = AppEntry(label, packageName, icon)
    }

    val collator = Collator.getInstance(Locale.CHINA)
    val comparator = Comparator<AppEntry> { left, right ->
        val result = collator.compare(left.label, right.label)
        if (result != 0) result else left.packageName.compareTo(right.packageName)
    }
    return entries.values.sortedWith(comparator)
}

private fun resolveAppLabel(context: Context, packageName: String): String = try {
    val packageManager = context.packageManager
    packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
} catch (t: Throwable) {
    packageName
}

private fun resolveAppIcon(context: Context, packageName: String): Drawable? = try {
    val packageManager = context.packageManager
    packageManager.getApplicationInfo(packageName, 0).loadIcon(packageManager)
} catch (t: Throwable) {
    null
}

private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap.asImageBitmap()
}
