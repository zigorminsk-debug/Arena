package ai.arena.mobile

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ai.arena.mobile.databinding.ActivityProfileBinding

/**
 * Экран профиля: один WebView, один процесс, один каталог данных.
 * Наследники (ProfileActivity1…5) отличаются только идентификатором профиля.
 */
abstract class BaseProfileActivity : AppCompatActivity(), WebBridge.Host {

    protected abstract val profileId: String

    private lateinit var binding: ActivityProfileBinding
    private var webView: WebView? = null
    private var profile: Profile? = null
    private var lastKnownUrl: String = Links.HOME
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

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

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        // Обязательно до первого обращения к android.webkit в этом процессе:
        // разводит данные профилей по разным каталогам WebView.
        prepareProcessDataDirectory()
        super.onCreate(savedInstanceState)

        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profile = ProfileStore.get(this, profileId)
        setupUi()
        setupWebView()
        applyProfileChrome()
        loadInitialUrl(intent)
    }

    private fun prepareProcessDataDirectory() {
        try {
            WebView.setDataDirectorySuffix(profileId)
        } catch (t: Throwable) {
            // WebView уже инициализирован в этом процессе — суффикс изменить нельзя.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(ProfileRouter.EXTRA_URL)
        if (!url.isNullOrBlank()) loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        val fresh = ProfileStore.get(this, profileId)
        val previous = profile
        profile = fresh
        val userAgentChanged = previous != null &&
            (previous.desktopMode != fresh.desktopMode || previous.googleCompat != fresh.googleCompat)
        applyProfileChrome()
        if (userAgentChanged) {
            webView?.settings?.userAgentString = WebUtils.userAgent(WebSettings.getDefaultUserAgent(this), fresh)
            webView?.reload()
        }
    }

    override fun onDestroy() {
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
        settings.builtInZoomControls = SettingsStore.read(this).pinchZoom
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
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun loadInitialUrl(intent: Intent?) {
        val extra = intent?.getStringExtra(ProfileRouter.EXTRA_URL)
        val remembered = profile?.lastUrl?.takeIf { it.startsWith("http") }
        val target = when {
            !extra.isNullOrBlank() -> extra
            !remembered.isNullOrBlank() -> remembered
            else -> Links.HOME
        }
        loadUrl(target)
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
            binding.progress.isVisible = true
            binding.errorView.isVisible = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            binding.swipe.isRefreshing = false
            binding.progress.isVisible = false
            val current = url ?: lastKnownUrl
            lastKnownUrl = current
            updateToolbarSubtitle(current)
            ProfileStore.markUsed(this@BaseProfileActivity, profileId, current)
            injectGithubProbe(view, current)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            openExternally(Uri.parse(url))
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
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
            toast(getString(R.string.toast_download_failed))
            openExternally(Uri.parse(url))
        }
    }

    // --------------------------------------------------------------- GitHub

    private fun injectGithubProbe(view: WebView, url: String) {
        val host = try {
            Uri.parse(url).host
        } catch (t: Throwable) {
            null
        } ?: return
        if (!host.endsWith("github.com")) return
        view.evaluateJavascript(WebBridge.PROBE_SCRIPT, null)
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

    // ------------------------------------------------------------- меню

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_profile, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_desktop)?.isChecked = profile?.desktopMode == true
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

        R.id.action_profile_settings -> {
            showEditSheet()
            true
        }

        R.id.action_switch -> {
            showSwitchSheet()
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
            onSaved = {
                profile = ProfileStore.get(this, profileId)
                applyProfileChrome()
            },
            onResetRequest = { resetProfileInline() },
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
        if (current != null && current.github.isNotBlank()) {
            val updated = current.copy(github = "", lastUrl = Links.HOME)
            ProfileStore.update(this, updated)
            profile = updated
            applyProfileChrome()
        }
        toast(getString(R.string.toast_reset_done))
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

    @Suppress("unused")
    private fun confirmResetDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_reset_title)
            .setMessage(R.string.dialog_reset_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset) { _, _ -> resetProfileInline() }
            .show()
    }
}
