package com.soyxan.sidesubs

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val sessionRefreshExecutor = Executors.newSingleThreadExecutor()
    private val playbackClock = PlaybackClock()

    private lateinit var preferences: SharedPreferences
    private lateinit var plexAuth: PlexAuthManager
    private lateinit var jellyfinAuth: JellyfinAuthManager
    private lateinit var diagnostics: DiagnosticLog
    private var mediaProvider: MediaProvider? = null

    @Volatile private var pollInFlight = false
    @Volatile private var playbackGeneration = 0L
    @Volatile private var authPollInFlight = false
    @Volatile private var pendingPlexLogin: PlexPendingLogin? = null
    @Volatile private var plexLoginGeneration = 0L
    @Volatile private var pendingJellyfinLogin: JellyfinPendingLogin? = null
    @Volatile private var jellyfinLoginGeneration = 0L
    @Volatile private var plexLoginAuthorized = false
    @Volatile private var appInForeground = false
    private var cinemaMode = false
    private var delayControlsOpen = false
    private var hideChromeTask: Runnable? = null
    private var hideDelayControlsTask: Runnable? = null
    private var setupDialog: AlertDialog? = null
    private var setupStatusView: TextView? = null
    private var setupProgressView: ProgressBar? = null
    private var setupSignInAgainButton: Button? = null
    private var setupRequired = true

    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var delayControls: LinearLayout
    private lateinit var delayValueView: TextView
    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var currentSubtitleView: TextView
    private lateinit var nextSubtitleView: TextView
    private lateinit var sessionButton: Button
    private lateinit var subtitleButton: Button
    private lateinit var delayButton: Button
    private lateinit var settingsButton: Button
    private lateinit var cinemaButton: Button

    private var sessions: List<PlaybackSession> = emptyList()
    private var tracks: List<SubtitleTrack> = emptyList()
    private var selectedSession: PlaybackSession? = null
    private var selectedTrack: SubtitleTrack? = null
    private var timeline: SubtitleTimeline? = null
    private var loadedMediaId = ""
    private var loggedSessionCount = -1
    private var loggedSessionState = ""
    private var loggedPollError = ""
    private var lastLanguageFallbackNotice = ""
    private var pendingUpdateNotice: UpdateInfo? = null

    private val pollTask = object : Runnable {
        override fun run() {
            if (!appInForeground) return
            pollOnce()
            if (appInForeground) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val authPollTask = object : Runnable {
        override fun run() {
            val pending = pendingPlexLogin ?: return
            val generation = plexLoginGeneration
            if (!appInForeground) return
            val dialog = setupDialog
            if (authPollInFlight) {
                handler.postDelayed(this, AUTH_POLL_INTERVAL_MS)
                return
            }

            authPollInFlight = true
            executor.execute {
                try {
                    if (!plexLoginAuthorized) {
                        val token = plexAuth.pollLogin(pending)
                        if (token == null) {
                            if (appInForeground) handler.postDelayed(this, AUTH_POLL_INTERVAL_MS)
                            return@execute
                        }
                        if (pendingPlexLogin !== pending || generation != plexLoginGeneration) return@execute
                        plexAuth.saveAccountToken(token)
                        plexLoginAuthorized = true
                        runOnUiThread {
                            if (setupDialog !== dialog) return@runOnUiThread
                            setupStatusView?.text = "Signed in. Finding your Plex server…"
                            setupProgressView?.visibility = View.VISIBLE
                            setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Find Plex servers"
                            setupSignInAgainButton?.visibility = View.VISIBLE
                        }
                    }

                    if (!appInForeground) {
                        diagnostics.add("Plex server discovery deferred until SideSubs resumes")
                        return@execute
                    }
                    val servers = plexAuth.listServers()
                    if (pendingPlexLogin !== pending || generation != plexLoginGeneration) return@execute
                    runOnUiThread {
                        if (setupDialog !== dialog || !appInForeground) return@runOnUiThread
                        pendingPlexLogin = null
                        plexLoginAuthorized = false
                        if (servers.isEmpty()) {
                            setupProgressView?.visibility = View.GONE
                            setupStatusView?.text = "No Plex Media Servers found in your account."
                            setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                        } else {
                            setupDialog?.dismiss()
                            showServerChooser(servers, required = setupRequired, retryAfterLogin = true)
                        }
                    }
                } catch (error: Exception) {
                    if (pendingPlexLogin !== pending || generation != plexLoginGeneration || !appInForeground) return@execute
                    if (error is IOException) {
                        runOnUiThread {
                            if (setupDialog !== dialog || !appInForeground) return@runOnUiThread
                            setupProgressView?.visibility = View.VISIBLE
                            setupStatusView?.text =
                                "Cannot reach Plex right now. Retrying automatically…"
                        }
                        handler.postDelayed(this, NETWORK_RETRY_INTERVAL_MS)
                    } else {
                        pendingPlexLogin = null
                        plexLoginAuthorized = false
                        runOnUiThread {
                            if (setupDialog !== dialog || !appInForeground) return@runOnUiThread
                            setupProgressView?.visibility = View.GONE
                            setupStatusView?.text = "Plex sign-in: ${friendlyAuthError(error)}"
                            setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                                text = if (plexAuth.hasAccountToken()) "Find Plex servers" else "Sign in with Plex"
                                isEnabled = true
                            }
                            setupSignInAgainButton?.visibility =
                                if (plexAuth.hasAccountToken()) View.VISIBLE else View.GONE
                        }
                    }
                } finally {
                    authPollInFlight = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        diagnostics = DiagnosticLog(this)
        plexAuth = PlexAuthManager(preferences, diagnostics)
        jellyfinAuth = JellyfinAuthManager(preferences, diagnostics)
        diagnostics.add("App started; savedProvider=" + preferences.getString(PlexAuthManager.KEY_PROVIDER, "").orEmpty())
        installCrashRecorder()
        buildUi()
        checkForUpdatesIfNeeded()

        val afterCrash = {
            when {
                jellyfinAuth.hasSavedConnection() -> restoreSavedProvider()
                preferences.getString(PlexAuthManager.KEY_PROVIDER, "").orEmpty() == MediaProviderType.PLEX.name &&
                    plexAuth.hasSavedServer() -> restoreSavedProvider()
                plexAuth.hasAccountToken() ->
                    showProviderSetup(required = true, message = "Signed in to Plex. Find a media server to continue.")
                else -> showProviderSetup(required = true)
            }
        }
        if (!showRecordedCrashIfAny(afterCrash)) afterCrash()
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
        if (cinemaMode) {
            diagnostics.add("Cinema mode resumed")
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            enterImmersiveMode()
            showCinemaChromeTemporarily()
        }
        if (mediaProvider != null) {
            diagnostics.add("Playback polling resumed")
            startPolling()
        }
        if (pendingJellyfinLogin != null) {
            diagnostics.add("SideSubs resumed; continuing Jellyfin sign-in")
            handler.removeCallbacks(jellyfinAuthPollTask)
            handler.post(jellyfinAuthPollTask)
        }
        if (pendingPlexLogin != null) {
            diagnostics.add("SideSubs resumed; continuing Plex sign-in")
            if (plexLoginAuthorized) setupStatusView?.text = "Signed in. Finding your Plex server…"
            handler.removeCallbacks(authPollTask)
            handler.post(authPollTask)
        }
        maybeShowPendingUpdateNotice()
    }

    override fun onPause() {
        appInForeground = false
        stopPolling()
        handler.removeCallbacks(authPollTask)
        handler.removeCallbacks(jellyfinAuthPollTask)
        if (pendingPlexLogin != null) diagnostics.add("Plex sign-in paused while browser is open")
        super.onPause()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setOnClickListener { if (cinemaMode) showCinemaChromeTemporarily() }
        }
        applySafeAreaInsets()

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = pillBackground()
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }

        titleView = TextView(this).apply {
            text = "SideSubs"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 2
        }
        stateView = TextView(this).apply {
            text = "Choose a media server"
            setTextColor(0xFF999999.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        topBar.addView(titleView)
        topBar.addView(stateView)
        root.addView(topBar, matchWrap())

        val subtitleArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }

        currentSubtitleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.08f)
        }
        nextSubtitleView = TextView(this).apply {
            setTextColor(0xFF777777.toInt())
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, 0)
            setLineSpacing(0f, 1.06f)
        }
        subtitleArea.addView(currentSubtitleView, matchWrap())
        subtitleArea.addView(nextSubtitleView, matchWrap())
        root.addView(
            subtitleArea,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        delayValueView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(92)
        }
        val delayMinusButton = controlButton("−", description = "Decrease subtitle delay").apply {
            textSize = 22f
        }
        val delayPlusButton = controlButton("+", description = "Increase subtitle delay").apply {
            textSize = 22f
        }
        delayControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pillBackground()
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
            addView(delayMinusButton, LinearLayout.LayoutParams(dp(52), dp(44)))
            addView(delayValueView, LinearLayout.LayoutParams(dp(104), dp(44)))
            addView(delayPlusButton, LinearLayout.LayoutParams(dp(52), dp(44)))
        }
        root.addView(
            delayControls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            },
        )

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pillBackground()
            setPadding(dp(8), 0, dp(8), 0)
        }

        sessionButton = controlButton("Session", R.drawable.ic_tv).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleButton = controlButton("Subtitles", R.drawable.ic_subtitles).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
        }
        delayButton = controlButton("", R.drawable.ic_schedule, "Subtitle delay")
        settingsButton = controlButton("", R.drawable.ic_settings, "Settings")
        cinemaButton = controlButton("", R.drawable.ic_fullscreen, "Cinema mode")

        sessionButton.setOnClickListener { refreshSessionsAndShowChooser() }
        subtitleButton.setOnClickListener { showSubtitleChooser() }
        delayButton.setOnClickListener { showDelayControls() }
        delayMinusButton.setOnClickListener { adjustDelay(-DELAY_STEP_MS) }
        delayPlusButton.setOnClickListener { adjustDelay(DELAY_STEP_MS) }
        settingsButton.setOnClickListener { showSettings() }
        cinemaButton.setOnClickListener { setCinemaMode(!cinemaMode) }

        addControl(sessionButton, 1.15f)
        addControl(subtitleButton, 2.05f)
        addIconControl(delayButton)
        addIconControl(settingsButton)
        addIconControl(cinemaButton)

        root.addView(
            controls,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)),
        )

        setContentView(root)
        updateDelayDisplay()
        applySubtitleSize(preferredSubtitleSize())
    }

    private fun restoreSavedProvider() {
        stateView.text = "Connecting to saved media server…"
        executor.execute {
            try {
                val savedProvider = runCatching {
                    MediaProviderType.valueOf(
                        preferences.getString(PlexAuthManager.KEY_PROVIDER, MediaProviderType.PLEX.name)
                            ?: MediaProviderType.PLEX.name
                    )
                }.getOrDefault(MediaProviderType.PLEX)
                val connection = when (savedProvider) {
                    MediaProviderType.PLEX -> plexAuth.restoreConnection()
                    MediaProviderType.JELLYFIN -> jellyfinAuth.restoreConnection()
                } ?: error("Saved ${savedProvider.displayName} server is no longer available")
                runOnUiThread { connectProvider(connection) }
            } catch (error: Exception) {
                diagnostics.add("Restore server failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    stateView.text = "Choose a media server"
                    showProviderSetup(required = true, message = friendlyError(error))
                }
            }
        }
    }

    private fun showProviderSetup(required: Boolean, message: String? = null) {
        if (isFinishing) return
        setupDialog?.dismiss()
        setupRequired = required

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        content.addView(label("Media server"))
        val providers = MediaProviderType.entries
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.displayName },
        )
        val savedProvider = runCatching {
            MediaProviderType.valueOf(
                preferences.getString(PlexAuthManager.KEY_PROVIDER, MediaProviderType.PLEX.name)
                    ?: MediaProviderType.PLEX.name
            )
        }.getOrDefault(MediaProviderType.PLEX)
        spinner.setSelection(providers.indexOf(savedProvider).coerceAtLeast(0))
        content.addView(spinner)

        val jellyfinUrlLabel = label("Jellyfin server URL")
        val jellyfinUrlInput = input(
            preferences.getString(PlexAuthManager.KEY_SERVER_URL, "").orEmpty()
                .takeIf { savedProvider == MediaProviderType.JELLYFIN }
                ?: ""
        ).apply {
            hint = "http://192.168.0.10:8096"
        }
        content.addView(jellyfinUrlLabel)
        content.addView(jellyfinUrlInput)

        val help = TextView(this).apply {
            text = message ?: "Choose the media server platform. SideSubs will use that provider's own sign-in flow."
            setTextColor(0xFF999999.toInt())
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(help)

        val setupProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        content.addView(
            setupProgress,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            },
        )
        setupProgressView = setupProgress

        if (mediaProvider == null) {
            val signInAgain = Button(this).apply {
                text = "Sign in again"
                isAllCaps = false
                visibility = if (plexAuth.hasAccountToken()) View.VISIBLE else View.GONE
                setOnClickListener {
                    handler.removeCallbacks(authPollTask)
                    pendingPlexLogin = null
                    plexLoginAuthorized = false
                    plexAuth.signOut()
                    showProviderSetup(required = required)
                }
            }
            content.addView(signInAgain)
            setupSignInAgainButton = signInAgain
        }
        setupStatusView = help

        val builder = AlertDialog.Builder(this)
            .setCustomTitle(dialogTitleWithMenu("Connect SideSubs"))
            .setView(content)
            .setPositiveButton("Continue", null)
            .setNeutralButton("Copy code", null)
            .setNegativeButton("Cancel", null)

        val dialog = builder.create().apply {
            setCancelable(!required)
            setCanceledOnTouchOutside(!required)
        }
        setupDialog = dialog

        dialog.setOnShowListener {
            val signIn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val copyCode = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val defaultHelp =
                message ?: "Choose the media server platform. SideSubs will use that provider's own sign-in flow."

            fun cancelPlexLogin() {
                plexLoginGeneration++
                handler.removeCallbacks(authPollTask)
                pendingPlexLogin = null
                plexLoginAuthorized = false
            }

            fun resetPlexLoginUi() {
                cancelPlexLogin()
                copyCode.visibility = View.GONE
                cancel.visibility = if (required) View.GONE else View.VISIBLE
                help.text = defaultHelp
                setupProgress.visibility = View.GONE
                signIn.text =
                    if (plexAuth.hasAccountToken()) "Find Plex servers" else "Sign in with Plex"
                signIn.isEnabled = true
                setupSignInAgainButton?.visibility =
                    if (plexAuth.hasAccountToken()) View.VISIBLE else View.GONE
            }

            fun resetJellyfinLoginUi() {
                jellyfinLoginGeneration++
                handler.removeCallbacks(jellyfinAuthPollTask)
                pendingJellyfinLogin = null
                copyCode.visibility = View.GONE
                cancel.visibility = if (required) View.GONE else View.VISIBLE
                help.text = defaultHelp
                setupProgress.visibility = View.GONE
                signIn.isEnabled = true
                val providerType = providers[spinner.selectedItemPosition]
                signIn.text = when (providerType) {
                    MediaProviderType.PLEX ->
                        if (plexAuth.hasAccountToken()) "Find Plex servers" else "Sign in with Plex"
                    MediaProviderType.JELLYFIN -> "Connect to Jellyfin"
                }
            }

            fun refreshProviderUi() {
                val providerType = providers[spinner.selectedItemPosition]
                if (providerType != MediaProviderType.JELLYFIN) resetJellyfinLoginUi()
                if (
                    providerType != MediaProviderType.PLEX &&
                    (pendingPlexLogin != null || !signIn.isEnabled)
                ) resetPlexLoginUi()
                val jellyfin = providerType == MediaProviderType.JELLYFIN
                jellyfinUrlLabel.visibility = if (jellyfin) View.VISIBLE else View.GONE
                jellyfinUrlInput.visibility = if (jellyfin) View.VISIBLE else View.GONE
                signIn.text = when (providerType) {
                    MediaProviderType.PLEX ->
                        if (plexAuth.hasAccountToken()) "Find Plex servers" else "Sign in with Plex"
                    MediaProviderType.JELLYFIN -> "Connect to Jellyfin"
                }
                signIn.isEnabled = true
                copyCode.visibility = View.GONE
                cancel.visibility = if (required) View.GONE else View.VISIBLE
                help.text = defaultHelp
                setupSignInAgainButton?.visibility =
                    if (!jellyfin && plexAuth.hasAccountToken()) View.VISIBLE else View.GONE
            }

            copyCode.visibility = View.GONE
            cancel.visibility = if (required) View.GONE else View.VISIBLE

            copyCode.setOnClickListener {
                val code = pendingJellyfinLogin?.code.orEmpty()
                if (code.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Jellyfin Quick Connect code", code))
                    Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
                }
            }

            cancel.setOnClickListener {
                val providerType = providers[spinner.selectedItemPosition]
                when {
                    providerType == MediaProviderType.JELLYFIN &&
                        (pendingJellyfinLogin != null || !signIn.isEnabled) ->
                        resetJellyfinLoginUi()
                    providerType == MediaProviderType.PLEX &&
                        (pendingPlexLogin != null || !signIn.isEnabled) ->
                        resetPlexLoginUi()
                    !required -> dialog.dismiss()
                }
            }

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshProviderUi()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            refreshProviderUi()

            signIn.setOnClickListener {
                val providerType = providers[spinner.selectedItemPosition]
                when (providerType) {
                    MediaProviderType.PLEX -> {
                        if (plexAuth.hasAccountToken()) {
                            loadServerChooser(required = required)
                        } else {
                            signIn.isEnabled = false
                            cancel.visibility = View.VISIBLE
                            cancelPlexLogin()
                            setupProgress.visibility = View.VISIBLE
                            help.text = "Opening Plex sign-in…"
                            val generation = ++plexLoginGeneration
                            beginPlexSignIn(help, signIn, generation)
                        }
                    }

                    MediaProviderType.JELLYFIN -> {
                        signIn.isEnabled = false
                        copyCode.visibility = View.GONE
                        cancel.visibility = View.VISIBLE
                        setupProgress.visibility = View.VISIBLE
                        help.text = "Connecting to Jellyfin…"
                        val generation = ++jellyfinLoginGeneration
                        beginJellyfinSignIn(
                            jellyfinUrlInput.text.toString(),
                            help,
                            signIn,
                            copyCode,
                            cancel,
                            generation,
                        )
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            if (setupDialog === dialog) {
                handler.removeCallbacks(authPollTask)
                handler.removeCallbacks(jellyfinAuthPollTask)
                plexLoginGeneration++
                jellyfinLoginGeneration++
                pendingJellyfinLogin = null
                pendingPlexLogin = null
                plexLoginAuthorized = false
                setupDialog = null
                setupStatusView = null
                setupProgressView = null
                setupSignInAgainButton = null
            }
        }
        dialog.show()
    }

    private val jellyfinAuthPollTask = object : Runnable {
        override fun run() {
            val pending = pendingJellyfinLogin ?: return
            val generation = jellyfinLoginGeneration
            if (!appInForeground) return
            val dialog = setupDialog
            if (authPollInFlight) {
                handler.postDelayed(this, AUTH_POLL_INTERVAL_MS)
                return
            }

            authPollInFlight = true
            executor.execute {
                try {
                    val connection = jellyfinAuth.pollLogin(pending)
                    if (connection == null) {
                        if (appInForeground) handler.postDelayed(this, AUTH_POLL_INTERVAL_MS)
                        return@execute
                    }
                    if (pendingJellyfinLogin !== pending || generation != jellyfinLoginGeneration) return@execute
                    runOnUiThread {
                        if (
                            setupDialog !== dialog ||
                            !appInForeground ||
                            pendingJellyfinLogin !== pending ||
                            generation != jellyfinLoginGeneration
                        ) return@runOnUiThread
                        pendingJellyfinLogin = null
                        jellyfinAuth.saveConnection(connection)
                        setupDialog?.dismiss()
                        connectProvider(connection)
                    }
                } catch (error: Exception) {
                    if (pendingJellyfinLogin !== pending || generation != jellyfinLoginGeneration) return@execute
                    pendingJellyfinLogin = null
                    diagnostics.add("Jellyfin sign-in failed: ${error.javaClass.simpleName}")
                    runOnUiThread {
                        if (setupDialog !== dialog) return@runOnUiThread
                        setupStatusView?.text = "Jellyfin sign-in: ${friendlyError(error)}"
                        setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                            text = "Connect to Jellyfin"
                            isEnabled = true
                        }
                        setupDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
                        setupDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility =
                            if (setupRequired) View.GONE else View.VISIBLE
                    }
                } finally {
                    authPollInFlight = false
                }
            }
        }
    }

    private fun beginJellyfinSignIn(
        serverUrl: String,
        status: TextView,
        button: Button,
        copyCodeButton: Button,
        cancelButton: Button,
        generation: Long,
    ) {
        executor.execute {
            try {
                val pending = jellyfinAuth.beginLogin(serverUrl)
                if (generation != jellyfinLoginGeneration) return@execute
                pendingJellyfinLogin = pending
                runOnUiThread {
                    if (generation != jellyfinLoginGeneration || setupDialog == null) return@runOnUiThread
                    setupProgressView?.visibility = View.GONE
                    status.text =
                        "Quick Connect code: ${pending.code}\n\n" +
                            "Open Jellyfin Settings → Quick Connect, enter this code and approve SideSubs."
                    button.text = "Waiting for approval…"
                    button.isEnabled = false
                    copyCodeButton.visibility = View.VISIBLE
                    cancelButton.visibility = View.VISIBLE
                    handler.removeCallbacks(jellyfinAuthPollTask)
                    handler.post(jellyfinAuthPollTask)
                }
            } catch (error: Exception) {
                if (generation != jellyfinLoginGeneration) return@execute
                diagnostics.add("Start Jellyfin sign-in failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    if (generation != jellyfinLoginGeneration) return@runOnUiThread
                    pendingJellyfinLogin = null
                    setupProgressView?.visibility = View.GONE
                    status.text = "Jellyfin: ${friendlyError(error)}"
                    button.text = "Connect to Jellyfin"
                    button.isEnabled = true
                    copyCodeButton.visibility = View.GONE
                    cancelButton.visibility = if (setupRequired) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun beginPlexSignIn(status: TextView, button: Button, generation: Long) {
        executor.execute {
            try {
                val pending = plexAuth.beginLogin()
                if (generation != plexLoginGeneration) return@execute
                pendingPlexLogin = pending
                runOnUiThread {
                    if (generation != plexLoginGeneration || setupDialog == null) return@runOnUiThread
                    setupProgressView?.visibility = View.GONE
                    status.text = "Complete sign-in in your browser, then return to SideSubs."
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pending.authUrl))
                    startActivity(intent)
                    handler.removeCallbacks(authPollTask)
                    handler.post(authPollTask)
                }
            } catch (error: Exception) {
                if (generation != plexLoginGeneration) return@execute
                diagnostics.add("Start Plex sign-in failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    if (generation != plexLoginGeneration) return@runOnUiThread
                    pendingPlexLogin = null
                    setupProgressView?.visibility = View.GONE
                    status.text = "Plex sign-in: ${friendlyAuthError(error)}"
                    button.isEnabled = true
                }
            }
        }
    }

    private fun showServerChooser(
        servers: List<PlexServerResource>,
        required: Boolean,
        retryAfterLogin: Boolean = false,
    ) {
        val labels = servers.map { server ->
            val local = server.connections.any { it.local && !it.relay }
            "${server.name}${if (local) " · Local" else ""}"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose Plex server")
            .setItems(labels) { d, which ->
                d.dismiss()
                connectToServer(servers[which], servers, required, retryAfterLogin)
            }
            .setNegativeButton(if (required) "Back" else "Cancel") { _, _ ->
                if (required) showProviderSetup(required = true)
            }
            .create()

        dialog.setCancelable(!required)
        dialog.setCanceledOnTouchOutside(!required)
        dialog.show()
    }

    private fun connectToServer(
        server: PlexServerResource,
        servers: List<PlexServerResource>,
        required: Boolean,
        retryAfterLogin: Boolean = false,
    ) {
        stateView.text = "Checking connections to ${server.name}…"
        val cancelled = AtomicBoolean(false)
        val progressText = TextView(this).apply {
            text = "Checking available server addresses…"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
        }
        val progressBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(
                ProgressBar(this@MainActivity).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    rightMargin = dp(16)
                },
            )
            addView(
                progressText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("Connecting to ${server.name}")
            .setView(progressBody)
            .setNegativeButton("Cancel") { _, _ ->
                cancelled.set(true)
                stateView.text = if (mediaProvider == null) "Choose a media server"
                    else "Connected to ${mediaProvider?.serverName}"
                if (mediaProvider == null) showProviderSetup(required = true)
            }
            .create()
        progress.setCancelable(false)
        progress.show()

        executor.execute {
            try {
                val connection = try {
                    plexAuth.selectServer(server, persist = false)
                } catch (error: Exception) {
                    if (
                        !retryAfterLogin ||
                        cancelled.get() ||
                        !appInForeground ||
                        error.message?.contains("None of the connections advertised") != true
                    ) throw error
                    diagnostics.add("Initial post-login Plex probe failed; refreshing server addresses once")
                    runOnUiThread {
                        if (progress.isShowing) progressText.text = "Refreshing Plex server addresses…"
                    }
                    val refreshed = plexAuth.listServers().firstOrNull { it.id == server.id }
                        ?: throw error
                    if (cancelled.get()) return@execute
                    plexAuth.selectServer(refreshed, persist = false)
                }
                runOnUiThread {
                    if (!progress.isShowing) return@runOnUiThread
                    plexAuth.saveConnection(connection)
                    progress.dismiss()
                    connectProvider(connection)
                }
            } catch (error: Exception) {
                diagnostics.add("Connect to selected server failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    if (!progress.isShowing) return@runOnUiThread
                    progress.dismiss()
                    stateView.text = if (mediaProvider == null) "Choose a media server"
                        else "Connected to ${mediaProvider?.serverName}"
                    var navigating = false
                    AlertDialog.Builder(this)
                        .setTitle("Unable to connect to ${server.name}")
                        .setMessage(friendlyServerError(error))
                        .setPositiveButton("Retry") { _, _ ->
                            navigating = true
                            connectToServer(server, servers, required)
                        }
                        .setNeutralButton(if (servers.size > 1) "Choose server" else "Reload servers") { _, _ ->
                            navigating = true
                            if (servers.size > 1) showServerChooser(servers, required)
                            else loadServerChooser(required)
                        }
                        .setNegativeButton("Close", null)
                        .setOnDismissListener {
                            if (!navigating && mediaProvider == null) {
                                showProviderSetup(required = true, message = friendlyServerError(error))
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun friendlyServerError(error: Throwable): String = when {
        error.message?.contains("None of the connections advertised") == true ->
            "Plex found your server, but this phone cannot reach any of its addresses. Check that the phone is on the same network, or enable remote access in Plex."
        error.message?.contains("rejected the discovered authorization") == true ->
            "Plex found your server, but it rejected the authorization. Try signing in again."
        else -> friendlyAuthError(error)
    }

    private fun connectProvider(connection: ProviderConnection) {
        val provider = when (connection.provider) {
            MediaProviderType.PLEX -> PlexClient(
                baseUrl = connection.baseUrl,
                token = connection.accessToken,
                clientIdentifier = connection.clientIdentifier,
                serverName = connection.serverName,
                diagnostics = diagnostics,
            )

            MediaProviderType.JELLYFIN -> JellyfinClient(
                baseUrl = connection.baseUrl,
                token = connection.accessToken,
                userId = connection.userId,
                deviceId = connection.clientIdentifier,
                serverName = connection.serverName,
                diagnostics = diagnostics,
            )
        }

        mediaProvider = provider
        loggedSessionCount = -1
        loggedSessionState = ""
        loggedPollError = ""
        diagnostics.add("Connected to ${connection.provider.displayName} server")
        playbackClock.clear()
        clearLoadedSubtitle()
        stateView.text = "Connecting to ${connection.serverName}…"
        Toast.makeText(this, "Connected to ${connection.serverName}", Toast.LENGTH_SHORT).show()
        startPolling()
        maybeShowPendingUpdateNotice()
    }

    private fun startPolling() {
        handler.removeCallbacks(pollTask)
        playbackGeneration++
        if (appInForeground) handler.post(pollTask)
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollTask)
        playbackGeneration++
        if (mediaProvider != null) diagnostics.add("Playback polling paused")
    }

    private fun isCurrentPlayback(provider: MediaProvider, generation: Long): Boolean =
        appInForeground && generation == playbackGeneration && mediaProvider === provider

    private fun pollOnce() {
        if (!appInForeground) return
        val provider = mediaProvider ?: return
        if (pollInFlight) return
        pollInFlight = true
        val generation = playbackGeneration

        executor.execute {
            try {
                if (!isCurrentPlayback(provider, generation)) return@execute
                val freshSessions = provider.sessions()
                if (!isCurrentPlayback(provider, generation)) return@execute
                runOnUiThread {
                    if (isCurrentPlayback(provider, generation)) sessions = freshSessions
                }
                if (freshSessions.size != loggedSessionCount) {
                    loggedSessionCount = freshSessions.size
                    diagnostics.add("Playback sessions available: ${freshSessions.size}")
                }
                val session = chooseSession(freshSessions)
                val sessionState = if (session == null) "none"
                    else "${session.playerId}|${session.mediaId}|${session.state}"
                if (sessionState != loggedSessionState) {
                    loggedSessionState = sessionState
                    diagnostics.add(
                        if (session == null) "No active playback session"
                        else "Playback session: media=${session.mediaId} state=${session.state}"
                    )
                }
                if (session == null) {
                    loggedPollError = ""
                    runOnUiThread {
                        if (!isCurrentPlayback(provider, generation)) return@runOnUiThread
                        sessions = freshSessions
                        selectedSession = null
                        titleView.text = "SideSubs"
                        stateView.text = "No active ${provider.providerType.displayName} session · ${provider.serverName}"
                        currentSubtitleView.text = ""
                        nextSubtitleView.text = ""
                        sessionButton.text = "Session"
                    }
                    return@execute
                }

                val position = playbackClock.smooth(
                    session.clockKey(),
                    session.position,
                    session.state,
                )

                if (!isCurrentPlayback(provider, generation)) return@execute
                var freshTracks = tracks
                var track = selectedTrack
                var freshTimeline = timeline
                val needsTimeline = session.mediaId != loadedMediaId

                if (needsTimeline) {
                    freshTracks = provider.subtitleTracks(session.mediaId)
                    if (!isCurrentPlayback(provider, generation)) return@execute
                    track = chooseTrack(session.mediaId, freshTracks)
                    diagnostics.add(
                        "Subtitle tracks: media=${session.mediaId} total=${freshTracks.size} " +
                            "compatible=${freshTracks.count { it.compatible }} " +
                            "chosen=${track?.language ?: "none"}"
                    )
                    freshTimeline = track?.let { provider.subtitleTimeline(session.mediaId, it) }
                } else {
                    val wantedTrackId = preferredTrackId(session.mediaId)
                    if (track == null || (wantedTrackId.isNotEmpty() && wantedTrackId != track.id)) {
                        freshTracks = provider.subtitleTracks(session.mediaId)
                        if (!isCurrentPlayback(provider, generation)) return@execute
                        track = chooseTrack(session.mediaId, freshTracks)
                        freshTimeline = track?.let { provider.subtitleTimeline(session.mediaId, it) }
                    }
                }

                if (!isCurrentPlayback(provider, generation)) return@execute
                loggedPollError = ""
                runOnUiThread {
                    if (!isCurrentPlayback(provider, generation)) return@runOnUiThread
                    applyPlaybackState(
                        freshSessions,
                        session,
                        freshTracks,
                        track,
                        freshTimeline,
                        position,
                    )
                }
            } catch (error: Exception) {
                if (!isCurrentPlayback(provider, generation)) return@execute
                val reason = Regex("(Plex|Jellyfin) HTTP [0-9]{3}").find(error.message.orEmpty())?.value
                    ?: error.javaClass.simpleName
                if (reason != loggedPollError) {
                    loggedPollError = reason
                    diagnostics.add("Playback update failed: $reason")
                }
                runOnUiThread {
                    if (!isCurrentPlayback(provider, generation)) return@runOnUiThread
                    stateView.text = "${provider.providerType.displayName}: ${friendlyError(error)}"
                }
            } finally {
                pollInFlight = false
            }
        }
    }

    private fun chooseSession(items: List<PlaybackSession>): PlaybackSession? {
        if (items.isEmpty()) return null
        val selectedPlayerId = preferences.getString(KEY_PLAYER_ID, "").orEmpty()
        if (selectedPlayerId.isNotBlank()) {
            return items.firstOrNull { it.playerId == selectedPlayerId }
        }
        return items.firstOrNull { it.state.equals("playing", ignoreCase = true) } ?: items.first()
    }

    private fun chooseTrack(mediaId: String, available: List<SubtitleTrack>): SubtitleTrack? {
        val manual = preferredTrackId(mediaId)
        if (manual.isNotEmpty()) {
            available.firstOrNull { it.compatible && it.id == manual }?.let { return it }
        }
        val language = preferredLanguage()
        val compatible = available.filter { it.compatible }
        if (language.isBlank()) return compatible.firstOrNull()
        return compatible.firstOrNull { trackLanguageMatches(it, language, exactRegion = true) }
            ?: compatible.firstOrNull { trackLanguageMatches(it, language, exactRegion = false) }
    }

    private fun preferredTrackId(mediaId: String): String {
        val provider = mediaProvider?.providerType?.name ?: "UNKNOWN"
        return preferences.getString("track_${provider}_$mediaId", "").orEmpty()
    }

    private fun trackPreferenceKey(mediaId: String): String {
        val provider = mediaProvider?.providerType?.name ?: "UNKNOWN"
        return "track_${provider}_$mediaId"
    }

    private fun applyPlaybackState(
        freshSessions: List<PlaybackSession>,
        session: PlaybackSession,
        freshTracks: List<SubtitleTrack>,
        track: SubtitleTrack?,
        freshTimeline: SubtitleTimeline?,
        position: Double,
    ) {
        sessions = freshSessions
        selectedSession = session
        tracks = freshTracks
        selectedTrack = track
        timeline = freshTimeline
        loadedMediaId = session.mediaId

        titleView.text = session.title
        stateView.text = "${session.displayClient()} · ${formatPlaybackTime(position)} · ${session.state}"
        sessionButton.text = session.displayClient()
        subtitleButton.text = when {
            track != null -> track.label()
            tracks.isEmpty() -> "No subtitles"
            else -> "Choose subtitles"
        }
        subtitleButton.isEnabled = tracks.isNotEmpty()

        maybeShowLanguageFallback(session.mediaId, track)

        val delayMs = preferences.getInt(KEY_DELAY_MS, 1000)
        val effectivePosition = max(0.0, position - delayMs / 1000.0)
        val (current, next) = cuePair(freshTimeline?.cues, effectivePosition)
        currentSubtitleView.text = current?.text.orEmpty()
        nextSubtitleView.text = next?.text.orEmpty()
    }

    private fun cuePair(cues: List<Cue>?, position: Double): Pair<Cue?, Cue?> {
        if (cues == null) return null to null
        var current: Cue? = null
        var next: Cue? = null

        for (cue in cues) {
            if (cue.start <= position && position <= cue.end + 1.5) {
                current = cue
                continue
            }
            if (cue.start > position) {
                next = cue
                break
            }
        }

        val preview = next?.takeIf { upcoming ->
            if (current != null) {
                upcoming.start - current.end <= NEXT_PREVIEW_SECONDS
            } else {
                upcoming.start - position <= NEXT_PREVIEW_SECONDS
            }
        }

        return current to preview
    }

    private fun refreshSessionsAndShowChooser() {
        val provider = mediaProvider
        if (provider == null) {
            Toast.makeText(this, "No media server connected", Toast.LENGTH_SHORT).show()
            return
        }

        sessionButton.isEnabled = false
        sessionRefreshExecutor.execute {
            try {
                val freshSessions = provider.sessions()
                runOnUiThread {
                    if (mediaProvider !== provider) return@runOnUiThread
                    sessions = freshSessions
                    sessionButton.isEnabled = true
                    showSessionChooser()
                }
            } catch (error: Exception) {
                diagnostics.add("Session refresh failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    sessionButton.isEnabled = true
                    Toast.makeText(
                        this,
                        "Could not refresh playback sessions",
                        Toast.LENGTH_SHORT,
                    ).show()
                    showSessionChooser()
                }
            }
        }
    }

    private fun showSessionChooser() {
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No playback sessions available", Toast.LENGTH_SHORT).show()
            return
        }

        val saved = preferences.getString(KEY_PLAYER_ID, "").orEmpty()
        val activePlayerId = selectedSession?.playerId
            ?: chooseSession(sessions)?.playerId
            ?: saved
        val labels = sessions.map { "${it.displayClient()}\n${it.title}" }.toTypedArray()
        val checked = sessions.indexOfFirst { it.playerId == activePlayerId }

        AlertDialog.Builder(this)
            .setTitle("Playback session")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val item = sessions[which]
                diagnostics.add("Playback session selected: media=${item.mediaId} state=${item.state}")
                preferences.edit().putString(KEY_PLAYER_ID, item.playerId).apply()
                playbackClock.clear()
                clearLoadedSubtitle()
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "Following ${item.displayClient()}",
                    Toast.LENGTH_SHORT,
                ).show()
                pollOnce()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleChooser() {
        val session = selectedSession
        if (session == null || tracks.isEmpty()) {
            Toast.makeText(this, "No subtitle tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        val compatible = tracks.filter { it.compatible }
        val manual = preferredTrackId(session.mediaId)
        val labels = buildList {
            add("Auto (${preferredLanguage().uppercase(Locale.US)})")
            compatible.forEach { add(it.label()) }
        }.toTypedArray()
        val checked = if (manual.isEmpty()) 0 else {
            compatible.indexOfFirst { it.id == manual }.let { if (it >= 0) it + 1 else -1 }
        }

        AlertDialog.Builder(this)
            .setTitle("Subtitle track")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                diagnostics.add(
                    if (which == 0) "Subtitle selection: automatic for media=${session.mediaId}"
                    else "Subtitle selection: media=${session.mediaId} " +
                        "language=${compatible[which - 1].language} source=${compatible[which - 1].source}"
                )
                preferences.edit().apply {
                    if (which == 0) remove(trackPreferenceKey(session.mediaId))
                    else putString(trackPreferenceKey(session.mediaId), compatible[which - 1].id)
                }.apply()
                selectedTrack = null
                timeline = null
                dialog.dismiss()
                val subtitleToast = if (which == 0) {
                    "Subtitles set to automatic"
                } else {
                    "Subtitles: ${compatible[which - 1].label()}"
                }
                Toast.makeText(this, subtitleToast, Toast.LENGTH_SHORT).show()
                pollOnce()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDelayControls() {
        delayControlsOpen = true
        updateDelayDisplay()
        delayControls.visibility = View.VISIBLE
        scheduleDelayControlsHide()
        if (cinemaMode) showCinemaChromeTemporarily()
    }

    private fun adjustDelay(deltaMs: Int) {
        val current = preferences.getInt(KEY_DELAY_MS, 1000)
        val updatedDelay = (current + deltaMs).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        if (updatedDelay != current) {
            preferences.edit().putInt(KEY_DELAY_MS, updatedDelay).apply()
            diagnostics.add("Subtitle delay set: ${updatedDelay}ms")
        }
        updateDelayDisplay()
        scheduleDelayControlsHide()
        if (cinemaMode) showCinemaChromeTemporarily()
    }

    private fun scheduleDelayControlsHide() {
        hideDelayControlsTask?.let(handler::removeCallbacks)
        hideDelayControlsTask = Runnable {
            delayControlsOpen = false
            delayControls.visibility = View.GONE
        }.also { handler.postDelayed(it, DELAY_CONTROLS_TIMEOUT_MS) }
    }

    private fun updateDelayDisplay() {
        val delay = if (::preferences.isInitialized) preferences.getInt(KEY_DELAY_MS, 1000) else 1000
        delayButton.text = ""
        if (::delayValueView.isInitialized) {
            delayValueView.text = String.format(Locale.US, "%+.2f s", delay / 1000.0)
        }
    }

    private fun showSettings() {
        val provider = mediaProvider
        if (provider == null) {
            AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleWithMenu("SideSubs settings"))
                .setMessage("No media server connected.")
                .setPositiveButton("Connect") { _, _ -> showProviderSetup(required = false) }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        content.addView(label("Media server"))
        content.addView(valueText(provider.providerType.displayName))
        val signOutButton = Button(
            this,
            null,
            android.R.attr.borderlessButtonStyle,
        ).apply {
            text = "SIGN OUT"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
        }
        content.addView(
            signOutButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40),
            ).apply {
                gravity = Gravity.END
            },
        )

        content.addView(label("Connected server"))
        content.addView(valueText(provider.serverName))
        val changeServerButton = Button(
            this,
            null,
            android.R.attr.borderlessButtonStyle,
        ).apply {
            text = "CHANGE SERVER"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
        }
        content.addView(
            changeServerButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40),
            ).apply {
                gravity = Gravity.END
            },
        )

        content.addView(label("Preferred subtitle language"))

        var selectedLanguage = preferredLanguage()
        val languageButton = Button(this).apply {
            text = languageOption(selectedLanguage).label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                showLanguageChooser(selectedLanguage) { option ->
                    selectedLanguage = option.code
                    text = option.label
                }
            }
        }
        content.addView(languageButton)

        content.addView(label("Subtitle size"))
        val subtitleSizeButton = Button(this).apply {
            text = subtitleSizeOption(preferredSubtitleSize()).label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { showSubtitleSizeChooser(this) }
        }
        content.addView(subtitleSizeButton)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(dialogTitleWithMenu("SideSubs settings"))
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val language = selectedLanguage
                preferences.edit().putString(KEY_LANGUAGE, language).apply()
                diagnostics.add("Preferred subtitle language set: ${language.ifBlank { "automatic" }}")
                clearLoadedSubtitle()
                dialog.dismiss()
                pollOnce()
            }
            changeServerButton.setOnClickListener {
                dialog.dismiss()
                when (provider.providerType) {
                    MediaProviderType.PLEX -> loadServerChooser()
                    MediaProviderType.JELLYFIN -> showProviderSetup(required = false)
                }
            }
            signOutButton.setOnClickListener {
                dialog.dismiss()
                confirmSignOut()
            }
        }
        dialog.show()
    }

    private fun dialogTitleWithMenu(title: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(8), dp(4))

            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(0, dp(48), 1f),
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = "⋮"
                    contentDescription = "More options"
                    setTextColor(0xFFB0B0B0.toInt())
                    textSize = 28f
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    minWidth = dp(48)
                    minHeight = dp(48)
                    setOnClickListener { anchor ->
                        PopupMenu(this@MainActivity, anchor).apply {
                            menu.add("About")
                            menu.add("View logs")
                            setOnMenuItemClickListener { item ->
                                when (item.title.toString()) {
                                    "About" -> {
                                        showAbout()
                                        true
                                    }
                                    "View logs" -> {
                                        showDiagnosticLog()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            show()
                        }
                    }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
    }

    private fun showAbout() {
        val provider = mediaProvider
        val session = selectedSession
        val installedVersion = appVersionName()
        var updateStatusView: TextView? = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))

            addView(
                TextView(this@MainActivity).apply {
                    text = "Open-source second-screen subtitles for Plex and Jellyfin."
                    setTextColor(0xFFCCCCCC.toInt())
                    textSize = 14f
                    setPadding(0, 0, 0, dp(8))
                }
            )

            addView(label("Version"))
            addView(valueText(installedVersion))

            if (!installedVersion.contains("-dev", ignoreCase = true)) {
                addView(label("Update"))
                updateStatusView = TextView(this@MainActivity).apply {
                    text = "Checking…"
                    setTextColor(0xFFCCCCCC.toInt())
                    textSize = 15f
                }
                addView(updateStatusView)
            }

            if (provider != null) {
                addView(label("Media server"))
                addView(valueText(provider.providerType.displayName))

                addView(label("Connected server"))
                addView(valueText(provider.serverName))

                if (provider.serverAddress.isNotBlank()) {
                    addView(label("Server address"))
                    addView(valueText(provider.serverAddress))
                }
            }

            if (session != null) {
                addView(label("Connected client"))
                addView(valueText(session.displayClient()))

                addView(label("Now playing"))
                addView(valueText(session.title))
            }

            addView(label("GitHub"))
            addView(
                TextView(this@MainActivity).apply {
                    text = "github.com/soyxan/sidesubs"
                    setTextColor(0xFF90CAF9.toInt())
                    textSize = 15f
                    isClickable = true
                    isFocusable = true
                    setPadding(0, dp(2), 0, dp(4))
                    setOnClickListener {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/soyxan/sidesubs"),
                            )
                        )
                    }
                }
            )
        }

        AlertDialog.Builder(this)
            .setTitle("About SideSubs")
            .setView(content)
            .setPositiveButton("Close", null)
            .show()

        updateStatusView?.let { checkForUpdatesFromAbout(installedVersion, it) }
    }

    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"

    private fun checkForUpdatesFromAbout(installedVersion: String, statusView: TextView) {
        preferences.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, System.currentTimeMillis()).apply()
        executor.execute {
            try {
                val connection = URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 8_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "SideSubs-Android")

                val status = connection.responseCode
                val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    .orEmpty()
                connection.disconnect()
                if (status !in 200..299) error("GitHub HTTP $status")

                val json = JSONObject(payload)
                val latestVersion = json.optString("tag_name").removePrefix("v").trim()
                val releaseUrl = json.optString("html_url").trim()
                if (latestVersion.isBlank()) error("GitHub release has no version")

                preferences.edit()
                    .putString(KEY_LATEST_VERSION, latestVersion)
                    .putString(KEY_LATEST_RELEASE_URL, releaseUrl)
                    .apply()

                runOnUiThread {
                    val updateAvailable = isVersionNewer(latestVersion, installedVersion)
                    statusView.text =
                        if (updateAvailable) "$latestVersion available" else "Up to date"
                    statusView.setTextColor(
                        if (updateAvailable) 0xFF90CAF9.toInt() else 0xFFCCCCCC.toInt()
                    )
                    statusView.isClickable = updateAvailable && releaseUrl.isNotBlank()
                    statusView.isFocusable = statusView.isClickable
                    statusView.setOnClickListener(
                        if (statusView.isClickable) {
                            View.OnClickListener {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
                            }
                        } else {
                            null
                        }
                    )
                }
            } catch (error: Exception) {
                diagnostics.add("About update check failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    statusView.text = "Could not check for updates"
                    statusView.setTextColor(0xFFCCCCCC.toInt())
                    statusView.isClickable = false
                    statusView.isFocusable = false
                    statusView.setOnClickListener(null)
                }
            }
        }
    }

    private fun checkForUpdatesIfNeeded() {
        val installedVersion = appVersionName()
        if (installedVersion.contains("-dev", ignoreCase = true)) return

        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L)
        if (now - lastCheck < UPDATE_CHECK_INTERVAL_MS) {
            prepareCachedUpdateNotice(installedVersion)
            return
        }

        preferences.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, now).apply()
        executor.execute {
            try {
                val connection = URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 8_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "SideSubs-Android")

                val status = connection.responseCode
                val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    .orEmpty()
                connection.disconnect()
                if (status !in 200..299) error("GitHub HTTP $status")

                val json = JSONObject(payload)
                val latestVersion = json.optString("tag_name").removePrefix("v").trim()
                val releaseUrl = json.optString("html_url").trim()
                if (latestVersion.isBlank()) return@execute

                preferences.edit()
                    .putString(KEY_LATEST_VERSION, latestVersion)
                    .putString(KEY_LATEST_RELEASE_URL, releaseUrl)
                    .apply()

                if (isVersionNewer(latestVersion, installedVersion)) {
                    pendingUpdateNotice = UpdateInfo(latestVersion, releaseUrl)
                    runOnUiThread { maybeShowPendingUpdateNotice() }
                }
            } catch (error: Exception) {
                diagnostics.add("Update check failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun prepareCachedUpdateNotice(installedVersion: String) {
        val latestVersion = preferences.getString(KEY_LATEST_VERSION, "").orEmpty()
        val releaseUrl = preferences.getString(KEY_LATEST_RELEASE_URL, "").orEmpty()
        if (latestVersion.isNotBlank() && isVersionNewer(latestVersion, installedVersion)) {
            pendingUpdateNotice = UpdateInfo(latestVersion, releaseUrl)
            maybeShowPendingUpdateNotice()
        }
    }

    private fun maybeShowPendingUpdateNotice() {
        val update = pendingUpdateNotice ?: return
        if (!appInForeground || setupDialog != null || isFinishing) return
        if (preferences.getString(KEY_LAST_NOTIFIED_VERSION, "").orEmpty() == update.version) {
            pendingUpdateNotice = null
            return
        }

        pendingUpdateNotice = null
        preferences.edit().putString(KEY_LAST_NOTIFIED_VERSION, update.version).apply()

        AlertDialog.Builder(this)
            .setTitle("SideSubs update available")
            .setMessage(
                "Version ${update.version} is available.\n" +
                    "You are currently using ${appVersionName()}."
            )
            .setNegativeButton("Later", null)
            .setPositiveButton("View update") { _, _ ->
                if (update.url.isNotBlank()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                }
            }
            .show()
    }

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        val a = candidate.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private data class UpdateInfo(
        val version: String,
        val url: String,
    )

    private fun showDiagnosticLog() {
        val log = diagnostics.read()
        val view = TextView(this).apply {
            text = "Server addresses may appear here. PINs and tokens are not logged.\n\n$log"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle("SideSubs log")
            .setView(scroll)
            .setPositiveButton("Copy log") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SideSubs log", log))
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadServerChooser(required: Boolean = false) {
        if (setupDialog == null) showProviderSetup(required, "Finding Plex servers…")
        setupStatusView?.text = "Finding Plex servers…"
        setupProgressView?.visibility = View.VISIBLE
        setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        val dialog = setupDialog
        executor.execute {
            try {
                val servers = plexAuth.listServers()
                runOnUiThread {
                    if (setupDialog !== dialog) return@runOnUiThread
                    if (servers.isEmpty()) {
                        setupProgressView?.visibility = View.GONE
                        setupStatusView?.text = "No Plex Media Servers found in your account."
                        setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                    } else {
                        setupDialog?.dismiss()
                        showServerChooser(servers, required)
                    }
                }
            } catch (error: Exception) {
                diagnostics.add("Load Plex servers failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    if (setupDialog !== dialog) return@runOnUiThread
                    setupProgressView?.visibility = View.GONE
                    setupStatusView?.text = friendlyAuthError(error)
                    setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                }
            }
        }
    }

    private fun confirmSignOut() {
        val providerType = mediaProvider?.providerType ?: MediaProviderType.PLEX
        AlertDialog.Builder(this)
            .setTitle("Sign out of ${providerType.displayName}?")
            .setMessage("SideSubs will remove the saved ${providerType.displayName} authorization and server selection from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ ->
                stopPolling()
                when (providerType) {
                    MediaProviderType.PLEX -> plexAuth.signOut()
                    MediaProviderType.JELLYFIN -> jellyfinAuth.signOut()
                }
                mediaProvider = null
                sessions = emptyList()
                selectedSession = null
                clearLoadedSubtitle()
                titleView.text = "SideSubs"
                stateView.text = "Choose a media server"
                currentSubtitleView.text = ""
                nextSubtitleView.text = ""
                showProviderSetup(required = true)
            }
            .show()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun valueText(value: String) = TextView(this).apply {
        text = value
        setTextColor(0xFFCCCCCC.toInt())
        textSize = 15f
    }

    private fun input(value: String) = EditText(this).apply {
        setSingleLine(true)
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF666666.toInt())
        backgroundTintList = ColorStateList.valueOf(0xFF888888.toInt())
    }

    private fun preferredLanguage(): String {
        val stored = preferences.getString(KEY_LANGUAGE, null)
        if (stored.isNullOrBlank() || stored.equals("es", ignoreCase = true)) {
            if (stored != "es-ES") {
                preferences.edit().putString(KEY_LANGUAGE, "es-ES").apply()
            }
            return "es-ES"
        }
        return stored
    }

    private fun languageOption(code: String): LanguageOption {
        LANGUAGE_OPTIONS.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { return it }
        return when (code.lowercase(Locale.US)) {
            "es" -> LanguageOption("es", "Spanish")
            "en" -> LanguageOption("en", "English")
            "fr" -> LanguageOption("fr", "French")
            "pt" -> LanguageOption("pt", "Portuguese")
            "zh" -> LanguageOption("zh", "Chinese")
            else -> LANGUAGE_OPTIONS.firstOrNull {
                it.code.substringBefore("-").equals(code.substringBefore("-"), ignoreCase = true)
            } ?: LanguageOption(code, code.ifBlank { "Automatic" })
        }
    }

    private fun showLanguageChooser(selectedCode: String, onChoose: (LanguageOption) -> Unit) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Preferred subtitle language")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Cancel", null)
            .create()

        LANGUAGE_OPTIONS.forEach { option ->
            val row = TextView(this).apply {
                text = if (option.code == selectedCode) "${option.label}  ✓" else option.label
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                minHeight = dp(48)
                setOnClickListener {
                    onChoose(option)
                    dialog.dismiss()
                }
            }
            list.addView(row, matchWrap())
        }
        dialog.show()
    }

    private fun trackLanguageMatches(
        track: SubtitleTrack,
        preferred: String,
        exactRegion: Boolean,
    ): Boolean {
        val wanted = preferred.lowercase(Locale.US)
        val base = wanted.substringBefore("-")
        val tag = track.providerData["languageTag"].orEmpty().lowercase(Locale.US)
        if (exactRegion && wanted.contains("-")) return tag == wanted
        return track.language.equals(base, ignoreCase = true) ||
            tag.substringBefore("-").equals(base, ignoreCase = true)
    }

    private fun maybeShowLanguageFallback(mediaId: String, track: SubtitleTrack?) {
        if (track == null || preferredTrackId(mediaId).isNotEmpty()) return

        val preferred = preferredLanguage().lowercase(Locale.US)
        if (!preferred.contains("-")) return

        val actualTag = track.providerData["languageTag"]
            .orEmpty()
            .lowercase(Locale.US)
            .ifBlank { track.language.lowercase(Locale.US) }
        if (actualTag.isBlank() || actualTag == preferred) return
        if (actualTag.substringBefore("-") != preferred.substringBefore("-")) return

        val noticeKey = "$mediaId|$preferred|$actualTag"
        if (noticeKey == lastLanguageFallbackNotice) return
        lastLanguageFallbackNotice = noticeKey

        val preferredLabel = languageOption(preferred).label
        val actualLabel = languageOption(actualTag).label
        Toast.makeText(
            this,
            "$preferredLabel not available. Using $actualLabel",
            Toast.LENGTH_LONG,
        ).show()
        diagnostics.add(
            "Subtitle language fallback: preferred=$preferred actual=$actualTag media=$mediaId"
        )
    }

    private fun preferredSubtitleSize(): String =
        preferences.getString(KEY_SUBTITLE_SIZE, "medium").orEmpty().ifBlank { "medium" }

    private fun subtitleSizeOption(id: String): SubtitleSizeOption =
        SUBTITLE_SIZES.firstOrNull { it.id == id }
            ?: SUBTITLE_SIZES.first { it.id == "medium" }

    private fun applySubtitleSize(id: String) {
        val option = subtitleSizeOption(id)
        currentSubtitleView.textSize = option.currentSp
        nextSubtitleView.textSize = option.nextSp
    }

    private fun showSubtitleSizeChooser(settingsButton: Button) {
        val selectedId = preferredSubtitleSize()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Subtitle size")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Cancel", null)
            .create()

        SUBTITLE_SIZES.forEach { option ->
            val row = TextView(this).apply {
                text = if (option.id == selectedId) "${option.label}  ✓" else option.label
                setTextColor(Color.WHITE)
                textSize = option.previewSp
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                minHeight = dp(48)
                setOnClickListener {
                    preferences.edit().putString(KEY_SUBTITLE_SIZE, option.id).apply()
                    settingsButton.text = option.label
                    applySubtitleSize(option.id)
                    diagnostics.add("Subtitle size set: ${option.id}")
                    dialog.dismiss()
                }
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        dialog.show()
    }

    private fun clearLoadedSubtitle() {
        loadedMediaId = ""
        tracks = emptyList()
        selectedTrack = null
        timeline = null
        mediaProvider?.clearSubtitleCache()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun controlButton(
        label: String,
        iconRes: Int = 0,
        description: String? = null,
    ) = Button(this).apply {
        text = label
        contentDescription = description ?: label
        setTextColor(Color.WHITE)
        textSize = 12f
        isAllCaps = false
        isSingleLine = true
        setBackgroundColor(Color.TRANSPARENT)
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(2), 0, dp(2), 0)
        compoundDrawablePadding = if (label.isBlank()) 0 else dp(4)
        if (iconRes != 0) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawableTintList = textColors
        }
    }

    private fun addControl(button: Button, weight: Float) {
        controls.addView(button, LinearLayout.LayoutParams(0, dp(46), weight))
    }

    private fun addIconControl(button: Button) {
        controls.addView(button, LinearLayout.LayoutParams(dp(48), dp(46)))
    }

    private fun pillBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        setColor(0xCC171717.toInt())
    }

    private fun setCinemaMode(enabled: Boolean) {
        cinemaMode = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            enterImmersiveMode()
            showCinemaChromeTemporarily()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            exitImmersiveMode()
            hideChromeTask?.let(handler::removeCallbacks)
            setChromeVisible(true, animate = false)
        }
        root.requestApplyInsets()
    }

    private fun showCinemaChromeTemporarily() {
        if (!cinemaMode) return
        setChromeVisible(true)
        hideChromeTask?.let(handler::removeCallbacks)
        hideChromeTask = Runnable {
            if (cinemaMode) setChromeVisible(false)
        }.also { handler.postDelayed(it, 2500) }
    }

    private fun setChromeVisible(visible: Boolean, animate: Boolean = true) {
        val target = if (visible) View.VISIBLE else View.GONE
        val delayTarget = if (visible && delayControlsOpen) View.VISIBLE else View.GONE
        if (
            topBar.visibility == target &&
            controls.visibility == target &&
            delayControls.visibility == delayTarget
        ) return
        TransitionManager.endTransitions(root)
        if (animate) {
            TransitionManager.beginDelayedTransition(
                root,
                AutoTransition().apply { duration = 220L },
            )
        }
        topBar.visibility = target
        controls.visibility = target
        delayControls.visibility = delayTarget
    }

    private fun applySafeAreaInsets() {
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            var topInset = 0
            var bottomInset = 0

            if (!cinemaMode) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val insets = windowInsets.getInsets(
                        WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars() or
                            WindowInsets.Type.displayCutout()
                    )
                    topInset = insets.top
                    bottomInset = insets.bottom
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        topInset = windowInsets.systemWindowInsetTop
                        bottomInset = windowInsets.systemWindowInsetBottom
                    }
                }
            }

            view.setPadding(dp(16), dp(12) + topInset, dp(16), dp(10) + bottomInset)
            windowInsets
        }
        root.requestApplyInsets()
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }

    private fun exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
        }
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val trace = writer.toString().take(4000)
                preferences.edit().putString(KEY_LAST_CRASH, trace).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showRecordedCrashIfAny(onDismiss: (() -> Unit)?): Boolean {
        val crash = preferences.getString(KEY_LAST_CRASH, "").orEmpty()
        if (crash.isEmpty()) return false

        preferences.edit().remove(KEY_LAST_CRASH).apply()
        stateView.text = "Previous crash: ${crash.lineSequence().firstOrNull().orEmpty()}"

        AlertDialog.Builder(this)
            .setTitle("SideSubs recovered from a crash")
            .setMessage(crash)
            .setPositiveButton("OK") { _, _ -> onDismiss?.invoke() }
            .setOnCancelListener { onDismiss?.invoke() }
            .show()
        return true
    }

    private fun friendlyAuthError(error: Throwable): String {
        if (error is IOException) return "Cannot reach Plex. Check your connection and try again."
        if (error.message?.contains("HTTP 429") == true) {
            return "Plex is limiting sign-in attempts. Please wait a few minutes and try again."
        }
        if (error.message?.contains("HTTP 401") == true) {
            return "Plex did not accept the sign-in. Please try again."
        }
        return friendlyError(error)
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) error.javaClass.simpleName else message.take(100).let {
            if (message.length > 100) "$it…" else it
        }
    }

    private fun formatPlaybackTime(position: Double): String {
        val seconds = position.toLong().coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "%d:%02d:%02d",
            seconds / 3600,
            (seconds / 60) % 60,
            seconds % 60,
        )
    }

    private fun ellipsize(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max - 1) + "…"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cinemaMode) enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && cinemaMode) enterImmersiveMode()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        sessionRefreshExecutor.shutdownNow()
        super.onDestroy()
    }

    private data class LanguageOption(
        val code: String,
        val label: String,
    )

    private data class SubtitleSizeOption(
        val id: String,
        val label: String,
        val previewSp: Float,
        val currentSp: Float,
        val nextSp: Float,
    )

    private companion object {
        val LANGUAGE_OPTIONS = listOf(
            LanguageOption("", "Automatic"),
            LanguageOption("ar", "Arabic"),
            LanguageOption("bg", "Bulgarian"),
            LanguageOption("ca", "Catalan"),
            LanguageOption("zh-CN", "Chinese (Simplified)"),
            LanguageOption("zh-TW", "Chinese (Traditional)"),
            LanguageOption("hr", "Croatian"),
            LanguageOption("cs", "Czech"),
            LanguageOption("da", "Danish"),
            LanguageOption("nl", "Dutch"),
            LanguageOption("en-US", "English (United States)"),
            LanguageOption("en-GB", "English (United Kingdom)"),
            LanguageOption("en-AU", "English (Australia)"),
            LanguageOption("en-CA", "English (Canada)"),
            LanguageOption("et", "Estonian"),
            LanguageOption("fi", "Finnish"),
            LanguageOption("fr-FR", "French (France)"),
            LanguageOption("fr-CA", "French (Canada)"),
            LanguageOption("gl", "Galician"),
            LanguageOption("de", "German"),
            LanguageOption("el", "Greek"),
            LanguageOption("he", "Hebrew"),
            LanguageOption("hi", "Hindi"),
            LanguageOption("hu", "Hungarian"),
            LanguageOption("id", "Indonesian"),
            LanguageOption("it", "Italian"),
            LanguageOption("ja", "Japanese"),
            LanguageOption("ko", "Korean"),
            LanguageOption("lv", "Latvian"),
            LanguageOption("lt", "Lithuanian"),
            LanguageOption("ms", "Malay"),
            LanguageOption("no", "Norwegian"),
            LanguageOption("fa", "Persian"),
            LanguageOption("pl", "Polish"),
            LanguageOption("pt-PT", "Portuguese (Portugal)"),
            LanguageOption("pt-BR", "Portuguese (Brazil)"),
            LanguageOption("ro", "Romanian"),
            LanguageOption("ru", "Russian"),
            LanguageOption("sr", "Serbian"),
            LanguageOption("sk", "Slovak"),
            LanguageOption("sl", "Slovenian"),
            LanguageOption("es-ES", "Spanish (Spain)"),
            LanguageOption("es-419", "Spanish (Latin America)"),
            LanguageOption("es-MX", "Spanish (Mexico)"),
            LanguageOption("sv", "Swedish"),
            LanguageOption("th", "Thai"),
            LanguageOption("tr", "Turkish"),
            LanguageOption("uk", "Ukrainian"),
            LanguageOption("vi", "Vietnamese"),
        )

        val SUBTITLE_SIZES = listOf(
            SubtitleSizeOption("very-small", "Very small", 18f, 21f, 14f),
            SubtitleSizeOption("small", "Small", 22f, 25f, 16f),
            SubtitleSizeOption("medium", "Medium", 26f, 30f, 19f),
            SubtitleSizeOption("large", "Large", 32f, 38f, 24f),
            SubtitleSizeOption("very-large", "Very large", 40f, 50f, 31f),
        )

        const val PREFS = "sidesubs_settings"
        const val KEY_PLAYER_ID = "player_id"
        const val KEY_LANGUAGE = "preferred_language"
        const val KEY_SUBTITLE_SIZE = "subtitle_size"
        const val KEY_DELAY_MS = "subtitle_delay_ms"
        const val KEY_LAST_CRASH = "last_crash"
        const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
        const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        const val KEY_LATEST_VERSION = "latest_known_version"
        const val KEY_LATEST_RELEASE_URL = "latest_release_url"
        const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/soyxan/sidesubs/releases/latest"
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val MIN_DELAY_MS = -5000
        const val MAX_DELAY_MS = 5000
        const val DELAY_STEP_MS = 250
        const val DELAY_CONTROLS_TIMEOUT_MS = 3000L
        const val NEXT_PREVIEW_SECONDS = 4.0
        const val POLL_INTERVAL_MS = 750L
        const val AUTH_POLL_INTERVAL_MS = 3_000L
        const val NETWORK_RETRY_INTERVAL_MS = 3_000L
    }
}
