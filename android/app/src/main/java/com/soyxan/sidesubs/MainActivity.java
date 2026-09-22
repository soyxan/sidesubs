package com.soyxan.sidesubs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.soyxan.sidesubs.PlexModels.Cue;
import com.soyxan.sidesubs.PlexModels.PlaybackSession;
import com.soyxan.sidesubs.PlexModels.SubtitleTimeline;
import com.soyxan.sidesubs.PlexModels.SubtitleTrack;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "sidesubs_settings";
    private static final String KEY_PLEX_URL = "plex_url";
    private static final String KEY_PLEX_TOKEN = "plex_token";
    private static final String KEY_PLAYER_ID = "player_id";
    private static final String KEY_LANGUAGE = "preferred_language";
    private static final String KEY_DELAY_MS = "subtitle_delay_ms";
    private static final String KEY_LAST_CRASH = "last_crash";
    private static final long POLL_INTERVAL_MS = 750L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final PlaybackClock playbackClock = new PlaybackClock();

    private SharedPreferences preferences;
    private PlexClient plex;
    private boolean pollInFlight;
    private boolean cinemaMode;
    private boolean chromeVisible = true;
    private Runnable hideChromeTask;

    private LinearLayout root;
    private LinearLayout topBar;
    private LinearLayout controls;
    private TextView titleView;
    private TextView stateView;
    private TextView currentSubtitleView;
    private TextView nextSubtitleView;
    private Button sessionButton;
    private Button subtitleButton;
    private Button delayButton;
    private Button settingsButton;
    private Button cinemaButton;

    private List<PlaybackSession> sessions = new ArrayList<>();
    private List<SubtitleTrack> tracks = new ArrayList<>();
    private PlaybackSession selectedSession;
    private SubtitleTrack selectedTrack;
    private SubtitleTimeline timeline;
    private String loadedRatingKey = "";
    private String loadedTrackId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        installCrashRecorder();
        buildUi();
        showRecordedCrashIfAny();

        String url = preferences.getString(KEY_PLEX_URL, "");
        String token = preferences.getString(KEY_PLEX_TOKEN, "");
        if (isBlank(url) || isBlank(token)) {
            showSettings(true);
        } else {
            configureClient(url, token);
            startPolling();
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(16), dp(12), dp(16), dp(10));
        root.setOnClickListener(v -> {
            if (cinemaMode) showCinemaChromeTemporarily();
        });

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setGravity(Gravity.CENTER_HORIZONTAL);

        titleView = new TextView(this);
        titleView.setText("SideSubs");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);

        stateView = new TextView(this);
        stateView.setText("Connecting to Plex…");
        stateView.setTextColor(0xFF999999);
        stateView.setTextSize(12);
        stateView.setGravity(Gravity.CENTER);
        stateView.setPadding(0, dp(4), 0, 0);

        topBar.addView(titleView);
        topBar.addView(stateView);
        root.addView(topBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout subtitleArea = new LinearLayout(this);
        subtitleArea.setOrientation(LinearLayout.VERTICAL);
        subtitleArea.setGravity(Gravity.CENTER);
        subtitleArea.setPadding(dp(12), dp(20), dp(12), dp(20));

        currentSubtitleView = new TextView(this);
        currentSubtitleView.setText("");
        currentSubtitleView.setTextColor(Color.WHITE);
        currentSubtitleView.setTextSize(30);
        currentSubtitleView.setGravity(Gravity.CENTER);
        currentSubtitleView.setLineSpacing(0, 1.08f);

        nextSubtitleView = new TextView(this);
        nextSubtitleView.setText("");
        nextSubtitleView.setTextColor(0xFF777777);
        nextSubtitleView.setTextSize(19);
        nextSubtitleView.setGravity(Gravity.CENTER);
        nextSubtitleView.setPadding(0, dp(26), 0, 0);
        nextSubtitleView.setLineSpacing(0, 1.06f);

        subtitleArea.addView(currentSubtitleView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        subtitleArea.addView(nextSubtitleView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        root.addView(subtitleArea, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        sessionButton = controlButton("📺 Session");
        subtitleButton = controlButton("💬 Subtitles");
        delayButton = controlButton("◷");
        settingsButton = controlButton("⚙");
        cinemaButton = controlButton("⛶");

        sessionButton.setOnClickListener(v -> showSessionChooser());
        subtitleButton.setOnClickListener(v -> showSubtitleChooser());
        delayButton.setOnClickListener(v -> showDelayChooser());
        settingsButton.setOnClickListener(v -> showSettings(false));
        cinemaButton.setOnClickListener(v -> setCinemaMode(!cinemaMode));

        addControl(sessionButton, 1.5f);
        addControl(subtitleButton, 1.6f);
        addControl(delayButton, 0.55f);
        addControl(settingsButton, 0.55f);
        addControl(cinemaButton, 0.55f);

        root.addView(controls, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ));

        setContentView(root);
        updateDelayButton();
    }

    private Button controlButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(5), 0, dp(5), 0);
        return button;
    }

    private void addControl(Button button, float weight) {
        controls.addView(button, new LinearLayout.LayoutParams(0, dp(46), weight));
    }

    private void configureClient(String url, String token) {
        try {
            plex = new PlexClient(url, token);
            playbackClock.clear();
            clearLoadedSubtitle();
            stateView.setText("Connecting to " + plex.serverUrl());
        } catch (Exception error) {
            plex = null;
            stateView.setText("Invalid Plex configuration");
        }
    }

    private void startPolling() {
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            pollOnce();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void pollOnce() {
        if (plex == null || pollInFlight) return;
        pollInFlight = true;

        executor.execute(() -> {
            try {
                List<PlaybackSession> freshSessions = plex.sessions();
                PlaybackSession session = chooseSession(freshSessions);

                if (session == null) {
                    runOnUiThread(() -> {
                        sessions = freshSessions;
                        selectedSession = null;
                        titleView.setText("SideSubs");
                        stateView.setText("No active Plex session");
                        currentSubtitleView.setText("");
                        nextSubtitleView.setText("");
                        sessionButton.setText("📺 Session");
                    });
                    return;
                }

                double position = playbackClock.smooth(
                    session.clockKey(),
                    session.position,
                    session.state
                );

                boolean needsTimeline = !session.ratingKey.equals(loadedRatingKey);
                List<SubtitleTrack> freshTracks = tracks;
                SubtitleTrack track = selectedTrack;
                SubtitleTimeline freshTimeline = timeline;

                if (needsTimeline) {
                    freshTracks = plex.subtitleTracks(session.ratingKey);
                    track = chooseTrack(session.ratingKey, freshTracks);
                    freshTimeline = track == null ? null : plex.subtitleTimeline(session.ratingKey, track);
                } else {
                    String wantedTrackId = preferredTrackId(session.ratingKey);
                    if (track == null || (!wantedTrackId.isEmpty() && !wantedTrackId.equals(track.id))) {
                        freshTracks = plex.subtitleTracks(session.ratingKey);
                        track = chooseTrack(session.ratingKey, freshTracks);
                        freshTimeline = track == null ? null : plex.subtitleTimeline(session.ratingKey, track);
                    }
                }

                final List<PlaybackSession> uiSessions = freshSessions;
                final List<SubtitleTrack> uiTracks = freshTracks;
                final PlaybackSession uiSession = session;
                final SubtitleTrack uiTrack = track;
                final SubtitleTimeline uiTimeline = freshTimeline;
                final double uiPosition = position;

                runOnUiThread(() -> applyPlaybackState(
                    uiSessions,
                    uiSession,
                    uiTracks,
                    uiTrack,
                    uiTimeline,
                    uiPosition
                ));
            } catch (Exception error) {
                runOnUiThread(() -> stateView.setText("Plex: " + friendlyError(error)));
            } finally {
                pollInFlight = false;
            }
        });
    }

    private PlaybackSession chooseSession(List<PlaybackSession> items) {
        if (items == null || items.isEmpty()) return null;

        String selectedPlayerId = preferences.getString(KEY_PLAYER_ID, "");
        if (!isBlank(selectedPlayerId)) {
            for (PlaybackSession session : items) {
                if (selectedPlayerId.equals(session.playerId)) return session;
            }
        }

        for (PlaybackSession session : items) {
            if ("playing".equalsIgnoreCase(session.state)) return session;
        }
        return items.get(0);
    }

    private SubtitleTrack chooseTrack(String ratingKey, List<SubtitleTrack> available) {
        String manual = preferredTrackId(ratingKey);
        if (!manual.isEmpty()) {
            for (SubtitleTrack track : available) {
                if (track.compatible && manual.equals(track.id)) return track;
            }
        }

        String language = preferences.getString(KEY_LANGUAGE, "es");
        for (SubtitleTrack track : available) {
            if (track.compatible && language.equalsIgnoreCase(track.language)) return track;
        }
        return null;
    }

    private String preferredTrackId(String ratingKey) {
        return preferences.getString("track_" + ratingKey, "");
    }

    private void applyPlaybackState(
        List<PlaybackSession> freshSessions,
        PlaybackSession session,
        List<SubtitleTrack> freshTracks,
        SubtitleTrack track,
        SubtitleTimeline freshTimeline,
        double position
    ) {
        sessions = freshSessions;
        selectedSession = session;
        tracks = freshTracks;
        selectedTrack = track;
        timeline = freshTimeline;
        loadedRatingKey = session.ratingKey;
        loadedTrackId = track == null ? "" : track.id;

        titleView.setText(session.title);
        stateView.setText(session.displayClient() + " · " + session.state);
        sessionButton.setText("📺 " + ellipsize(session.displayClient(), 13));
        subtitleButton.setText(track == null ? "💬 No " + preferredLanguage().toUpperCase(Locale.US)
            : "💬 " + ellipsize(track.label(), 17));
        subtitleButton.setEnabled(!tracks.isEmpty());

        int delayMs = preferences.getInt(KEY_DELAY_MS, 1000);
        double effectivePosition = Math.max(0, position - delayMs / 1000.0);
        Cue[] pair = cuePair(freshTimeline == null ? null : freshTimeline.cues, effectivePosition);
        currentSubtitleView.setText(pair[0] == null ? "" : pair[0].text);
        nextSubtitleView.setText(pair[1] == null ? "" : pair[1].text);
    }

    private Cue[] cuePair(List<Cue> cues, double position) {
        Cue current = null;
        Cue next = null;
        if (cues == null) return new Cue[]{null, null};

        for (Cue cue : cues) {
            if (cue.start <= position && position <= cue.end + 1.5) {
                current = cue;
                continue;
            }
            if (cue.start > position) {
                next = cue;
                break;
            }
        }
        if (current != null && next == current) next = null;
        return new Cue[]{current, next};
    }

    private void showSessionChooser() {
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No Plex sessions available", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[sessions.size()];
        int checked = -1;
        String saved = preferences.getString(KEY_PLAYER_ID, "");
        for (int i = 0; i < sessions.size(); i++) {
            PlaybackSession item = sessions.get(i);
            labels[i] = item.displayClient() + "\n" + item.title;
            if (item.playerId.equals(saved)) checked = i;
        }

        new AlertDialog.Builder(this)
            .setTitle("Plex session")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                PlaybackSession item = sessions.get(which);
                preferences.edit().putString(KEY_PLAYER_ID, item.playerId).apply();
                playbackClock.clear();
                clearLoadedSubtitle();
                dialog.dismiss();
                pollOnce();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSubtitleChooser() {
        if (selectedSession == null || tracks.isEmpty()) {
            Toast.makeText(this, "No subtitle tracks available", Toast.LENGTH_SHORT).show();
            return;
        }

        List<SubtitleTrack> compatible = new ArrayList<>();
        for (SubtitleTrack track : tracks) if (track.compatible) compatible.add(track);

        String[] labels = new String[compatible.size() + 1];
        labels[0] = "Auto (" + preferredLanguage().toUpperCase(Locale.US) + ")";
        String manual = preferredTrackId(selectedSession.ratingKey);
        int checked = manual.isEmpty() ? 0 : -1;
        for (int i = 0; i < compatible.size(); i++) {
            labels[i + 1] = compatible.get(i).label();
            if (compatible.get(i).id.equals(manual)) checked = i + 1;
        }

        new AlertDialog.Builder(this)
            .setTitle("Subtitle track")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                SharedPreferences.Editor edit = preferences.edit();
                if (which == 0) {
                    edit.remove("track_" + selectedSession.ratingKey);
                } else {
                    edit.putString("track_" + selectedSession.ratingKey, compatible.get(which - 1).id);
                }
                edit.apply();
                loadedTrackId = "";
                selectedTrack = null;
                timeline = null;
                dialog.dismiss();
                pollOnce();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showDelayChooser() {
        final int step = 250;
        final int max = 5000;
        int count = max / step + 1;
        String[] labels = new String[count];
        int current = preferences.getInt(KEY_DELAY_MS, 1000);
        int checked = Math.max(0, Math.min(count - 1, Math.round(current / (float) step)));
        for (int i = 0; i < count; i++) {
            labels[i] = String.format(Locale.US, "%.2f s", (i * step) / 1000.0);
        }

        new AlertDialog.Builder(this)
            .setTitle("Subtitle delay")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                preferences.edit().putInt(KEY_DELAY_MS, which * step).apply();
                updateDelayButton();
                dialog.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateDelayButton() {
        int delay = preferences == null ? 1000 : preferences.getInt(KEY_DELAY_MS, 1000);
        delayButton.setText(String.format(Locale.US, "◷ %.1f", delay / 1000.0));
    }

    private void showSettings(boolean required) {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(8), pad, dp(4));

        TextView urlLabel = label("Plex server URL");
        EditText urlInput = input(preferences.getString(KEY_PLEX_URL, ""));
        urlInput.setHint("http://192.168.1.50:32400");

        TextView tokenLabel = label("Plex token");
        EditText tokenInput = input(preferences.getString(KEY_PLEX_TOKEN, ""));
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        TextView languageLabel = label("Preferred subtitle language");
        EditText languageInput = input(preferredLanguage());
        languageInput.setHint("es");

        content.addView(urlLabel);
        content.addView(urlInput);
        content.addView(tokenLabel);
        content.addView(tokenInput);
        content.addView(languageLabel);
        content.addView(languageInput);

        TextView hint = new TextView(this);
        hint.setText("SideSubs connects directly to Plex. Docker is not required.");
        hint.setTextColor(0xFF999999);
        hint.setTextSize(12);
        hint.setPadding(0, dp(10), 0, 0);
        content.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle("SideSubs settings")
            .setView(scroll)
            .setPositiveButton("Save", null);

        if (!required) builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.setCancelable(!required);
        dialog.setCanceledOnTouchOutside(!required);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String url = urlInput.getText().toString().trim();
                String token = tokenInput.getText().toString().trim();
                String language = languageInput.getText().toString().trim().toLowerCase(Locale.US);
                if (isBlank(url)) {
                    urlInput.setError("Plex URL is required");
                    return;
                }
                if (isBlank(token)) {
                    tokenInput.setError("Plex token is required");
                    return;
                }
                if (isBlank(language)) language = "es";

                try {
                    preferences.edit()
                        .putString(KEY_PLEX_URL, url)
                        .putString(KEY_PLEX_TOKEN, token)
                        .putString(KEY_LANGUAGE, language)
                        .apply();

                    configureClient(url, token);
                    dialog.dismiss();
                    handler.post(this::startPolling);
                } catch (Exception error) {
                    stateView.setText("Configuration error: " + friendlyError(error));
                }
            }));
        dialog.show();
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setPadding(0, dp(12), 0, dp(4));
        return label;
    }

    private EditText input(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value == null ? "" : value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF666666);
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF888888));
        return input;
    }

    private String preferredLanguage() {
        return preferences.getString(KEY_LANGUAGE, "es");
    }

    private void clearLoadedSubtitle() {
        loadedRatingKey = "";
        loadedTrackId = "";
        tracks = new ArrayList<>();
        selectedTrack = null;
        timeline = null;
        if (plex != null) plex.clearSubtitleCache();
    }

    private void setCinemaMode(boolean enabled) {
        cinemaMode = enabled;
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            enterImmersiveMode();
            showCinemaChromeTemporarily();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            exitImmersiveMode();
            chromeVisible = true;
            topBar.setVisibility(View.VISIBLE);
            controls.setVisibility(View.VISIBLE);
            if (hideChromeTask != null) handler.removeCallbacks(hideChromeTask);
        }
    }

    private void showCinemaChromeTemporarily() {
        if (!cinemaMode) return;
        chromeVisible = true;
        topBar.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        if (hideChromeTask != null) handler.removeCallbacks(hideChromeTask);
        hideChromeTask = () -> {
            if (!cinemaMode) return;
            chromeVisible = false;
            topBar.setVisibility(View.GONE);
            controls.setVisibility(View.GONE);
        };
        handler.postDelayed(hideChromeTask, 2500);
    }

    private void enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter writer = new StringWriter();
                error.printStackTrace(new PrintWriter(writer));
                String trace = writer.toString();
                if (trace.length() > 4000) trace = trace.substring(0, 4000);
                preferences.edit().putString(KEY_LAST_CRASH, trace).commit();
            } catch (Exception ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private void showRecordedCrashIfAny() {
        String crash = preferences.getString(KEY_LAST_CRASH, "");
        if (crash == null || crash.isEmpty()) return;
        preferences.edit().remove(KEY_LAST_CRASH).apply();

        String summary = crash;
        int newline = summary.indexOf('\n');
        if (newline > 0) summary = summary.substring(0, newline);
        stateView.setText("Previous crash: " + summary);

        new AlertDialog.Builder(this)
            .setTitle("SideSubs recovered from a crash")
            .setMessage(crash)
            .setPositiveButton("OK", null)
            .show();
    }

    private String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        if (message.length() > 100) return message.substring(0, 100) + "…";
        return message;
    }

    private String ellipsize(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (cinemaMode) enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && cinemaMode) enterImmersiveMode();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
