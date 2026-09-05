package com.axuan.lyskps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class InfoActivity : ComponentActivity() {

    private val vpnPrefs: SharedPreferences by lazy {
        getSharedPreferences(VpnConfig.PREFS, Context.MODE_PRIVATE)
    }

    // 响应式 UI 状态
    private val isVpnRunningState = mutableStateOf(false)
    private val isProxyRunningState = mutableStateOf(false)
    private val isShizukuConnectedState = mutableStateOf(false)
    private val shizukuUidState = mutableStateOf<Int?>(null)
    private val isRsaPatchedState = mutableStateOf(false)
    private val isRsaStatusCheckingState = mutableStateOf(false)
    private val gameTargetState = mutableStateOf("com.papegames.lysk.cn")
    private var pendingTarget: Context? = null
    private val certStatusState = mutableStateOf("正在读取 TLS 凭据…")
    private val logSnapshotState = mutableStateOf("")

    private var pendingShizukuMode: Int = 0
    private var pendingNlsPrepared: SolverNlsArchive.Prepared? = null
    private var rsaStatusCheckInFlight = false
    private var rsaStatusRefreshPending = false
    private var rsaStatusRevision = 0L
    private var wasShizukuConnected = false

    // Activity Result Launchers
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            showToast("未授予 VPN 权限")
        }
    }

    private val selectNlsZipLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri: Uri? = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) {
            VpnLog.i("NLS", "已选择 ZIP，开始校验并解压")
            showToast("正在校验并解压 Solver NLS 资源…")
            Thread {
                try {
                    val prepared = SolverNlsArchive.prepare(this, uri)
                    runOnUiThread {
                        pendingNlsPrepared = prepared
                        VpnLog.i("NLS", "ZIP/NX 校验完成，提交 Shizuku 安装")
                        requestShizukuOperation(NlsResourceManager.MODE_INSTALL)
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        pendingTarget = null
                        val detail = "NLS ZIP 解析失败：" + (t.message ?: t.javaClass.simpleName)
                        VpnLog.i("ERROR", detail)
                        showToast(detail)
                    }
                }
            }.start()
        } else {
            pendingTarget = null
            VpnLog.i("NLS", "已取消选择 Solver NLS ZIP")
        }
    }

    private var pendingRsaImportBits = 0
    private val importRsaPemLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri: Uri? = res.data?.data
        val expectedBits = pendingRsaImportBits
        pendingRsaImportBits = 0
        if (res.resultCode == RESULT_OK && uri != null && expectedBits != 0) {
            Thread {
                try {
                    val result = contentResolver.openInputStream(uri)?.use { input ->
                        RsaPublicKeyImporter.importPem(
                            getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE),
                            input,
                            expectedBits
                        )
                    } ?: throw IllegalArgumentException("无法读取所选 PEM 文件")
                    runOnUiThread {
                        showToast("$result，下次立即补丁时生效")
                        refreshRsaPatchStatus()
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        showToast("导入 PEM 失败：" + (t.message ?: t.javaClass.simpleName))
                    }
                }
            }.start()
        }
    }

    private val exportCrtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri: Uri? = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) {
            Thread {
                try {
                    contentResolver.openOutputStream(uri, "w")?.use { out ->
                        out.write(TlsIdentityStore.get(this).caBytes())
                        out.flush()
                    }
                    runOnUiThread { showToast("CA 证书已成功导出") }
                } catch (e: Throwable) {
                    runOnUiThread { showToast("导出失败：" + e.message) }
                }
            }.start()
        }
    }

    private val exportHashLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri: Uri? = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) {
            Thread {
                try {
                    contentResolver.openOutputStream(uri, "w")?.use { out ->
                        out.write(TlsIdentityStore.get(this).caPemBytes())
                        out.flush()
                    }
                    runOnUiThread { showToast("系统证书 .0 已成功导出") }
                } catch (e: Throwable) {
                    runOnUiThread { showToast("导出失败：" + e.message) }
                }
            }.start()
        }
    }

    private var pendingImportKind: Int = 0
    private val importPartLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri: Uri? = res.data?.data
        val kind = pendingImportKind
        if (res.resultCode == RESULT_OK && uri != null && kind != 0) {
            Thread {
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val result = TlsIdentityStore.get(this).importPart(kind, inputStream)
                        runOnUiThread {
                            LyskVpnService.stop(this)
                            showToast("$result；VPN 已停止，请重新启动")
                            refreshCertStatus()
                        }
                    }
                } catch (e: Throwable) {
                    runOnUiThread { showToast("导入失败：" + e.message) }
                }
            }.start()
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 3001) {
            val mode = pendingShizukuMode
            pendingShizukuMode = 0
            if (grantResult == PackageManager.PERMISSION_GRANTED && mode != 0) {
                VpnLog.i("ACTION", "Shizuku 权限已授予，继续执行：${shizukuActionName(mode)}")
                runShizukuOperation(mode)
            } else {
                VpnLog.i("ERROR", "未授予 Shizuku 权限，操作已取消")
                showToast("未授予 Shizuku 权限")
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateLiveStates()
            mainHandler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(this)
        } catch (ignored: Throwable) {}

        Shizuku.addBinderReceivedListenerSticky { runOnUiThread { updateLiveStates() } }
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        VpnLog.init(this)

        setContent {
            val isDark = true
            val themeController = remember(isDark) {
                ThemeController(
                    ColorSchemeMode.Dark,
                    keyColor = null,
                    isDark = isDark,
                    paletteStyle = ThemePaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.Spec2025
                )
            }
            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { isDark },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { isDark }
                )
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                onDispose { }
            }
            MiuixTheme(controller = themeController) {
                MainScreen(
                    isVpnRunning = isVpnRunningState.value,
                    isProxyRunning = isProxyRunningState.value,
                    isShizukuConnected = isShizukuConnectedState.value,
                    shizukuUid = shizukuUidState.value,
                    isRsaPatched = isRsaPatchedState.value,
                    isRsaStatusChecking = isRsaStatusCheckingState.value,
                    certStatus = certStatusState.value,
                    logSnapshot = logSnapshotState.value,
                    initialConfig = VpnConfig.load(vpnPrefs),
                    gameTarget = gameTargetState.value,
                    onSelectGame = { selectGameTarget(it) },
                    installedGames = GameTarget.PACKAGES.filter { pkg -> try { packageManager.getApplicationInfo(pkg, 0); true } catch (_: Exception) { false } },
                    onToggleVpn = { saveAndToggleVpn(it) },
                    onToggleProxy = { saveAndToggleProxy(it) },
                    onLaunchGame = { launchGame() },
                    onPatchRsa = { requestShizukuOperation(OfficialRsaRestorer.MODE_APPLY_PRIVATE) },
                    onRestoreRsa = { requestShizukuOperation(it) },
                    onImportRsaPem = { selectRsaPem(it) },
                    onRestoreBuiltInRsaPem = { restoreBuiltInRsaPem() },
                    onSelectNlsZip = { selectNlsZip() },
                    onRestoreNls = { requestShizukuOperation(it) },
                    onExportCrt = { exportCaCrt() },
                    onExportHash = { exportCaHash() },
                    onRegenerateCert = { regenerateCert() },
                    onImportPart = { importCertPart(it) },
                    onClearLogs = { VpnLog.clear(); logSnapshotState.value = "" },
                    onCopyLogs = { copyLogsToClipboard() }
                )
            }
        }

        gameTargetState.value = GameTarget.selected(this)
        refreshCertStatus()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(pollRunnable)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun updateLiveStates() {
        isVpnRunningState.value = LyskVpnService.isRunning(this)
        isProxyRunningState.value = HttpProxyService.isRunning()
        val shizukuConnected = Shizuku.pingBinder()
        val justConnected = shizukuConnected && !wasShizukuConnected
        wasShizukuConnected = shizukuConnected
        isShizukuConnectedState.value = shizukuConnected
        shizukuUidState.value = if (shizukuConnected) {
            try { Shizuku.getUid() } catch (ignored: Throwable) { null }
        } else {
            null
        }
        if (!shizukuConnected) {
            isRsaPatchedState.value = false
        } else {
            val hasPermission = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (ignored: Throwable) { false }
            if (hasPermission && justConnected) {
                refreshRsaPatchStatus()
            }
        }
        val snap = VpnLog.snapshot()
        if (logSnapshotState.value != snap) {
            logSnapshotState.value = snap
        }
    }

    private fun refreshRsaPatchStatus() {
        if (rsaStatusCheckInFlight) {
            rsaStatusRefreshPending = true
            return
        }
        val ready = try {
            Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (ignored: Throwable) { false }
        if (!ready) {
            isRsaPatchedState.value = false
            return
        }
        try {
            Config.load(getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE))
            val off2048 = 0L
            val off1024 = 0L
            val revision = rsaStatusRevision
            val target = GameTarget.selected(this)
            rsaStatusCheckInFlight = true
            isRsaStatusCheckingState.value = true
            OfficialRsaRestorer.checkPatched(
                this, off2048, off1024,
                Config.replace2048Bytes(), Config.replace1024Bytes()
            ) { patched, detail ->
                runOnUiThread {
                    rsaStatusCheckInFlight = false
                    isRsaStatusCheckingState.value = false
                    if (revision == rsaStatusRevision && target == GameTarget.selected(this)
                        && detail != "busy") {
                        isRsaPatchedState.value = try {
                            Shizuku.pingBinder() && patched
                        } catch (ignored: Throwable) { false }
                    }
                    if (rsaStatusRefreshPending) {
                        rsaStatusRefreshPending = false
                        refreshRsaPatchStatus()
                    }
                }
            }
        } catch (ignored: Throwable) {
            rsaStatusCheckInFlight = false
            isRsaStatusCheckingState.value = false
            isRsaPatchedState.value = false
        }
    }

    private fun refreshCertStatus() {
        Thread {
            try {
                val s = TlsIdentityStore.get(this).status()
                runOnUiThread { certStatusState.value = s }
            } catch (e: Throwable) {
                runOnUiThread { certStatusState.value = "凭据初始化异常：" + e.message }
            }
        }.start()
    }

    private fun saveConfig(cfg: VpnConfig): Boolean {
        return try {
            cfg.save(vpnPrefs)
            true
        } catch (t: Throwable) {
            showToast(t.message ?: "配置无效")
            false
        }
    }

    private fun saveAndToggleVpn(currentConfig: VpnConfig) {
        if (!saveConfig(currentConfig)) return
        if (LyskVpnService.isRunning(this)) {
            LyskVpnService.stop(this)
            showToast("VPN 分流已停止")
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnLauncher.launch(intent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        VpnLog.i("UI", "保存配置并启动 VPN")
        LyskVpnService.start(this)
        showToast("已启动 VPN 分流")
    }

    private fun saveAndToggleProxy(currentConfig: VpnConfig) {
        if (!saveConfig(currentConfig)) return
        if (HttpProxyService.isRunning()) {
            HttpProxyService.stop(this)
            showToast("HTTP 代理已停止")
        } else {
            HttpProxyService.start(this)
            showToast("独立 HTTP 代理已监听 127.0.0.1:${HttpProxyService.PORT}")
        }
    }

    private fun selectGameTarget(pkg: String) {
        if (pendingShizukuMode != 0 || OfficialRsaRestorer.isBusy() || NlsResourceManager.isBusy()) { showToast("请等待当前文件操作完成"); return }
        GameTarget.validate(pkg)
        vpnPrefs.edit().putString("game_package", pkg).apply()
        gameTargetState.value = pkg
        isRsaPatchedState.value = false
        refreshRsaPatchStatus()
    }

    private fun launchGame() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(GameTarget.selected(this))
            if (launch == null) {
                showToast("未安装恋与深空客户端")
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launch)
        } catch (e: Throwable) {
            showToast("启动游戏失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun requestShizukuOperation(mode: Int) {
        if (OfficialRsaRestorer.isBusy() || NlsResourceManager.isBusy()) { showToast("请等待当前文件操作完成"); return }
        if (mode != NlsResourceManager.MODE_INSTALL || pendingTarget == null) pendingTarget = GameTarget.freeze(this)
        val action = shizukuActionName(mode)
        VpnLog.i("ACTION", "请求执行：$action")
        try {
            if (!Shizuku.pingBinder()) {
                val detail = "Shizuku 未运行或尚未连接，无法执行：$action"
                VpnLog.i("ERROR", detail)
                showToast(detail)
                try { ShizukuProvider.requestBinderForNonProviderProcess(this) } catch (ignored: Throwable) {}
                return
            }
            if (Shizuku.isPreV11()) {
                val detail = "Shizuku 版本过旧，无法执行：$action"
                VpnLog.i("ERROR", detail)
                showToast("Shizuku 版本过旧，请升级至 v11+")
                return
            }
            if (requiresRootShizuku(mode) && Shizuku.getUid() != 0) {
                val detail = "$action 仅当 Shizuku 以 Root 模式运行时可用"
                VpnLog.i("ERROR", detail)
                showToast(detail)
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                VpnLog.i("ACTION", "权限已就绪，开始执行：$action")
                runShizukuOperation(mode)
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                val detail = "Shizuku 权限已被拒绝，无法执行：$action"
                VpnLog.i("ERROR", detail)
                showToast("Shizuku 权限已被拒绝，请在 Shizuku 应用中重新授权")
                return
            }
            pendingShizukuMode = mode
            VpnLog.i("ACTION", "正在请求 Shizuku 权限：$action")
            Shizuku.requestPermission(3001)
        } catch (e: Throwable) {
            val detail = "连接 Shizuku 失败：" + (e.message ?: e.javaClass.simpleName)
            VpnLog.i("ERROR", detail)
            showToast(detail)
        }
    }

    private fun shizukuActionName(mode: Int): String = when (mode) {
        OfficialRsaRestorer.MODE_APPLY_PRIVATE -> "立即补丁 RSA"
        OfficialRsaRestorer.MODE_RESTORE_BACKUP -> "从自动备份还原 RSA"
        OfficialRsaRestorer.MODE_RESTORE_BLOCKS -> "恢复官方 RSA 公钥"
        OfficialRsaRestorer.MODE_DELETE_IL2CPP -> "删除并重建 il2cpp"
        OfficialRsaRestorer.MODE_RESTORE_FROM_APK -> "从游戏 APK 重写 metadata"
        NlsResourceManager.MODE_INSTALL -> "安装 NLS ZIP/NX"
        NlsResourceManager.MODE_RESTORE_BACKUP -> "从私有备份还原 NLS"
        NlsResourceManager.MODE_DELETE -> "删除已安装 NLS"
        else -> "未知操作 $mode"
    }

    private fun requiresRootShizuku(mode: Int): Boolean =
        mode == OfficialRsaRestorer.MODE_DELETE_IL2CPP

    private fun runShizukuOperation(mode: Int) {
        val targetContext = pendingTarget ?: GameTarget.freeze(this)
        pendingTarget = null
        if (requiresRootShizuku(mode)) {
            val hasRoot = try { Shizuku.pingBinder() && Shizuku.getUid() == 0 }
            catch (ignored: Throwable) { false }
            if (!hasRoot) {
                val detail = "${shizukuActionName(mode)} 仅当 Shizuku 以 Root 模式运行时可用"
                VpnLog.i("ERROR", detail)
                showToast(detail)
                return
            }
        }
        when (mode) {
            NlsResourceManager.MODE_INSTALL -> {
                val prepared = pendingNlsPrepared
                if (prepared == null) {
                    VpnLog.i("ERROR", "NLS 安装未执行：尚未准备 Solver ZIP/NX")
                    showToast("请先选择 Solver NLS ZIP")
                    return
                }
                showToast("正在备份并安装 NLS 资源…")
                NlsResourceManager.install(targetContext, prepared) { ok, detail ->
                    runOnUiThread {
                        if (ok) pendingNlsPrepared = null
                        showToast((if (ok) "完成：" else "失败：") + detail)
                    }
                }
            }
            NlsResourceManager.MODE_RESTORE_BACKUP -> {
                showToast("正在从私有备份还原 NLS…")
                NlsResourceManager.restoreBackup(targetContext) { ok, detail ->
                    runOnUiThread { showToast((if (ok) "完成：" else "失败：") + detail) }
                }
            }
            NlsResourceManager.MODE_DELETE -> {
                showToast("正在删除已安装的 NLS 资源…")
                NlsResourceManager.deleteInstalled(targetContext) { ok, detail ->
                    runOnUiThread { showToast((if (ok) "完成：" else "失败：") + detail) }
                }
            }
            OfficialRsaRestorer.MODE_APPLY_PRIVATE -> {
                try {
                    Config.load(getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE))
                    val off2048 = 0L
                    val off1024 = 0L
                    showToast("正在通过 Shizuku 立即补丁 RSA…")
                    OfficialRsaRestorer.patch(
                        targetContext, off2048, off1024,
                        Config.replace2048Bytes(), Config.replace1024Bytes()
                    ) { ok, detail ->
                        runOnUiThread {
                            showToast((if (ok) "完成：" else "失败：") + detail)
                            if (ok && GameTarget.selected(targetContext) == GameTarget.selected(this)) {
                                // patch() has already read both key blocks back successfully.
                                // Invalidate checks started before this write completed.
                                rsaStatusRevision++
                                rsaStatusRefreshPending = false
                                isRsaStatusCheckingState.value = false
                                isRsaPatchedState.value = true
                            } else {
                                refreshRsaPatchStatus()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    val detail = "RSA 配置无效：" + (e.message ?: e.javaClass.simpleName)
                    VpnLog.i("ERROR", detail)
                    showToast(detail)
                }
            }
            OfficialRsaRestorer.MODE_RESTORE_BACKUP -> {
                LyskVpnService.stop(this)
                showToast("正在从自动备份还原 RSA…")
                OfficialRsaRestorer.restoreBackup(targetContext) { ok, detail ->
                    runOnUiThread {
                        showToast((if (ok) "完成：" else "失败：") + detail)
                        refreshRsaPatchStatus()
                    }
                }
            }
            else -> {
                LyskVpnService.stop(this)
                val action = when (mode) {
                    OfficialRsaRestorer.MODE_DELETE_IL2CPP -> "删除 il2cpp 目录"
                    OfficialRsaRestorer.MODE_RESTORE_FROM_APK -> "从游戏 APK 重写 metadata"
                    else -> "恢复官方公钥"
                }
                showToast("正在通过 Shizuku $action…")
                OfficialRsaRestorer.restore(targetContext, mode) { ok, detail ->
                    runOnUiThread {
                        showToast((if (ok) "完成：" else "失败：") + detail)
                        refreshRsaPatchStatus()
                    }
                }
            }
        }
    }

    private fun selectNlsZip() {
        pendingTarget = GameTarget.freeze(this)
        VpnLog.i("NLS", "打开文件选择器，等待 Solver NLS ZIP")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        selectNlsZipLauncher.launch(intent)
    }

    private fun selectRsaPem(bits: Int) {
        pendingRsaImportBits = bits
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-pem-file", "text/plain", "application/octet-stream"))
        }
        importRsaPemLauncher.launch(intent)
    }

    private fun restoreBuiltInRsaPem() {
        Config.restoreDefaultRsaBlocks(getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE))
        showToast("已还原内置 2048/1024 位 PEM")
        refreshRsaPatchStatus()
    }

    private fun exportCaCrt() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-x509-ca-cert"
            putExtra(Intent.EXTRA_TITLE, "LYSK-PS-Connector-CA.crt")
        }
        exportCrtLauncher.launch(intent)
    }

    private fun exportCaHash() {
        Thread {
            try {
                val name = TlsIdentityStore.get(this).androidHashFileName()
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/x-pem-file"
                        putExtra(Intent.EXTRA_TITLE, name)
                    }
                    exportHashLauncher.launch(intent)
                }
            } catch (e: Throwable) {
                runOnUiThread { showToast("准备导出失败：" + e.message) }
            }
        }.start()
    }

    private fun regenerateCert() {
        Thread {
            try {
                TlsIdentityStore.get(this).regenerate()
                runOnUiThread {
                    LyskVpnService.stop(this)
                    refreshCertStatus()
                    showToast("已重新生成并停止 VPN，请安装并信任新 CA")
                }
            } catch (e: Throwable) {
                runOnUiThread { showToast("生成失败：" + e.message) }
            }
        }.start()
    }

    private fun importCertPart(kind: Int) {
        pendingImportKind = kind
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        importPartLauncher.launch(intent)
    }

    private fun copyLogsToClipboard() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("LYSK-PS Logs", logSnapshotState.value))
        showToast("分流日志已复制到剪贴板")
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

// -----------------------------------------------------------------------------
// COMPOSE UI: Miuix + 液态玻璃 (Liquid Glass Aesthetic)
// -----------------------------------------------------------------------------

@Composable
fun MainScreen(
    isVpnRunning: Boolean,
    isProxyRunning: Boolean,
    isShizukuConnected: Boolean,
    shizukuUid: Int?,
    isRsaPatched: Boolean,
    isRsaStatusChecking: Boolean,
    certStatus: String,
    logSnapshot: String,
    initialConfig: VpnConfig,
    gameTarget: String,
    onSelectGame: (String) -> Unit,
    installedGames: List<String>,
    onToggleVpn: (VpnConfig) -> Unit,
    onToggleProxy: (VpnConfig) -> Unit,
    onLaunchGame: () -> Unit,
    onPatchRsa: () -> Unit,
    onRestoreRsa: (Int) -> Unit,
    onImportRsaPem: (Int) -> Unit,
    onRestoreBuiltInRsaPem: () -> Unit,
    onSelectNlsZip: () -> Unit,
    onRestoreNls: (Int) -> Unit,
    onExportCrt: () -> Unit,
    onExportHash: () -> Unit,
    onRegenerateCert: () -> Unit,
    onImportPart: (Int) -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit
) {
    val homeScrollState = rememberScrollState()
    val proxyScrollState = rememberScrollState()
    val tlsScrollState = rememberScrollState()
    val logsPageScrollState = rememberScrollState()
    val logViewportScrollState = rememberScrollState()
    val scrollBehavior = MiuixScrollBehavior()
    val surfaceColor = Color.Black
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var activeDialog by rememberSaveable { mutableStateOf<AppDialog?>(null) }

    var mode by remember { mutableStateOf(initialConfig.mode) }
    var proxyEndpoint by remember { mutableStateOf(initialConfig.proxyEndpoint) }
    var redirectEndpoint by remember { mutableStateOf(initialConfig.redirectEndpoint) }
    var redirectTlsWrapper by remember { mutableStateOf(initialConfig.redirectTlsWrapper) }
    var domainsText by remember { mutableStateOf(initialConfig.domainsText) }
    var packagesText by remember { mutableStateOf(initialConfig.packagesText) }

    fun currentConfig(): VpnConfig {
        return VpnConfig.fromInput(
            mode,
            proxyEndpoint,
            redirectEndpoint,
            redirectTlsWrapper,
            domainsText,
            packagesText
        )
    }

    val isRedirect = mode == VpnConfig.MODE_REDIRECT
    val addressLower = redirectEndpoint.trim().lowercase(Locale.ROOT)
    val isHttps = addressLower.startsWith("https://")

    val pageScrollState = when (selectedTab) {
        MainTab.HOME -> homeScrollState
        MainTab.PROXY -> proxyScrollState
        MainTab.TLS -> tlsScrollState
        MainTab.LOGS -> logsPageScrollState
    }

    LaunchedEffect(selectedTab, logSnapshot) {
        if (selectedTab == MainTab.LOGS) {
            withFrameNanos { }
            withFrameNanos { }
            logViewportScrollState.scrollTo(logViewportScrollState.maxValue)
        }
    }

    LaunchedEffect(isShizukuConnected) {
        if (!isShizukuConnected && activeDialog in setOf(
                AppDialog.RSA_OPTIONS,
                AppDialog.RSA_DELETE_CONFIRM,
                AppDialog.RSA_APK_RESTORE_CONFIRM,
                AppDialog.RSA_KEY_OPTIONS,
                AppDialog.RSA_KEY_RESET_CONFIRM,
                AppDialog.NLS_OPTIONS,
                AppDialog.NLS_DELETE_CONFIRM
            )) {
            activeDialog = null
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                color = Color.Black,
                titleColor = Color.White,
                largeTitleColor = Color.White,
                title = if (selectedTab == MainTab.HOME) "LYSK PS Connector" else selectedTab.label,
                actions = {
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            LiquidGlassBottomBar(
                selectedTab = selectedTab,
                backdrop = backdrop,
                onSelected = { selectedTab = it }
            )
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .layerBackdrop(backdrop)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .verticalScroll(pageScrollState)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(modifier = Modifier.height(padding.calculateTopPadding() + 12.dp))

                // -------------------------------------------------------------
                // 1. HERO CARD (液态玻璃 / Liquid Glass 主卡片)
                // -------------------------------------------------------------
                if (selectedTab == MainTab.HOME) {
                    HomeStatusCard(
                        isVpnRunning = isVpnRunning,
                        isShizukuConnected = isShizukuConnected,
                        shizukuUid = shizukuUid,
                        modeName = if (isRedirect) "Web 重定向" else "HTTP 代理",
                        tlsWrapperActive = redirectTlsWrapper && isRedirect,
                        onToggle = { onToggleVpn(currentConfig()) }
                    )

                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "操作客户端：$gameTarget",
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Button(
                                onClick = { activeDialog = AppDialog.GAME_TARGET },
                                modifier = Modifier.height(40.dp),
                                colors = ButtonDefaults.buttonColors(),
                                insideMargin = PaddingValues(horizontal = 14.dp)
                            ) { Text("更改", fontSize = 13.sp, maxLines = 1) }
                        }
                    }

                    HomeActionCard(
                        title = "RSA 公钥补丁",
                        subtitle = when {
                            !isShizukuConnected -> "连接 Shizuku 后可用"
                            isRsaStatusChecking -> "正在检查 metadata 中的 RSA…"
                            isRsaPatched -> "已匹配当前导入的 2048/1024 位公钥"
                            else -> "修改 metadata 中的 2048/1024 位公钥"
                        },
                        enabled = isShizukuConnected && !isRsaStatusChecking,
                        highlighted = isRsaPatched,
                        primaryText = "立即补丁",
                        onPrimaryClick = onPatchRsa,
                        secondaryText = "还原",
                        onSecondaryClick = { activeDialog = AppDialog.RSA_OPTIONS },
                        tertiaryText = "导入新公钥",
                        onTertiaryClick = { activeDialog = AppDialog.RSA_KEY_OPTIONS }
                    )

                    HomeActionCard(
                        title = "NLS 语音资源",
                        subtitle = if (isShizukuConnected) {
                            "安装或还原配套 Solver ZIP/NX"
                        } else {
                            "连接 Shizuku 后可用"
                        },
                        enabled = isShizukuConnected,
                        primaryText = "安装 ZIP",
                        onPrimaryClick = onSelectNlsZip,
                        secondaryText = "还原",
                        onSecondaryClick = { activeDialog = AppDialog.NLS_OPTIONS }
                    )

                }

                // -------------------------------------------------------------
                // 3. 分流与网络设置 (Routing & Mode Configuration)
                // -------------------------------------------------------------
                if (selectedTab == MainTab.PROXY) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "工作分流配置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp)
                    )

                    // 模式选择
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModeChip(
                            modifier = Modifier.weight(1f),
                            selected = isRedirect,
                            title = "Web 重定向",
                            desc = "拦截并重定向到 Web",
                            onClick = { mode = VpnConfig.MODE_REDIRECT }
                        )
                        ModeChip(
                            modifier = Modifier.weight(1f),
                            selected = !isRedirect,
                            title = "HTTP 代理",
                            desc = "转发到上游代理服务",
                            onClick = { mode = VpnConfig.MODE_PROXY }
                        )
                    }

                    // 协议提示/警告
                    if (isRedirect && !isHttps) {
                        val tipBg = if (redirectTlsWrapper) {
                            MiuixTheme.colorScheme.primaryContainer
                        } else {
                            MiuixTheme.colorScheme.errorContainer
                        }
                        val tipColor = if (redirectTlsWrapper) {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        } else {
                            MiuixTheme.colorScheme.onErrorContainer
                        }
                        val tipText = if (redirectTlsWrapper) {
                            "提示：当前服务地址不是 https://。命中的 HTTPS 将由内置包装器终止 TLS 后转为 HTTP。"
                        } else {
                            "警告：服务地址不是 https:// 且包装器已关闭。原始 TLS 送入明文 HTTP 通常会失败。"
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(tipBg)
                                .padding(12.dp, 8.dp)
                        ) {
                            Text(text = tipText, fontSize = 11.sp, color = tipColor, lineHeight = 16.sp)
                        }
                    }

                    if (isRedirect) {
                        SwitchPreference(
                            title = "启用内置 HTTPS 包装器",
                            summary = "动态签发 Leaf 证书并终止 TLS（转明文 HTTP）",
                            checked = redirectTlsWrapper,
                            onCheckedChange = { redirectTlsWrapper = it }
                        )
                    }

                    // 地址输入
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("上游 HTTP 服务地址", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(
                            value = redirectEndpoint,
                            onValueChange = { redirectEndpoint = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("上游代理地址", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(
                            value = proxyEndpoint,
                            onValueChange = { proxyEndpoint = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("域名分流规则（每行一条，排除以 ! 开头，支持 re: 正则）", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(
                            value = domainsText,
                            onValueChange = { domainsText = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("VPN 作用包名（每行一个，* 代表除本工具外的所有应用）", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(
                            value = packagesText,
                            onValueChange = { packagesText = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SwitchPreference(
                        title = "独立 HTTP 调试代理",
                        summary = "监听 127.0.0.1:${HttpProxyService.PORT}，不启动 VPN，供抓包或调试",
                        checked = isProxyRunning,
                        onCheckedChange = { onToggleProxy(currentConfig()) }
                    )
                }
                }

                // -------------------------------------------------------------
                // 4. 高级维护与 TLS 证书中心 (Tools & Credentials)
                // -------------------------------------------------------------
                if (selectedTab == MainTab.TLS) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "TLS 凭据与证书中心",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .padding(12.dp, 10.dp)
                    ) {
                        Text(
                            text = certStatus,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }

                    ArrowPreference(
                        title = "导出 CA 证书 (.crt)",
                        summary = "供 Android 系统设置手动安装为用户受信任证书",
                        onClick = onExportCrt
                    )

                    ArrowPreference(
                        title = "导出系统哈希证书 (xxxxxxxx.0)",
                        summary = "Android 系统 CA 目录 / APEX 专用格式",
                        onClick = onExportHash
                    )

                    ArrowPreference(
                        title = "重新生成随机 CA / Leaf 证书对",
                        summary = "重置私有证书，旧 CA 将立即失效",
                        onClick = { activeDialog = AppDialog.TLS_REGENERATE_CONFIRM }
                    )

                    // 导入展开
                    var showImportRow by remember { mutableStateOf(false) }
                    ArrowPreference(
                        title = "导入证书与私钥对...",
                        summary = if (showImportRow) "收起导入入口" else "展开 CA/Leaf 证书与私钥手动导入入口",
                        onClick = { showImportRow = !showImportRow }
                    )

                    if (showImportRow) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onImportPart(TlsIdentityStore.CA_CERT) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("导入 CA 证书", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { onImportPart(TlsIdentityStore.CA_KEY) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("导入 CA 私钥", fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onImportPart(TlsIdentityStore.LEAF_CERT) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("导入 Leaf 证书", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { onImportPart(TlsIdentityStore.LEAF_KEY) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("导入 Leaf 私钥", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                }
                }

                // -------------------------------------------------------------
                // 5. 实时分流日志视窗 (Live Traffic Logs)
                // -------------------------------------------------------------
                if (selectedTab == MainTab.LOGS) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 12.dp, 14.dp, 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isVpnRunning) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("实时分流数据流", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = onCopyLogs) {
                                Text("复制", fontSize = 11.sp)
                            }
                            Button(onClick = onClearLogs) {
                                Text("清空", fontSize = 11.sp)
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                            .padding(12.dp)
                            .verticalScroll(logViewportScrollState)
                    ) {
                        Text(
                            text = if (logSnapshot.isEmpty()) "-- 暂无分流连接日志 --" else logSnapshot,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerHighest
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
                }

                Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 12.dp))
            }

            if (selectedTab == MainTab.HOME) {
                FloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            bottom = padding.calculateBottomPadding() + 20.dp,
                            end = 20.dp
                        )
                        .border(0.05.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                    shadowElevation = 0.dp,
                    onClick = onLaunchGame
                ) {
                    Icon(
                        imageVector = AppIcons.Launch,
                        contentDescription = "启动恋与深空",
                        modifier = Modifier.size(40.dp),
                        tint = MiuixTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }

    AppDialogHost(
        activeDialog = activeDialog,
        isShizukuRoot = isShizukuConnected && shizukuUid == 0,
        onDismiss = { activeDialog = null },
        onDialogChange = { activeDialog = it },
        onRestoreRsa = onRestoreRsa,
        onImportRsaPem = onImportRsaPem,
        onRestoreBuiltInRsaPem = onRestoreBuiltInRsaPem,
        onRestoreNls = onRestoreNls,
        onRegenerateCert = onRegenerateCert,
        installedGames = installedGames,
        gameTarget = gameTarget,
        onSelectGame = onSelectGame
    )
}

private enum class AppDialog {
    GAME_TARGET,
    RSA_OPTIONS,
    RSA_DELETE_CONFIRM,
    RSA_APK_RESTORE_CONFIRM,
    RSA_KEY_OPTIONS,
    RSA_KEY_RESET_CONFIRM,
    NLS_OPTIONS,
    NLS_DELETE_CONFIRM,
    TLS_REGENERATE_CONFIRM
}

@Composable
private fun AppDialogHost(
    activeDialog: AppDialog?,
    isShizukuRoot: Boolean,
    onDismiss: () -> Unit,
    onDialogChange: (AppDialog) -> Unit,
    onRestoreRsa: (Int) -> Unit,
    onImportRsaPem: (Int) -> Unit,
    onRestoreBuiltInRsaPem: () -> Unit,
    onRestoreNls: (Int) -> Unit,
    onRegenerateCert: () -> Unit,
    installedGames: List<String>,
    gameTarget: String,
    onSelectGame: (String) -> Unit
) {
    val title = when (activeDialog) {
        AppDialog.GAME_TARGET -> "更改操作客户端"
        AppDialog.RSA_OPTIONS -> "恢复 RSA"
        AppDialog.RSA_DELETE_CONFIRM -> "重建 il2cpp？"
        AppDialog.RSA_APK_RESTORE_CONFIRM -> "从游戏 APK 重写？"
        AppDialog.RSA_KEY_OPTIONS -> "导入新公钥"
        AppDialog.RSA_KEY_RESET_CONFIRM -> "还原内置 PEM？"
        AppDialog.NLS_OPTIONS -> "还原 NLS 语音资源"
        AppDialog.NLS_DELETE_CONFIRM -> "删除 NLS 资源？"
        AppDialog.TLS_REGENERATE_CONFIRM -> "重新生成 TLS 身份凭据？"
        null -> null
    }
    val summary = when (activeDialog) {
        AppDialog.GAME_TARGET -> if (installedGames.isEmpty()) "未找到已安装的客户端" else "选择 RSA、NLS 和启动操作使用的客户端"
        AppDialog.RSA_OPTIONS -> "通过 Shizuku 选择恢复方式"
        AppDialog.RSA_DELETE_CONFIRM -> if (isShizukuRoot) {
            "将停止恋与深空并删除 files/il2cpp 目录，游戏下次启动时会自动重建。"
        } else {
            "仅当 Shizuku 以 Root 模式运行时可用。"
        }
        AppDialog.RSA_APK_RESTORE_CONFIRM ->
            "将先校验游戏 APK 内的原始文件，再停止游戏并完整覆盖当前 global-metadata.dat。"
        AppDialog.RSA_KEY_OPTIONS -> "分别选择 2048 位和 1024 位 RSA 公钥 PEM"
        AppDialog.RSA_KEY_RESET_CONFIRM -> "将清除已导入的两把公钥，恢复应用内置的 2048/1024 位 PEM。"
        AppDialog.NLS_OPTIONS -> "选择从备份恢复，或移除已安装的资源文件"
        AppDialog.NLS_DELETE_CONFIRM -> "将停止游戏并删除上次安装记录对应的 ZIP 与 NX 文件。"
        AppDialog.TLS_REGENERATE_CONFIRM -> "旧 CA 将立即失效，需要重新向系统安装并信任新导出的 CA 证书。"
        null -> null
    }

    WindowDialog(
        show = activeDialog != null,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss
    ) {
        when (activeDialog) {
            AppDialog.GAME_TARGET -> {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    installedGames.forEach { pkg ->
                        BasicComponent(
                            title = GameTarget.LABELS[GameTarget.PACKAGES.indexOf(pkg)] + if (pkg == gameTarget) " · 当前" else "",
                            summary = pkg,
                            onClick = { onDismiss(); onSelectGame(pkg) }
                        )
                    }
                }
            }
            AppDialog.RSA_OPTIONS -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    BasicComponent(
                        title = "从自动备份还原",
                        summary = "恢复立即补丁前保存的 RSA 文件",
                        onClick = {
                            onDismiss()
                            onRestoreRsa(OfficialRsaRestorer.MODE_RESTORE_BACKUP)
                        }
                    )
                    BasicComponent(
                        title = "恢复官方公钥",
                        summary = "覆盖两处官方公钥块并保留 metadata",
                        onClick = {
                            onDismiss()
                            onRestoreRsa(OfficialRsaRestorer.MODE_RESTORE_BLOCKS)
                        }
                    )
                    BasicComponent(
                        title = "从游戏 APK 重写 metadata",
                        summary = "提取安装包内原始文件并完整覆盖",
                        onClick = { onDialogChange(AppDialog.RSA_APK_RESTORE_CONFIRM) }
                    )
                    BasicComponent(
                        title = "重建 il2cpp",
                        titleColor = BasicComponentDefaults.titleColor(MiuixTheme.colorScheme.error),
                        summary = if (isShizukuRoot) {
                            "删除目录，游戏下次启动时自动重建"
                        } else {
                            "仅当 Shizuku 以 Root 模式运行时可用"
                        },
                        enabled = isShizukuRoot,
                        onClick = { onDialogChange(AppDialog.RSA_DELETE_CONFIRM) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AppDialog.RSA_DELETE_CONFIRM -> {
                DialogActionButtons(
                    confirmText = "删除并重建",
                    destructive = true,
                    confirmEnabled = isShizukuRoot,
                    onDismiss = onDismiss,
                    onConfirm = {
                        onDismiss()
                        onRestoreRsa(OfficialRsaRestorer.MODE_DELETE_IL2CPP)
                    }
                )
            }

            AppDialog.RSA_APK_RESTORE_CONFIRM -> {
                DialogActionButtons(
                    confirmText = "校验并重写",
                    destructive = false,
                    onDismiss = onDismiss,
                    onConfirm = {
                        onDismiss()
                        onRestoreRsa(OfficialRsaRestorer.MODE_RESTORE_FROM_APK)
                    }
                )
            }

            AppDialog.RSA_KEY_OPTIONS -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    BasicComponent(
                        title = "导入 2048 位 PEM",
                        summary = "选择一份 2048 位 RSA 公钥文件",
                        onClick = {
                            onDismiss()
                            onImportRsaPem(2048)
                        }
                    )
                    BasicComponent(
                        title = "导入 1024 位 PEM",
                        summary = "选择一份 1024 位 RSA 公钥文件",
                        onClick = {
                            onDismiss()
                            onImportRsaPem(1024)
                        }
                    )
                    BasicComponent(
                        title = "还原内置 PEM",
                        titleColor = BasicComponentDefaults.titleColor(MiuixTheme.colorScheme.error),
                        summary = "恢复应用随附的两把公钥",
                        onClick = { onDialogChange(AppDialog.RSA_KEY_RESET_CONFIRM) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AppDialog.RSA_KEY_RESET_CONFIRM -> {
                DialogActionButtons(
                    confirmText = "还原内置 PEM",
                    destructive = false,
                    onDismiss = onDismiss,
                    onConfirm = {
                        onDismiss()
                        onRestoreBuiltInRsaPem()
                    }
                )
            }

            AppDialog.NLS_OPTIONS -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    BasicComponent(
                        title = "从私有备份还原",
                        summary = "恢复 connector 保存的 ZIP 与 NX",
                        onClick = {
                            onDismiss()
                            onRestoreNls(NlsResourceManager.MODE_RESTORE_BACKUP)
                        }
                    )
                    BasicComponent(
                        title = "删除已安装资源",
                        titleColor = BasicComponentDefaults.titleColor(MiuixTheme.colorScheme.error),
                        summary = "移除游戏中的 ZIP 与 NX",
                        onClick = { onDialogChange(AppDialog.NLS_DELETE_CONFIRM) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AppDialog.NLS_DELETE_CONFIRM -> {
                DialogActionButtons(
                    confirmText = "删除资源",
                    destructive = true,
                    onDismiss = onDismiss,
                    onConfirm = {
                        onDismiss()
                        onRestoreNls(NlsResourceManager.MODE_DELETE)
                    }
                )
            }

            AppDialog.TLS_REGENERATE_CONFIRM -> {
                DialogActionButtons(
                    confirmText = "重新生成",
                    destructive = false,
                    onDismiss = onDismiss,
                    onConfirm = {
                        onDismiss()
                        onRegenerateCert()
                    }
                )
            }

            null -> Unit
        }
    }
}

@Composable
private fun DialogActionButtons(
    confirmText: String,
    destructive: Boolean,
    confirmEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(
            text = "取消",
            onClick = onDismiss,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColorsPrimary(
                color = if (destructive) colors.error else colors.primary,
                contentColor = if (destructive) colors.onError else colors.onPrimary
            )
        ) {
            Text(confirmText, maxLines = 1)
        }
    }
}

private enum class MainTab(
    val label: String,
    val description: String
) {
    HOME("主页", "服务状态与快捷操作"),
    PROXY("代理", "分流模式与上游配置"),
    TLS("TLS", "证书凭据与密钥维护"),
    LOGS("日志", "实时连接与诊断信息")
}

@Composable
private fun LiquidGlassBottomBar(
    selectedTab: MainTab,
    backdrop: LayerBackdrop,
    onSelected: (MainTab) -> Unit
) {
    val items = remember {
        listOf(
            NavigationItem("主页", AppIcons.Home),
            NavigationItem("代理", AppIcons.Proxy),
            NavigationItem("TLS", AppIcons.Security),
            NavigationItem("日志", AppIcons.Logs)
        )
    }
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        .let { inset -> if (inset != 0.dp) 8.dp + inset else 36.dp }
    FloatingBottomBar(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = bottomPadding),
        selectedIndex = MainTab.entries.indexOf(selectedTab),
        onSelected = { onSelected(MainTab.entries[it]) },
        backdrop = backdrop,
        tabsCount = items.size,
        isBlurEnabled = true
    ) { activateTab ->
        items.forEachIndexed { index, item ->
            FloatingBottomBarItem(
                selected = MainTab.entries.indexOf(selectedTab) == index,
                onClick = { activateTab(index) },
                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label
                )
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 液态玻璃 Hero 核心控制卡片
// -----------------------------------------------------------------------------

@Composable
private fun HomeStatusCard(
    isVpnRunning: Boolean,
    isShizukuConnected: Boolean,
    shizukuUid: Int?,
    modeName: String,
    tlsWrapperActive: Boolean,
    onToggle: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val foreground = colors.onSurface
    val activeContainer = if (isSystemInDarkTheme()) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp),
        colors = CardDefaults.defaultColors(
            color = if (isVpnRunning) activeContainer else colors.surfaceContainer
        ),
        onClick = onToggle,
        pressFeedbackType = PressFeedbackType.Tilt
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = AppIcons.Security,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(22.dp, 24.dp)
                    .size(116.dp),
                tint = if (isVpnRunning) Color(0xFF36D167) else colors.primary.copy(alpha = 0.14f)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Text(
                    text = if (isVpnRunning) "分流保护生效中" else "分流服务未连接",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isVpnRunning) "点击卡片停止分流" else "点击卡片接管游戏流量",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = foreground.copy(alpha = 0.72f)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp, 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modeName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = foreground
                )
                Text(
                    text = if (tlsWrapperActive) "TLS 已启用" else "TLS 未启用",
                    fontSize = 12.sp,
                    color = foreground.copy(alpha = 0.68f)
                )
            }

            ShizukuStatusBadge(
                isShizukuConnected = isShizukuConnected,
                shizukuUid = shizukuUid,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp, 14.dp)
            )
        }
    }
}

@Composable
private fun ShizukuStatusBadge(
    isShizukuConnected: Boolean,
    shizukuUid: Int? = null,
    modifier: Modifier = Modifier
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (isShizukuConnected) colors.primaryContainer else colors.errorContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isShizukuConnected) colors.primary else colors.error)
        )
        Text(
            text = when {
                !isShizukuConnected -> "Shizuku 未就绪"
                shizukuUid == 0 -> "Shizuku · Root"
                shizukuUid == 2000 -> "Shizuku · Shell"
                else -> "Shizuku 已就绪"
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (isShizukuConnected) colors.onPrimaryContainer else colors.onErrorContainer,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    primaryText: String,
    onPrimaryClick: () -> Unit,
    secondaryText: String,
    onSecondaryClick: () -> Unit,
    tertiaryText: String? = null,
    onTertiaryClick: (() -> Unit)? = null
) {
    val colors = MiuixTheme.colorScheme
    val activeContainer = if (isSystemInDarkTheme()) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    val disabledContainer = lerp(colors.surfaceContainer, colors.surface, 0.68f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = when {
                !enabled -> disabledContainer
                highlighted -> activeContainer
                else -> colors.surfaceContainer
            }
        )
    ) {
        BasicComponent(
            title = title,
            summary = subtitle,
            enabled = enabled
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeActionButton(
                text = primaryText,
                primary = true,
                enabled = enabled,
                onClick = onPrimaryClick,
                modifier = Modifier.weight(1f)
            )
            HomeActionButton(
                text = secondaryText,
                primary = false,
                enabled = enabled,
                onClick = onSecondaryClick,
                modifier = Modifier.weight(1f)
            )
            if (tertiaryText != null && onTertiaryClick != null) {
                HomeActionButton(
                    text = tertiaryText,
                    primary = false,
                    enabled = enabled,
                    onClick = onTertiaryClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HomeActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        insideMargin = PaddingValues(horizontal = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun LiquidGlassHeroCard(
    isVpnRunning: Boolean,
    isShizukuConnected: Boolean,
    modeName: String,
    tlsWrapperActive: Boolean,
    onToggle: () -> Unit,
    onLaunchGame: () -> Unit
) {
    val colors = MiuixTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (isVpnRunning) colors.secondaryContainer else colors.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 顶行：Shizuku 状态胶囊 & 启动游戏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shizuku Badge
                ShizukuStatusBadge(isShizukuConnected = isShizukuConnected)

                // 启动游戏快捷胶囊
                TextButton(
                    text = "启动恋与深空",
                    onClick = onLaunchGame,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 中行：状态文字与大开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isVpnRunning) "分流保护生效中" else "分流服务未连接",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isVpnRunning) colors.primary else colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isVpnRunning) "VPN 规则已接管 · 目标流量重定向" else "点击右侧按钮一键接管游戏流量",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary
                    )
                }

                // 大尺寸弹性开关
                val buttonScale by animateFloatAsState(
                    targetValue = if (isVpnRunning) 1.05f else 1.0f,
                    animationSpec = spring(),
                    label = "btnScale"
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .scale(buttonScale)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isVpnRunning) colors.primary else colors.primaryContainer)
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isVpnRunning) "已开启" else "开启",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isVpnRunning) colors.onPrimary else colors.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 底行：参数胶囊统计
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusParamCol(label = "工作模式", value = modeName, color = colors.primary)
                StatusParamCol(
                    label = "TLS 包装器",
                    value = if (tlsWrapperActive) "已激活" else "已关闭",
                    color = if (tlsWrapperActive) colors.primary else colors.onSurfaceVariantSummary
                )
                StatusParamCol(label = "目标应用", value = "lysk.cn", color = colors.onSurface)
            }
        }
    }
}

@Composable
fun StatusParamCol(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun LiquidGlassActionCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    buttonText: String,
    onClick: () -> Unit,
    onSecondaryClick: () -> Unit,
    secondaryText: String,
    onTertiaryClick: (() -> Unit)? = null,
    tertiaryText: String? = null
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(
                    text = buttonText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                text = secondaryText,
                onClick = onSecondaryClick,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (tertiaryText != null && onTertiaryClick != null) {
                TextButton(
                    text = tertiaryText,
                    onClick = onTertiaryClick,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
fun ModeChip(
    modifier: Modifier,
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(
            color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh
        ),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp, 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.primary else colors.outline)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = desc, fontSize = 10.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}
