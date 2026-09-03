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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import androidx.core.view.WindowInsetsControllerCompat
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
    private val certStatusState = mutableStateOf("正在读取 TLS 凭据…")
    private val logSnapshotState = mutableStateOf("")

    private var pendingShizukuMode: Int = 0
    private var pendingNlsPrepared: SolverNlsArchive.Prepared? = null

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
            showToast("正在校验并解压 Solver NLS 资源…")
            Thread {
                try {
                    val prepared = SolverNlsArchive.prepare(this, uri)
                    runOnUiThread {
                        pendingNlsPrepared = prepared
                        requestShizukuOperation(NlsResourceManager.MODE_INSTALL)
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        showToast("NLS ZIP 解析失败：" + (t.message ?: t.javaClass.simpleName))
                    }
                }
            }.start()
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
                    runOnUiThread { showToast("$result，下次立即补丁时生效") }
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
                runShizukuOperation(mode)
            } else {
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
            val isDark = isSystemInDarkTheme()
            val themeController = remember(isDark) {
                ThemeController(
                    ColorSchemeMode.System,
                    keyColor = null,
                    isDark = isDark,
                    paletteStyle = ThemePaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.Spec2025
                )
            }
            LaunchedEffect(isDark) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
            MiuixTheme(controller = themeController) {
                MainScreen(
                    isVpnRunning = isVpnRunningState.value,
                    isProxyRunning = isProxyRunningState.value,
                    isShizukuConnected = isShizukuConnectedState.value,
                    certStatus = certStatusState.value,
                    logSnapshot = logSnapshotState.value,
                    initialConfig = VpnConfig.load(vpnPrefs),
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
        isShizukuConnectedState.value = Shizuku.pingBinder()
        val snap = VpnLog.snapshot()
        if (logSnapshotState.value != snap) {
            logSnapshotState.value = snap
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

    private fun launchGame() {
        try {
            val launch = packageManager.getLaunchIntentForPackage("com.papegames.lysk.cn")
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
        try {
            if (!Shizuku.pingBinder()) {
                showToast("Shizuku 未运行或尚未连接，请检查 Shizuku 状态")
                try { ShizukuProvider.requestBinderForNonProviderProcess(this) } catch (ignored: Throwable) {}
                return
            }
            if (Shizuku.isPreV11()) {
                showToast("Shizuku 版本过旧，请升级至 v11+")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                runShizukuOperation(mode)
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                showToast("Shizuku 权限已被拒绝，请在 Shizuku 应用中重新授权")
                return
            }
            pendingShizukuMode = mode
            Shizuku.requestPermission(3001)
        } catch (e: Throwable) {
            showToast("连接 Shizuku 失败：" + e.message)
        }
    }

    private fun runShizukuOperation(mode: Int) {
        when (mode) {
            NlsResourceManager.MODE_INSTALL -> {
                val prepared = pendingNlsPrepared
                if (prepared == null) {
                    showToast("请先选择 Solver NLS ZIP")
                    return
                }
                showToast("正在备份并安装 NLS 资源…")
                NlsResourceManager.install(this, prepared) { ok, detail ->
                    runOnUiThread {
                        if (ok) pendingNlsPrepared = null
                        showToast((if (ok) "完成：" else "失败：") + detail)
                    }
                }
            }
            NlsResourceManager.MODE_RESTORE_BACKUP -> {
                showToast("正在从私有备份还原 NLS…")
                NlsResourceManager.restoreBackup(this) { ok, detail ->
                    runOnUiThread { showToast((if (ok) "完成：" else "失败：") + detail) }
                }
            }
            NlsResourceManager.MODE_DELETE -> {
                showToast("正在删除已安装的 NLS 资源…")
                NlsResourceManager.deleteInstalled(this) { ok, detail ->
                    runOnUiThread { showToast((if (ok) "完成：" else "失败：") + detail) }
                }
            }
            OfficialRsaRestorer.MODE_APPLY_PRIVATE -> {
                try {
                    Config.load(getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE))
                    val off2048 = Config.parseHex(Config.off2048)
                    val off1024 = Config.parseHex(Config.off1024)
                    showToast("正在通过 Shizuku 立即补丁 RSA…")
                    OfficialRsaRestorer.patch(
                        this, off2048, off1024,
                        Config.replace2048Bytes(), Config.replace1024Bytes()
                    ) { ok, detail ->
                        runOnUiThread { showToast((if (ok) "完成：" else "失败：") + detail) }
                    }
                } catch (e: Throwable) {
                    showToast("RSA 配置无效：" + e.message)
                }
            }
            OfficialRsaRestorer.MODE_RESTORE_BACKUP -> {
                LyskVpnService.stop(this)
                showToast("正在从自动备份还原 RSA…")
                OfficialRsaRestorer.restoreBackup(this) { ok, detail ->
                    runOnUiThread { showToast((if (ok) "完成：" else "失败：") + detail) }
                }
            }
            else -> {
                LyskVpnService.stop(this)
                val action = if (mode == OfficialRsaRestorer.MODE_DELETE_IL2CPP) "删除 il2cpp 目录" else "恢复官方公钥"
                showToast("正在通过 Shizuku $action…")
                OfficialRsaRestorer.restore(this, mode) { ok, detail ->
                    runOnUiThread { showToast((if (ok) "完成：" else "失败：") + detail) }
                }
            }
        }
    }

    private fun selectNlsZip() {
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
    certStatus: String,
    logSnapshot: String,
    initialConfig: VpnConfig,
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
    val scrollState = rememberScrollState()
    val scrollBehavior = MiuixScrollBehavior()
    val surfaceColor = MiuixTheme.colorScheme.surface
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

    LaunchedEffect(selectedTab) {
        scrollState.scrollTo(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = selectedTab.label,
                actions = {
                    Text(
                        text = "3.1",
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
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .layerBackdrop(backdrop)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // -------------------------------------------------------------
                // 1. HERO CARD (液态玻璃 / Liquid Glass 主卡片)
                // -------------------------------------------------------------
                if (selectedTab == MainTab.HOME) {
                    LiquidGlassHeroCard(
                        isVpnRunning = isVpnRunning,
                        isShizukuConnected = isShizukuConnected,
                        modeName = if (isRedirect) "Web 重定向" else "HTTP 代理",
                        tlsWrapperActive = redirectTlsWrapper && isRedirect,
                        onToggle = { onToggleVpn(currentConfig()) },
                        onLaunchGame = onLaunchGame
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LiquidGlassActionCard(
                            modifier = Modifier.weight(1f),
                            title = "RSA 补丁",
                            subtitle = "改写公钥",
                            buttonText = "立即补丁",
                            onClick = onPatchRsa,
                            onSecondaryClick = { activeDialog = AppDialog.RSA_OPTIONS },
                            secondaryText = "还原",
                            onTertiaryClick = { activeDialog = AppDialog.RSA_KEY_OPTIONS },
                            tertiaryText = "导入新公钥"
                        )

                        LiquidGlassActionCard(
                            modifier = Modifier.weight(1f),
                            title = "NLS 资源",
                            subtitle = "Solver ZIP/NX",
                            buttonText = "安装 ZIP",
                            onClick = onSelectNlsZip,
                            onSecondaryClick = { activeDialog = AppDialog.NLS_OPTIONS },
                            secondaryText = "还原"
                        )
                    }
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
                            .height(260.dp)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
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

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    AppDialogHost(
        activeDialog = activeDialog,
        onDismiss = { activeDialog = null },
        onDialogChange = { activeDialog = it },
        onRestoreRsa = onRestoreRsa,
        onImportRsaPem = onImportRsaPem,
        onRestoreBuiltInRsaPem = onRestoreBuiltInRsaPem,
        onRestoreNls = onRestoreNls,
        onRegenerateCert = onRegenerateCert
    )
}

private enum class AppDialog {
    RSA_OPTIONS,
    RSA_DELETE_CONFIRM,
    RSA_KEY_OPTIONS,
    RSA_KEY_RESET_CONFIRM,
    NLS_OPTIONS,
    NLS_DELETE_CONFIRM,
    TLS_REGENERATE_CONFIRM
}

@Composable
private fun AppDialogHost(
    activeDialog: AppDialog?,
    onDismiss: () -> Unit,
    onDialogChange: (AppDialog) -> Unit,
    onRestoreRsa: (Int) -> Unit,
    onImportRsaPem: (Int) -> Unit,
    onRestoreBuiltInRsaPem: () -> Unit,
    onRestoreNls: (Int) -> Unit,
    onRegenerateCert: () -> Unit
) {
    val title = when (activeDialog) {
        AppDialog.RSA_OPTIONS -> "恢复 RSA"
        AppDialog.RSA_DELETE_CONFIRM -> "重建 il2cpp？"
        AppDialog.RSA_KEY_OPTIONS -> "导入新公钥"
        AppDialog.RSA_KEY_RESET_CONFIRM -> "还原内置 PEM？"
        AppDialog.NLS_OPTIONS -> "还原 NLS 语音资源"
        AppDialog.NLS_DELETE_CONFIRM -> "删除 NLS 资源？"
        AppDialog.TLS_REGENERATE_CONFIRM -> "重新生成 TLS 身份凭据？"
        null -> null
    }
    val summary = when (activeDialog) {
        AppDialog.RSA_OPTIONS -> "通过 Shizuku 选择恢复方式"
        AppDialog.RSA_DELETE_CONFIRM -> "将停止恋与深空并删除 files/il2cpp 目录，游戏下次启动时会自动重建。"
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
                        title = "重建 il2cpp",
                        titleColor = BasicComponentDefaults.titleColor(MiuixTheme.colorScheme.error),
                        summary = "删除目录，游戏下次启动时自动重建",
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
                    onDismiss = onDismiss,
                    onConfirm = {
                        onDismiss()
                        onRestoreRsa(OfficialRsaRestorer.MODE_DELETE_IL2CPP)
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
    IosLiquidGlassNavigationBar(
        items = items,
        selectedIndex = MainTab.entries.indexOf(selectedTab),
        onItemClick = { onSelected(MainTab.entries[it]) },
        backdrop = backdrop,
        isBlurActive = true
    )
}

// -----------------------------------------------------------------------------
// 液态玻璃 Hero 核心控制卡片
// -----------------------------------------------------------------------------

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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(
                            if (isShizukuConnected) colors.primaryContainer else colors.errorContainer
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isShizukuConnected) colors.primary else colors.error)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isShizukuConnected) "Shizuku 已连接" else "Shizuku 未运行",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isShizukuConnected) colors.onPrimaryContainer else colors.onErrorContainer
                        )
                    }
                }

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
