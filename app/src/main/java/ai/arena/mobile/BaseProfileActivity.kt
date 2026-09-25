package ai.arena.mobile

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ai.arena.mobile.databinding.ActivityProfileBinding
import kotlin.math.abs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Экран профиля: один WebView, один процесс, один каталог данных.
 * Наследники (ProfileActivity1…5) отличаются только идентификатором профиля.
 */
abstract class BaseProfileActivity : AppCompatActivity(), WebBridge.Host {

    protected abstract val profileId: String

    private lateinit var binding: ActivityProfileBinding
    private lateinit var appLockGate: AppLockGate
    private var webView: WebView? = null
    private var profile: Profile? = null
    private var lastKnownUrl: String = Links.HOME
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSharedText: String? = null
    private var restoreBundle: Bundle? = null
    private var restoredUrl: String? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var contentStarted = false
    private var sessionWarningChecked = false
    private var pendingDownload: PendingDownload? = null
    private var pendingBlobDownload: BlobDownload? = null

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    private data class BlobDownload(
        val dataUrl: String,
        val suggestedName: String?,
        val mimeType: String?,
    )

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult
            callback.onReceiveValue(extractUris(result.resultCode, result.data))
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request == null) return@registerForActivityResult
            if (grants.isNotEmpty() && grants.values.all { it }) request.grant(request.resources) else request.deny()
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingDownload
            val pendingBlob = pendingBlobDownload
            pendingDownload = null
            pendingBlobDownload = null
            when {
                pending != null && granted -> {
                    startDownload(
                        pending.url,
                        pending.userAgent,
                        pending.contentDisposition,
                        pending.mimeType,
                    )
                }

                pendingBlob != null && granted -> saveBlobDownload(pendingBlob)
                pending != null || pendingBlob != null -> toast(getString(R.string.toast_download_failed))
            }
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        // Обязательно до первого обращения к android.webkit в этом процессе:
        // разводит данные профилей по разным каталогам WebView.
        prepareProcessDataDirectory()
        super.onCreate(savedInstanceState)
        restoreBundle = savedInstanceState?.getBundle(STATE_WEBVIEW)
        restoredUrl = savedInstanceState?.getString(STATE_URL)
        pendingSharedText = savedInstanceState?.getString(ProfileRouter.EXTRA_SHARED_TEXT)

        ViewStateStats.onActivityCreate()
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appLockGate = AppLockGate(this)
        appLockGate.ensure { startProfileContent(intent) }
    }

    private fun prepareProcessDataDirectory() {
        try {
            WebView.setDataDirectorySuffix(profileId)
            WebViewState.markApplied()
        } catch (t: Throwable) {
            // WebView уже инициализирован в этом процессе — суффикс изменить нельзя
            WebViewState.markFailed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Всё, что требует разблокированного приложения (создаёт WebView). */
    private fun startProfileContent(intent: Intent?) {
        if (contentStarted) return
        contentStarted = true

        profile = ProfileStore.get(this, profileId)
        pendingSharedText = pendingSharedText
            ?: intent?.getStringExtra(ProfileRouter.EXTRA_SHARED_TEXT)?.takeIf { it.isNotBlank() }
        setupUi()
        setupWebView()
        applyProfileChrome()
        setupRail()
        attachHeaderSwipe()
        restoreOrLoad(intent)
        // Ярлык профиля может запускать Activity напрямую, минуя MainActivity.
        // Поэтому проверка обновлений должна работать и здесь.
        UpdateChecker.installPendingIfReady(this)
        UpdateChecker.checkAsync(this, manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedText = intent.getStringExtra(ProfileRouter.EXTRA_SHARED_TEXT)?.takeIf { it.isNotBlank() }
        val url = intent.getStringExtra(ProfileRouter.EXTRA_URL)
        when {
            !url.isNullOrBlank() -> loadUrl(url)
            intent.getBooleanExtra(ProfileRouter.EXTRA_AUTO_LOGIN, false) -> loadArenaForAutoLogin()
            pendingSharedText != null -> webView?.reload()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!contentStarted) return
        UpdateChecker.installPendingIfReady(this)
        UpdateChecker.checkAsync(this, manual = false)
        // Возврат в приложение после долгого фона: спрашиваем подтверждение снова.
        // Не затираем action, который создаёт WebView после разблокировки.
        appLockGate.ensureUnlocked()

        val fresh = ProfileStore.get(this, profileId)
        val previous = profile
        profile = fresh
        val userAgentChanged = previous != null &&
            (previous.desktopMode != fresh.desktopMode || previous.googleCompat != fresh.googleCompat)
        applyProfileChrome()
        applyRuntimeSettings()
        if (userAgentChanged) {
            webView?.settings?.userAgentString = WebUtils.userAgent(WebSettings.getDefaultUserAgent(this), fresh)
            webView?.reload()
        }
    }

    /**
     * WebView пишет cookies на диск с задержкой. Сохраняем их уже в onPause,
     * то есть до перехода в другой профиль или в фон, а onStop оставляем как
     * последний повторный барьер перед убийством процесса.
     */
    override fun onPause() {
        persistSessionBeforeLeaving()
        super.onPause()
    }

    override fun onStop() {
        persistSessionBeforeLeaving()
        super.onStop()
    }

    /** Вызывается роутером до запуска другого профильного процесса. */
    internal fun persistSessionBeforeLeaving() {
        if (!contentStarted) return
        try {
            SessionKeeper.persistCurrentProfile(
                ctx = this,
                profileId = profileId,
                keepSession = SettingsStore.read(this).keepSession,
            )
        } catch (t: Throwable) {
            // Сохранение входа не должно закрывать профиль при ошибке WebView.
        }
    }

    /**
     * Поворот экрана (и любое изменение конфигурации) приходит сюда: экран НЕ
     * пересоздаётся, WebView остаётся тем же, поэтому текст и вложения в
     * странице не теряются. Здесь обновляем только то, что зависит от размеров.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ViewStateStats.onConfigChange(ViewStateStats.describe(newConfig))
        if (!contentStarted) return
        setupRail()
        applyProfileChrome()
        applyRuntimeSettings()
        binding.swipe.resetScrollState()
    }

    /**
     * Страховка на случай, если экран всё же пересоздан системой: сохраняем
     * состояние WebView (адрес, историю), чтобы вернуть страницу, а не грузить
     * её заново.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, webView?.url ?: lastKnownUrl)
        pendingSharedText?.let { outState.putString(ProfileRouter.EXTRA_SHARED_TEXT, it) }
        try {
            val webState = Bundle()
            binding.webView.saveState(webState)
            outState.putBundle(STATE_WEBVIEW, webState)
        } catch (t: Throwable) {
            // WebView не успел подняться — состояния нет, ничего страшного
        }
    }

    override fun onDestroy() {
        persistSessionBeforeLeaving()
        try {
            binding.webView.removeJavascriptInterface(WebBridge.JS_NAME)
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        } catch (t: Throwable) {
            // ignore
        }
        webView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------- UI

    private fun setupUi() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { goBackSmart() }
        binding.tvAvatar.setOnClickListener { showSwitchSheet() }
        binding.btnRetry.setOnClickListener { loadUrl(lastKnownUrl) }
        binding.swipe.bindWebView { webView }
        binding.swipe.setOnRefreshListener { webView?.reload() }
        binding.swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.arena_secondary))
        binding.swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.arena_surface))
        binding.progress.max = 100

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = webView
                    if (view != null && view.canGoBack()) {
                        view.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    /** Панель профилей слева: включается на широких экранах (планшеты, складные). */
    private fun setupRail() {
        val wide = resources.configuration.smallestScreenWidthDp >= 600
        val profiles = ProfileStore.all(this)
        if (!wide || profiles.size < 2) {
            binding.railProfiles.isVisible = false
            return
        }

        binding.railProfiles.isVisible = true
        binding.railItems.removeAllViews()
        binding.railList.setOnClickListener { ProfileRouter.openChooser(this) }

        val density = resources.displayMetrics.density
        val size = (44 * density).toInt()

        profiles.forEach { entry ->
            val active = entry.id == profileId
            val avatar = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    bottomMargin = (8 * density).toInt()
                }
                gravity = Gravity.CENTER
                text = profileInitial(entry)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                val fill = parseColorSafe(entry.color)
                background = circleDrawable(
                    if (active) fill else (fill and 0x00FFFFFF) or (0x78 shl 24)
                )
                isClickable = true
                isFocusable = true
                contentDescription = entry.name
                setOnClickListener {
                    if (!active) ProfileRouter.switch(this@BaseProfileActivity, entry.id)
                }
                setOnLongClickListener {
                    Sheets.showEdit(this@BaseProfileActivity, entry.id) { }
                    true
                }
            }
            binding.railItems.addView(avatar)
        }
    }

    /** Свайп по шапке переключает профили (свайп от края экрана не трогаем — там системный жест). */
    private fun attachHeaderSwipe() {
        val threshold = 80 * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var tracking = false

        binding.toolbar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    tracking = true
                }

                MotionEvent.ACTION_UP -> {
                    if (tracking) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > threshold && abs(dy) < threshold) switchProfileBy(dx < 0)
                    }
                    tracking = false
                }

                MotionEvent.ACTION_CANCEL -> tracking = false
            }
            false // клики по кнопкам шапки не перехватываем
        }
    }

    private fun switchProfileBy(next: Boolean) {
        val profiles = ProfileStore.all(this)
        if (profiles.size < 2) return
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return
        val target = if (next) {
            profiles[(index + 1) % profiles.size]
        } else {
            profiles[(index - 1 + profiles.size) % profiles.size]
        }
        ProfileRouter.switch(this, target.id)
    }

    private fun applyProfileChrome() {
        val current = profile ?: return
        binding.toolbar.title = current.name
        binding.tvAvatar.background = circleDrawable(parseColorSafe(current.color))
        binding.tvAvatar.text = profileInitial(current)
        updateToolbarSubtitle(lastKnownUrl)
        if (current.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Настройки, влияющие на работу уже открытой страницы. */
    private fun applyRuntimeSettings() {
        val settings = SettingsStore.read(this)
        binding.swipe.isEnabled = settings.pullToRefresh
        if (!settings.pullToRefresh) {
            binding.swipe.isRefreshing = false
            binding.swipe.resetScrollState()
        }
        webView?.settings?.builtInZoomControls = settings.pinchZoom
        webView?.settings?.displayZoomControls = false
    }

    private fun updateToolbarSubtitle(url: String?) {
        val host = try {
            Uri.parse(url ?: "").host
        } catch (t: Throwable) {
            null
        }
        val github = profile?.github?.takeIf { it.isNotBlank() }
        val parts = mutableListOf<String>()
        if (!host.isNullOrBlank()) parts.add(host)
        parts.add(
            if (github != null) getString(R.string.github_connected, github)
            else getString(R.string.github_not_connected)
        )
        binding.toolbar.subtitle = parts.joinToString(" · ")
    }

    // -------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val current = profile ?: return
        val view = binding.webView
        webView = view

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
        if (Build.VERSION.SDK_INT >= 33) settings.setAlgorithmicDarkeningAllowed(true)
        settings.userAgentString = WebUtils.userAgent(settings.userAgentString, current)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        // Критично для OAuth-входов (GitHub, Google) внутри WebView.
        cookies.setAcceptThirdPartyCookies(view, true)

        view.setBackgroundColor(ContextCompat.getColor(this, R.color.arena_webview_bg))
        view.addJavascriptInterface(WebBridge(this), WebBridge.JS_NAME)
        view.webViewClient = ArenaWebViewClient()
        view.webChromeClient = ArenaChromeClient()
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        // Касания принадлежат WebView: без этого прокрутка на части устройств «залипает»
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        applyRuntimeSettings()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    /** Продолжаем с сохранённого состояния, если экран был пересоздан. */
    private fun restoreOrLoad(intent: Intent?) {
        val explicitNavigation = !intent?.getStringExtra(ProfileRouter.EXTRA_URL).isNullOrBlank() ||
            intent?.getBooleanExtra(ProfileRouter.EXTRA_AUTO_LOGIN, false) == true
        val restored = restoreBundle
        restoreBundle = null
        if (!explicitNavigation && restored != null) {
            try {
                if (webView?.restoreState(restored) != null) return
            } catch (t: Throwable) {
                // состояние не подошло — грузим адрес заново
            }
        }
        loadInitialUrl(intent)
    }

    private fun loadInitialUrl(intent: Intent?) {
        val extra = intent?.getStringExtra(ProfileRouter.EXTRA_URL)
        val autoLogin = intent?.getBooleanExtra(ProfileRouter.EXTRA_AUTO_LOGIN, false) == true
        val saved = restoredUrl?.takeIf { it.startsWith("http") }
        restoredUrl = null
        val remembered = profile?.lastUrl?.takeIf { it.startsWith("http") }
        val target = when {
            !extra.isNullOrBlank() -> extra
            autoLogin -> Links.HOME
            !saved.isNullOrBlank() -> saved
            !remembered.isNullOrBlank() -> remembered
            else -> Links.HOME
        }
        loadUrl(target)
    }

    /**
     * Переключение профилей всегда возвращает в Arena. WebView этого профиля
     * отправит собственные cookies, поэтому сайт автоматически восстановит
     * именно его аккаунт, не затрагивая соседние профили.
     */
    private fun loadArenaForAutoLogin() {
        if (!contentStarted || webView == null) return
        loadUrl(Links.HOME)
    }

    private fun loadUrl(url: String) {
        lastKnownUrl = url
        binding.errorView.isVisible = false
        webView?.loadUrl(url)
    }

    private fun goBackSmart() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else finish()
    }

    private inner class ArenaWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUri(request.url)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUri(Uri.parse(url))

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!url.isNullOrBlank()) lastKnownUrl = url
            if (Diagnostics.hostOf(url)?.endsWith("arena.ai") == true) ViewStateStats.onPageLoad()
            binding.progress.isVisible = true
            binding.errorView.isVisible = false
            // До подтверждения из JS жест «потянуть вниз» не перехватываем
            binding.swipe.resetScrollState()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            binding.swipe.isRefreshing = false
            binding.progress.isVisible = false
            val current = url ?: lastKnownUrl
            lastKnownUrl = current
            updateToolbarSubtitle(current)
            ProfileStore.markUsed(this@BaseProfileActivity, profileId, current)
            injectPageHelpers(view, current)
            pendingSharedText?.let { injectSharedText(view, it) }

            // Страница Arena загрузилась: фиксируем cookies, чтобы вход не потерялся.
            // Проверку потери сессии откладываем: CookieManager может вернуть
            // восстановленные cookies только после первого сетевого запроса.
            if (Diagnostics.hostOf(current)?.endsWith("arena.ai") == true) {
                SessionKeeper.flush()
                if (SettingsStore.read(this@BaseProfileActivity).keepSession) {
                    SessionKeeper.persistAuthCookies()
                }
                view.postDelayed({
                    if (!isFinishing && !isDestroyed && webView === view &&
                        Diagnostics.hostOf(view.url)?.endsWith("arena.ai") == true
                    ) {
                        warnIfSessionLost()
                    }
                }, SESSION_CHECK_DELAY_MS)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                binding.swipe.isRefreshing = false
                binding.progress.isVisible = false
                binding.errorView.isVisible = true
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Процесс-рендерер упал: показываем заглушку вместо падения приложения.
            binding.swipe.isRefreshing = false
            binding.progress.isVisible = false
            binding.errorView.isVisible = true
            return true
        }
    }

    private inner class ArenaChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progress.progress = newProgress
            binding.progress.isVisible = newProgress in 1..99
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val needed = mutableListOf<String>()
            request.resources?.forEach { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                        if (!hasPermission(android.Manifest.permission.CAMERA)) {
                            needed.add(android.Manifest.permission.CAMERA)
                        }
                    }

                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                            needed.add(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
            if (needed.isEmpty()) {
                request.grant(request.resources)
            } else {
                pendingPermissionRequest = request
                permissionLauncher.launch(needed.toTypedArray())
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, true, false)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?,
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            val chooserIntent = fileChooserParams?.createIntent() ?: return false
            return try {
                fileChooserLauncher.launch(chooserIntent)
                true
            } catch (t: Throwable) {
                fileChooserCallback = null
                false
            }
        }
    }

    private fun extractUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null

        val clip = data.clipData
        if (clip != null) {
            val uris = ArrayList<Uri>(clip.itemCount)
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { uris.add(it) }
            }
            return if (uris.isEmpty()) null else uris.toTypedArray()
        }

        val single = data.data ?: return null
        return arrayOf(single)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------- внешние ссылки и загрузки

    private fun handleUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return when (scheme) {
            "http", "https" -> false
            "about", "javascript", "data", "blob", "file", "content" -> false
            "intent" -> {
                launchIntentUri(uri)
                true
            }

            else -> {
                openExternally(uri)
                true
            }
        }
    }

    private fun launchIntentUri(uri: Uri) {
        try {
            val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            startActivity(parsed)
        } catch (t: Throwable) {
            toast(getString(R.string.toast_no_app))
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (t: Throwable) {
            toast(getString(R.string.toast_no_app))
        }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:", ignoreCase = true) || url.startsWith("data:", ignoreCase = true)) {
            requestBlobDownload(url, contentDisposition, mimeType)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType)
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val initial = DownloadFileName.resolve(url, contentDisposition, mimeType)
        val needsProbe = initial == null ||
            (DownloadFileName.isGenericMime(mimeType) && initial?.hasExtension != true)

        if (!needsProbe && initial != null) {
            enqueueDownload(url, userAgent, initial.name, mimeType)
            return
        }

        // У API Arena часто нет расширения в URL, а WebView передаёт
        // application/octet-stream. Сначала читаем только HTTP-заголовки:
        // Content-Disposition обычно содержит настоящее имя файла.
        Thread {
            val headers = probeDownloadHeaders(url, userAgent)
            val resolved = DownloadFileName.resolve(
                url = headers?.finalUrl ?: url,
                contentDisposition = headers?.contentDisposition ?: contentDisposition,
                mimeType = headers?.mimeType ?: mimeType,
            )
            val fileName = resolved?.name ?: initial?.name ?: "arena-download-${System.currentTimeMillis()}"
            val effectiveMime = headers?.mimeType ?: mimeType
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                enqueueDownload(url, userAgent, fileName, effectiveMime)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * WebView не может передать blob:// в DownloadManager. Читаем blob внутри
     * той же страницы и возвращаем его в приложение как data:...;base64,... .
     */
    private fun requestBlobDownload(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val view = webView
        if (view == null) {
            onBlobDownloadFailed()
            return
        }

        val suggestedName = DownloadFileName.resolve(null, contentDisposition, mimeType)?.name ?: "download"
        val requestedMime = mimeType?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
        val urlLiteral = JSONObject.quote(url)
        val nameLiteral = JSONObject.quote(suggestedName)
        val mimeLiteral = JSONObject.quote(requestedMime)
        val script = """
            (function() {
                var fail = function() {
                    if (window.ArenaAndroid && window.ArenaAndroid.reportBlobDownloadFailed) {
                        window.ArenaAndroid.reportBlobDownloadFailed();
                    }
                };
                fetch($urlLiteral).then(function(response) {
                    if (!response.ok) throw new Error("blob response failed");
                    return response.blob();
                }).then(function(blob) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        if (reader.result && window.ArenaAndroid && window.ArenaAndroid.reportBlobDownload) {
                            window.ArenaAndroid.reportBlobDownload(
                                String(reader.result),
                                $nameLiteral,
                                blob.type || $mimeLiteral
                            );
                        } else {
                            fail();
                        }
                    };
                    reader.onerror = fail;
                    reader.readAsDataURL(blob);
                }).catch(fail);
            })();
        """.trimIndent()

        try {
            view.evaluateJavascript(script, null)
        } catch (_: Throwable) {
            onBlobDownloadFailed()
        }
    }

    override fun onBlobDownload(dataUrl: String, suggestedName: String?, mimeType: String?) {
        if (dataUrl.length > MAX_BLOB_DATA_URL_LENGTH) {
            onBlobDownloadFailed()
            return
        }

        val pending = BlobDownload(dataUrl, suggestedName, mimeType)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            pendingBlobDownload = pending
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } catch (_: Throwable) {
                    pendingBlobDownload = null
                    toast(getString(R.string.toast_download_failed))
                }
            }
        } else {
            saveBlobDownload(pending)
        }
    }

    override fun onBlobDownloadFailed() {
        runOnUiThread { toast(getString(R.string.toast_download_failed)) }
    }

    private fun saveBlobDownload(download: BlobDownload) {
        Thread {
            try {
                val comma = download.dataUrl.indexOf(',')
                if (comma <= 5) throw IllegalArgumentException("invalid data URL")
                val metadata = download.dataUrl.substring(5, comma)
                if (!metadata.contains(";base64", ignoreCase = true)) {
                    throw IllegalArgumentException("blob is not base64 encoded")
                }
                val bytes = Base64.decode(download.dataUrl.substring(comma + 1), Base64.DEFAULT)
                if (bytes.isEmpty()) throw IllegalArgumentException("empty blob")

                val mime = download.mimeType?.substringBefore(';')?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: metadata.substringBefore(';').trim().takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val fileName = DownloadFileName.resolveSuggestedName(download.suggestedName, mime)?.name
                    ?: "download"
                saveBlobToDownloads(fileName, mime, bytes)
                runOnUiThread { toast(getString(R.string.toast_download_started)) }
            } catch (_: Throwable) {
                runOnUiThread { toast(getString(R.string.toast_download_failed)) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun saveBlobToDownloads(fileName: String, mimeType: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("cannot create Downloads entry")
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("cannot open Downloads entry")
                val completed = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                contentResolver.update(uri, completed, null, null)
            } catch (t: Throwable) {
                contentResolver.delete(uri, null, null)
                throw t
            }
            return
        }

        @Suppress("DEPRECATION")
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("cannot create Downloads directory")
        }
        val target = nextDownloadFile(directory, fileName)
        FileOutputStream(target).use { it.write(bytes) }
    }

    private fun nextDownloadFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate

        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var number = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($number)$extension")
            number++
        }
        return candidate
    }

    private data class DownloadHeaders(
        val finalUrl: String,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    /** Заголовки проверяем в фоне, чтобы не блокировать UI перед скачиванием. */
    private fun probeDownloadHeaders(url: String, userAgent: String?): DownloadHeaders? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        fun request(method: String): DownloadHeaders? {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    instanceFollowRedirects = true
                    connectTimeout = DOWNLOAD_PROBE_TIMEOUT_MS
                    readTimeout = DOWNLOAD_PROBE_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Accept", "*/*")
                    if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let {
                        setRequestProperty("Cookie", it)
                    }
                    if (method == "GET") setRequestProperty("Range", "bytes=0-0")
                }
                val code = connection.responseCode
                if (code !in 200..399) return null
                DownloadHeaders(
                    finalUrl = connection.url?.toString() ?: url,
                    contentDisposition = connection.getHeaderField("Content-Disposition"),
                    mimeType = connection.getHeaderField("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                )
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }

        // HEAD не загружает содержимое. Если endpoint его не поддерживает,
        // пробуем GET с одним байтом диапазона.
        return request("HEAD") ?: request("GET")
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        fileName: String,
        mimeType: String?,
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }?.let {
                request.setMimeType(it)
            }
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            request.setTitle(fileName)
            request.setDescription(getString(R.string.app_name))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.allowScanningByMediaScanner()
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast(getString(R.string.toast_download_started))
        } catch (t: Throwable) {
            // Не передаём blob:// или ошибочную ссылку внешнему приложению:
            // Android пытается открыть её как HTML и показывает вторую,
            // вводящую в заблуждение ошибку «нет приложения для ссылки».
            toast(getString(R.string.toast_download_failed))
        }
    }

    // --------------------------------------------------------------- GitHub

    /** Навешивает на страницу вспомогательные скрипты: прокрутка и логин GitHub. */
    private fun injectPageHelpers(view: WebView, url: String) {
        Scripts.load(this, Scripts.SCROLL_TRACKER).takeIf { it.isNotEmpty() }
            ?.let { view.evaluateJavascript(it, null) }

        val host = Diagnostics.hostOf(url) ?: return

        // Сторож черновика: не даёт потерять текст и вложения при повороте экрана
        if (host.endsWith("arena.ai")) {
            Scripts.load(this, Scripts.COMPOSER_KEEPER).takeIf { it.isNotEmpty() }
                ?.let { view.evaluateJavascript(it, null) }
        }

        if (!host.endsWith("github.com")) return
        Scripts.load(this, Scripts.GITHUB_PROBE).takeIf { it.isNotEmpty() }
            ?.let { view.evaluateJavascript(it, null) }
    }

    /** Страница сообщила, что находится в самом верху (или наоборот). */
    override fun onPageAtTopChanged(atTop: Boolean) {
        runOnUiThread { binding.swipe.setPageAtTop(atTop) }
    }

    /**
     * Текст, присланный из другого приложения через «Поделиться»: сначала кладём
     * в буфер обмена (запасной вариант), затем пытаемся подставить в поле ввода.
     */
    private fun injectSharedText(view: WebView, text: String) {
        pendingSharedText = null
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("prompt", text))
        } catch (t: Throwable) {
            // ignore
        }
        val script = Scripts.load(this, Scripts.SHARED_TEXT)
        if (script.isEmpty()) {
            onSharedTextResult(false)
            return
        }
        view.evaluateJavascript(script, null)
        view.evaluateJavascript(Scripts.sharedTextCall(JSONObject.quote(text)), null)
    }

    override fun onSharedTextResult(injected: Boolean) {
        runOnUiThread {
            toast(getString(if (injected) R.string.shared_text_pasted else R.string.shared_text_copied))
        }
    }

    /** Черновик вернулся в поле ввода после перерисовки страницы (поворот экрана). */
    override fun onDraftRestored(textRestored: Boolean, filesRestored: Int) {
        ViewStateStats.onDraftRestored()
        runOnUiThread {
            val message = when {
                filesRestored > 0 -> getString(R.string.draft_restored_files, filesRestored)
                textRestored -> getString(R.string.draft_restored)
                else -> return@runOnUiThread
            }
            toast(message)
        }
    }

    override fun onGithubLogin(login: String) {
        runOnUiThread {
            val current = profile ?: return@runOnUiThread
            if (current.github == login) return@runOnUiThread
            val updated = current.copy(github = login)
            ProfileStore.update(this, updated)
            profile = updated
            updateToolbarSubtitle(lastKnownUrl)
        }
    }

    // ------------------------------------------------------- состояние входа

    /**
     * Если в прошлый раз вход был, а сейчас cookies авторизации нет — вход не
     * пережил перезапуск. Сообщаем один раз, дальше подсказка не повторяется.
     */
    private fun warnIfSessionLost() {
        if (sessionWarningChecked) return
        sessionWarningChecked = true
        if (!SettingsStore.read(this).keepSession) return
        val diff = SessionKeeper.diffWithSnapshot(this, profileId)
        if (!diff.hasPrevious || !diff.lostAuth) return
        toast(getString(R.string.session_lost_hint))
        SessionKeeper.saveSnapshot(this, profileId)
    }

    // ---------------------------------------------------------------- меню

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_profile, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_desktop)?.isChecked = profile?.desktopMode == true

        // Показываем только выбранные для этого профиля разделы Arena
        val sections = profile?.sections ?: ProfileSections.DEFAULT
        menu.findItem(R.id.action_home)?.isVisible = sections.contains(ProfileSections.CHAT)
        menu.findItem(R.id.action_agent)?.isVisible = sections.contains(ProfileSections.AGENT)
        menu.findItem(R.id.action_leaderboard)?.isVisible = sections.contains(ProfileSections.LEADERBOARD)
        menu.findItem(R.id.action_history)?.isVisible = sections.contains(ProfileSections.HISTORY)
        menu.findItem(R.id.action_github_repos)?.isVisible = sections.contains(ProfileSections.REPOS)
        menu.findItem(R.id.action_help)?.isVisible = sections.contains(ProfileSections.HELP)

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            goBackSmart()
            true
        }

        R.id.action_refresh -> {
            webView?.reload()
            true
        }

        R.id.action_agent -> {
            loadUrl(Links.AGENT)
            true
        }

        R.id.action_home -> {
            loadUrl(Links.HOME)
            true
        }

        R.id.action_leaderboard -> {
            loadUrl(Links.LEADERBOARD)
            true
        }

        R.id.action_history -> {
            loadUrl(Links.HISTORY)
            true
        }

        R.id.action_github_repos -> {
            loadUrl(Links.githubRepos(profile?.github.orEmpty()))
            true
        }

        R.id.action_help -> {
            loadUrl(Links.HELP)
            true
        }

        R.id.action_desktop -> {
            toggleDesktopMode()
            true
        }

        R.id.action_share -> {
            shareCurrentUrl()
            true
        }

        R.id.action_copy -> {
            copyCurrentUrl()
            true
        }

        R.id.action_browser -> {
            openExternally(Uri.parse(currentUrl()))
            true
        }

        R.id.action_diagnostics -> {
            Sheets.showDiagnostics(this, profileId, inProfileProcess = true)
            true
        }

        R.id.action_scroll_top -> {
            scrollToTop()
            true
        }

        R.id.action_profile_settings -> {
            showEditSheet()
            true
        }

        R.id.action_switch -> {
            showSwitchSheet()
            true
        }

        R.id.action_app_settings -> {
            Sheets.showSettings(this, onChange = { applyRuntimeSettings() })
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleDesktopMode() {
        val current = profile ?: return
        val updated = current.copy(desktopMode = !current.desktopMode)
        ProfileStore.update(this, updated)
        profile = updated
        webView?.settings?.userAgentString = WebUtils.userAgent(WebSettings.getDefaultUserAgent(this), updated)
        webView?.reload()
        invalidateOptionsMenu()
        toast(getString(R.string.toast_saved))
    }

    private fun showSwitchSheet() {
        Sheets.showSwitch(this, profileId)
    }

    private fun showEditSheet() {
        Sheets.showEdit(
            activity = this,
            profileId = profileId,
            onResetRequest = { resetProfileInline() },
            onClearCacheRequest = { clearCacheInline() },
            inProfileProcess = true,
            onSaved = {
                profile = ProfileStore.get(this, profileId)
                applyProfileChrome()
                setupRail()
                invalidateOptionsMenu()
            },
        )
    }

    /** Сброс данных профиля, запущенный изнутри самого профиля. */
    private fun resetProfileInline() {
        ProfileStore.markForWipe(this, profileId)
        try {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
            val database = WebViewDatabase.getInstance(this)
            database.clearFormData()
            database.clearHttpAuthUsernamePassword()
            webView?.clearCache(true)
            webView?.clearHistory()
            webView?.clearFormData()
            webView?.loadUrl(Links.HOME)
        } catch (t: Throwable) {
            // ignore
        }
        val current = profile
        if (current != null) {
            val updated = current.copy(github = "", lastUrl = Links.HOME)
            ProfileStore.update(this, updated)
            profile = updated
            applyProfileChrome()
        }
        SessionKeeper.saveSnapshot(this, profileId)
        toast(getString(R.string.toast_reset_done))
    }

    /** Очистка кэша внутри самого профиля: вход в аккаунт сохраняется. */
    private fun clearCacheInline() {
        ProfileStore.markCacheClean(this, profileId)
        try {
            webView?.clearCache(true)
            webView?.clearFormData()
        } catch (t: Throwable) {
            // ignore
        }
        toast(getString(R.string.toast_cache_cleared))
    }

    /** Возврат страницы наверх: работает и для внутренних областей прокрутки. */
    private fun scrollToTop() {
        val view = webView ?: return
        view.scrollTo(0, 0)
        val script = Scripts.load(this, Scripts.SCROLL_TO_TOP)
        if (script.isNotEmpty()) view.evaluateJavascript(script, null)
    }

    private fun currentUrl(): String = webView?.url ?: lastKnownUrl

    private fun copyCurrentUrl() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("url", currentUrl()))
            toast(getString(R.string.toast_url_copied))
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun shareCurrentUrl() {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, currentUrl())
            }
            startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STATE_WEBVIEW = "profile_webview_state"
        private const val STATE_URL = "profile_webview_url"
        private const val SESSION_CHECK_DELAY_MS = 350L
        private const val DOWNLOAD_PROBE_TIMEOUT_MS = 5_000
        private const val MAX_BLOB_DATA_URL_LENGTH = 128 * 1024 * 1024
    }
}
