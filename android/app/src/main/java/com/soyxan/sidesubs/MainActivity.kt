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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val playbackClock = PlaybackClock()

    private lateinit var preferences: SharedPreferences
    private lateinit var plexAuth: PlexAuthManager
    private lateinit var diagnostics: DiagnosticLog
    private var mediaProvider: MediaProvider? = null

    @Volatile private var pollInFlight = false
    @Volatile private var authPollInFlight = false
    @Volatile private var pendingPlexLogin: PlexPendingLogin? = null
    @Volatile private var plexLoginAuthorized = false
    private var cinemaMode = false
    private var hideChromeTask: Runnable? = null
    private var setupDialog: AlertDialog? = null
    private var setupStatusView: TextView? = null
    private var setupSignInAgainButton: Button? = null
    private var setupRequired = true

    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var controls: LinearLayout
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

    private val pollTask = object : Runnable {
        override fun run() {
            pollOnce()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val authPollTask = object : Runnable {
        override fun run() {
            val pending = pendingPlexLogin ?: return
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
                            handler.postDelayed(this, AUTH_POLL_INTERVAL_MS)
                            return@execute
                        }
                        if (pendingPlexLogin !== pending) return@execute
                        plexLoginAuthorized = true
                        runOnUiThread {
                            if (setupDialog !== dialog) return@runOnUiThread
                            setupStatusView?.text = "Signed in. Finding your Plex server…"
                            setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Find Plex servers"
                            setupSignInAgainButton?.visibility = View.VISIBLE
                        }
                    }

                    val servers = plexAuth.listServers()
                    if (pendingPlexLogin !== pending) return@execute
                    pendingPlexLogin = null
                    plexLoginAuthorized = false
                    runOnUiThread {
                        if (setupDialog !== dialog) return@runOnUiThread
                        if (servers.isEmpty()) {
                            setupStatusView?.text = "No Plex Media Servers found in your account."
                            setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                        } else {
                            setupDialog?.dismiss()
                            showServerChooser(servers, required = setupRequired)
                        }
                    }
                } catch (error: Exception) {
                    if (pendingPlexLogin !== pending) return@execute
                    if (error is IOException) {
                        runOnUiThread {
                            if (setupDialog !== dialog) return@runOnUiThread
                            setupStatusView?.text =
                                "Cannot reach Plex right now. Retrying automatically…"
                        }
                        handler.postDelayed(this, NETWORK_RETRY_INTERVAL_MS)
                    } else {
                        pendingPlexLogin = null
                        plexLoginAuthorized = false
                        runOnUiThread {
                            if (setupDialog !== dialog) return@runOnUiThread
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
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        diagnostics = DiagnosticLog(this)
        plexAuth = PlexAuthManager(preferences, diagnostics)
        diagnostics.add("App started; savedServer=${plexAuth.hasSavedServer()} accountTokenPresent=${plexAuth.hasAccountToken()}")
        installCrashRecorder()
        buildUi()

        val afterCrash = {
            when {
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
        if (pendingPlexLogin != null) {
            handler.removeCallbacks(authPollTask)
            handler.post(authPollTask)
        }
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

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        sessionButton = controlButton("📺 Session")
        subtitleButton = controlButton("💬 Subtitles")
        delayButton = controlButton("◷")
        settingsButton = controlButton("⚙")
        cinemaButton = controlButton("⛶")

        sessionButton.setOnClickListener { showSessionChooser() }
        subtitleButton.setOnClickListener { showSubtitleChooser() }
        delayButton.setOnClickListener { showDelayChooser() }
        settingsButton.setOnClickListener { showSettings() }
        cinemaButton.setOnClickListener { setCinemaMode(!cinemaMode) }

        addControl(sessionButton, 1.5f)
        addControl(subtitleButton, 1.6f)
        addControl(delayButton, 0.55f)
        addControl(settingsButton, 0.55f)
        addControl(cinemaButton, 0.55f)

        root.addView(
            controls,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)),
        )

        setContentView(root)
        updateDelayButton()
    }

    private fun restoreSavedProvider() {
        stateView.text = "Connecting to saved media server…"
        executor.execute {
            try {
                val connection = plexAuth.restoreConnection()
                    ?: error("Saved Plex server is no longer available")
                runOnUiThread { connectProvider(connection) }
            } catch (error: Exception) {
                diagnostics.add("Restore server failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    stateView.text = "Choose a media server"
                    showProviderSetup(required = true, message = friendlyServerError(error))
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
        content.addView(spinner)

        val help = TextView(this).apply {
            text = message ?: "Choose the media server platform. SideSubs will use that provider's own sign-in flow."
            setTextColor(0xFF999999.toInt())
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(help)
        val viewLogButton = Button(this).apply {
            text = "View log"
            isAllCaps = false
        }
        content.addView(viewLogButton)
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
            .setTitle("Connect SideSubs")
            .setView(content)
            .setPositiveButton(
                if (plexAuth.hasAccountToken()) "Find Plex servers" else "Sign in with Plex",
                null,
            )

        if (!required) builder.setNegativeButton("Cancel", null)

        val dialog = builder.create().apply {
            setCancelable(!required)
            setCanceledOnTouchOutside(!required)
        }
        setupDialog = dialog

        dialog.setOnShowListener {
            viewLogButton.setOnClickListener { showDiagnosticLog() }
            val signIn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            signIn.setOnClickListener {
                val providerType = providers[spinner.selectedItemPosition]
                when (providerType) {
                    MediaProviderType.PLEX -> {
                        if (plexAuth.hasAccountToken()) {
                            loadServerChooser(required = required)
                        } else {
                            signIn.isEnabled = false
                            handler.removeCallbacks(authPollTask)
                            pendingPlexLogin = null
                            plexLoginAuthorized = false
                            help.text = "Opening Plex sign-in…"
                            beginPlexSignIn(help, signIn)
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            if (setupDialog === dialog) {
                handler.removeCallbacks(authPollTask)
                pendingPlexLogin = null
                plexLoginAuthorized = false
                setupDialog = null
                setupStatusView = null
                setupSignInAgainButton = null
            }
        }
        dialog.show()
    }

    private fun beginPlexSignIn(status: TextView, button: Button) {
        executor.execute {
            try {
                val pending = plexAuth.beginLogin()
                pendingPlexLogin = pending
                runOnUiThread {
                    status.text = "Complete sign-in in your browser, then return to SideSubs."
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pending.authUrl))
                    startActivity(intent)
                    handler.removeCallbacks(authPollTask)
                    handler.post(authPollTask)
                }
            } catch (error: Exception) {
                diagnostics.add("Start Plex sign-in failed: ${error.javaClass.simpleName}")
                runOnUiThread {
                    pendingPlexLogin = null
                    status.text = "Plex sign-in: ${friendlyAuthError(error)}"
                    button.isEnabled = true
                }
            }
        }
    }

    private fun showServerChooser(servers: List<PlexServerResource>, required: Boolean) {
        if (servers.size == 1) {
            connectToServer(servers.first(), servers, required)
            return
        }

        val labels = servers.map { server ->
            val local = server.connections.any { it.local && !it.relay }
            "${server.name}${if (local) " · Local" else ""}"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose Plex server")
            .setItems(labels) { d, which ->
                d.dismiss()
                connectToServer(servers[which], servers, required)
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
    ) {
        stateView.text = "Checking connections to ${server.name}…"
        val progress = AlertDialog.Builder(this)
            .setTitle("Connecting to ${server.name}")
            .setMessage("Checking available server addresses…")
            .setNegativeButton("Cancel") { _, _ ->
                stateView.text = if (mediaProvider == null) "Choose a media server"
                    else "Connected to ${mediaProvider?.serverName}"
                if (mediaProvider == null) showProviderSetup(required = true)
            }
            .create()
        progress.setCancelable(false)
        progress.show()

        executor.execute {
            try {
                val connection = plexAuth.selectServer(server, persist = false)
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
        }

        mediaProvider = provider
        loggedSessionCount = -1
        loggedSessionState = ""
        loggedPollError = ""
        diagnostics.add("Connected to Plex server using validated address")
        playbackClock.clear()
        clearLoadedSubtitle()
        stateView.text = "Connecting to ${connection.serverName}…"
        startPolling()
    }

    private fun startPolling() {
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollTask)
        pollInFlight = false
    }

    private fun pollOnce() {
        val provider = mediaProvider ?: return
        if (pollInFlight) return
        pollInFlight = true

        executor.execute {
            try {
                val freshSessions = provider.sessions()
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
                        sessions = freshSessions
                        selectedSession = null
                        titleView.text = "SideSubs"
                        stateView.text = "No active ${provider.providerType.displayName} session · ${provider.serverName}"
                        currentSubtitleView.text = ""
                        nextSubtitleView.text = ""
                        sessionButton.text = "📺 Session"
                    }
                    return@execute
                }

                val position = playbackClock.smooth(
                    session.clockKey(),
                    session.position,
                    session.state,
                )

                var freshTracks = tracks
                var track = selectedTrack
                var freshTimeline = timeline
                val needsTimeline = session.mediaId != loadedMediaId

                if (needsTimeline) {
                    freshTracks = provider.subtitleTracks(session.mediaId)
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
                        track = chooseTrack(session.mediaId, freshTracks)
                        freshTimeline = track?.let { provider.subtitleTimeline(session.mediaId, it) }
                    }
                }

                loggedPollError = ""
                runOnUiThread {
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
                val reason = Regex("Plex HTTP [0-9]{3}").find(error.message.orEmpty())?.value
                    ?: error.javaClass.simpleName
                if (reason != loggedPollError) {
                    loggedPollError = reason
                    diagnostics.add("Playback update failed: $reason")
                }
                runOnUiThread {
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
            items.firstOrNull { it.playerId == selectedPlayerId }?.let { return it }
        }
        return items.firstOrNull { it.state.equals("playing", ignoreCase = true) } ?: items.first()
    }

    private fun chooseTrack(mediaId: String, available: List<SubtitleTrack>): SubtitleTrack? {
        val manual = preferredTrackId(mediaId)
        if (manual.isNotEmpty()) {
            available.firstOrNull { it.compatible && it.id == manual }?.let { return it }
        }
        val language = preferredLanguage()
        return available.firstOrNull { it.compatible && it.language.equals(language, ignoreCase = true) }
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
        stateView.text = "${session.displayClient()} · ${session.state}"
        sessionButton.text = "📺 ${ellipsize(session.displayClient(), 13)}"
        subtitleButton.text = if (track == null) {
            "💬 No ${preferredLanguage().uppercase(Locale.US)}"
        } else {
            "💬 ${ellipsize(track.label(), 17)}"
        }
        subtitleButton.isEnabled = tracks.isNotEmpty()

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
        return current to next
    }

    private fun showSessionChooser() {
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No playback sessions available", Toast.LENGTH_SHORT).show()
            return
        }

        val saved = preferences.getString(KEY_PLAYER_ID, "").orEmpty()
        val labels = sessions.map { "${it.displayClient()}\n${it.title}" }.toTypedArray()
        val checked = sessions.indexOfFirst { it.playerId == saved }

        AlertDialog.Builder(this)
            .setTitle("Playback session")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val item = sessions[which]
                diagnostics.add("Playback session selected: media=${item.mediaId} state=${item.state}")
                preferences.edit().putString(KEY_PLAYER_ID, item.playerId).apply()
                playbackClock.clear()
                clearLoadedSubtitle()
                dialog.dismiss()
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
                pollOnce()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDelayChooser() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }

        val minus = controlButton("−").apply { textSize = 24f }
        val value = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            minWidth = dp(120)
        }
        val plus = controlButton("+").apply { textSize = 24f }

        var delay = preferences.getInt(KEY_DELAY_MS, 1000).coerceIn(0, 5000)
        fun refresh() {
            value.text = String.format(Locale.US, "%.2f s", delay / 1000.0)
        }
        refresh()

        minus.setOnClickListener {
            delay = max(0, delay - 250)
            refresh()
        }
        plus.setOnClickListener {
            delay = min(5000, delay + 250)
            refresh()
        }

        content.addView(minus, LinearLayout.LayoutParams(dp(64), dp(52)))
        content.addView(value, LinearLayout.LayoutParams(dp(130), dp(52)))
        content.addView(plus, LinearLayout.LayoutParams(dp(64), dp(52)))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Subtitle delay")
            .setView(content)
            .setNeutralButton("Reset", null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                delay = 0
                refresh()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                preferences.edit().putInt(KEY_DELAY_MS, delay).apply()
                updateDelayButton()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateDelayButton() {
        val delay = if (::preferences.isInitialized) preferences.getInt(KEY_DELAY_MS, 1000) else 1000
        delayButton.text = String.format(Locale.US, "◷ %.1f", delay / 1000.0)
    }

    private fun showSettings() {
        val provider = mediaProvider
        if (provider == null) {
            AlertDialog.Builder(this)
                .setTitle("SideSubs settings")
                .setMessage("No media server connected.")
                .setPositiveButton("Connect") { _, _ -> showProviderSetup(required = false) }
                .setNeutralButton("View log") { _, _ -> showDiagnosticLog() }
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
        content.addView(label("Connected server"))
        content.addView(valueText(provider.serverName))
        content.addView(label("Preferred subtitle language"))

        val languageInput = input(preferredLanguage()).apply { hint = "es" }
        content.addView(languageInput)

        val viewLogButton = Button(this).apply {
            text = "View log"
            isAllCaps = false
        }
        content.addView(viewLogButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle("SideSubs settings")
            .setView(content)
            .setPositiveButton("Save", null)
            .setNeutralButton("Change server", null)
            .setNegativeButton("Sign out", null)
            .create()

        dialog.setOnShowListener {
            viewLogButton.setOnClickListener {
                dialog.dismiss()
                showDiagnosticLog()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val language = languageInput.text.toString()
                    .trim()
                    .lowercase(Locale.US)
                    .ifBlank { "es" }
                preferences.edit().putString(KEY_LANGUAGE, language).apply()
                diagnostics.add("Preferred subtitle language set: $language")
                clearLoadedSubtitle()
                dialog.dismiss()
                pollOnce()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                loadServerChooser()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                confirmSignOut()
            }
        }
        dialog.show()
    }

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
        setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        val dialog = setupDialog
        executor.execute {
            try {
                val servers = plexAuth.listServers()
                runOnUiThread {
                    if (setupDialog !== dialog) return@runOnUiThread
                    if (servers.isEmpty()) {
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
                    setupStatusView?.text = friendlyAuthError(error)
                    setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                }
            }
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of Plex?")
            .setMessage("SideSubs will remove the saved Plex authorization and server selection from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ ->
                stopPolling()
                plexAuth.signOut()
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

    private fun preferredLanguage(): String =
        preferences.getString(KEY_LANGUAGE, "es").orEmpty().ifBlank { "es" }

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

    private fun controlButton(label: String) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        isAllCaps = false
        isSingleLine = true
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(5), 0, dp(5), 0)
    }

    private fun addControl(button: Button, weight: Float) {
        controls.addView(button, LinearLayout.LayoutParams(0, dp(46), weight))
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
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            hideChromeTask?.let(handler::removeCallbacks)
        }
        root.requestApplyInsets()
    }

    private fun showCinemaChromeTemporarily() {
        if (!cinemaMode) return
        topBar.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        hideChromeTask?.let(handler::removeCallbacks)
        hideChromeTask = Runnable {
            if (cinemaMode) {
                topBar.visibility = View.GONE
                controls.visibility = View.GONE
            }
        }.also { handler.postDelayed(it, 2500) }
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
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "sidesubs_settings"
        const val KEY_PLAYER_ID = "player_id"
        const val KEY_LANGUAGE = "preferred_language"
        const val KEY_DELAY_MS = "subtitle_delay_ms"
        const val KEY_LAST_CRASH = "last_crash"
        const val POLL_INTERVAL_MS = 750L
        const val AUTH_POLL_INTERVAL_MS = 3_000L
        const val NETWORK_RETRY_INTERVAL_MS = 3_000L
    }
}
