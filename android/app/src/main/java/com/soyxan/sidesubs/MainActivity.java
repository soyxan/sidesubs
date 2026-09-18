package com.soyxan.sidesubs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREFS = "sidesubs_settings";
    private static final String KEY_SERVER_URL = "server_url";

    private WebView webView;
    private SharedPreferences preferences;
    private boolean cinemaMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
            ) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showConnectionError(
                        "Cannot connect to SideSubs",
                        "Check that the server address is correct and that SideSubs is running."
                    );
                }
            }

            @Override
            public void onReceivedSslError(
                WebView view,
                SslErrorHandler handler,
                SslError error
            ) {
                handler.cancel();
                showConnectionError(
                    "Secure connection failed",
                    "This server may be using HTTP instead of HTTPS, or its certificate may not be valid."
                );
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new SideSubsBridge(), "SideSubsAndroid");

        webView.setOnLongClickListener(view -> {
            showServerSettings(false);
            return true;
        });

        setContentView(webView);
        exitImmersiveMode();

        String savedUrl = preferences.getString(KEY_SERVER_URL, "");
        if (savedUrl == null || savedUrl.trim().isEmpty()) {
            showServerSettings(true);
        } else {
            loadServer(savedUrl);
        }
    }

    private void showConnectionError(String title, String message) {
        runOnUiThread(() -> {
            String currentUrl = preferences.getString(KEY_SERVER_URL, "");

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(48, 64, 48, 48);
            layout.setGravity(android.view.Gravity.CENTER);
            layout.setBackgroundColor(Color.BLACK);

            TextView iconView = new TextView(this);
            iconView.setText("⚠");
            iconView.setTextColor(0xFFFFC107);
            iconView.setTextSize(56);
            iconView.setGravity(android.view.Gravity.CENTER);
            iconView.setPadding(0, 0, 0, 16);

            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(22);
            titleView.setGravity(android.view.Gravity.CENTER);
            titleView.setPadding(0, 0, 0, 20);

            TextView urlView = new TextView(this);
            urlView.setText(currentUrl == null ? "" : currentUrl);
            urlView.setTextColor(0xFFB0B0B0);
            urlView.setTextSize(14);
            urlView.setGravity(android.view.Gravity.CENTER);
            urlView.setPadding(0, 0, 0, 18);

            TextView messageView = new TextView(this);
            messageView.setText(message);
            messageView.setTextColor(0xFFD0D0D0);
            messageView.setTextSize(15);
            messageView.setGravity(android.view.Gravity.CENTER);
            messageView.setPadding(0, 0, 0, 30);

            Button retryButton = new Button(this);
            retryButton.setText("Retry");
            retryButton.setOnClickListener(view -> {
                setContentView(webView);
                if (currentUrl != null && !currentUrl.isEmpty()) {
                    loadServer(currentUrl);
                }
            });

            Button changeButton = new Button(this);
            changeButton.setText("Change server");
            changeButton.setOnClickListener(view -> {
                setContentView(webView);
                showServerSettings(false);
            });

            layout.addView(iconView);
            layout.addView(titleView);
            layout.addView(urlView);
            layout.addView(messageView);
            layout.addView(retryButton);
            layout.addView(changeButton);

            setContentView(layout);
            setCinemaMode(false);
        });
    }

    private void showServerSettings(boolean required) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("http://192.168.1.50:8085");
        input.setText(preferences.getString(KEY_SERVER_URL, ""));
        input.setSelectAllOnFocus(false);
        input.setPadding(48, 24, 48, 24);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle("SideSubs server")
            .setMessage("Enter the URL of your SideSubs web interface.")
            .setView(input)
            .setPositiveButton("Save", null);

        if (!required) {
            builder.setNegativeButton("Cancel", null);
        }

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!required);
        dialog.setCancelable(!required);

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String normalized = normalizeUrl(input.getText().toString());
                if (normalized == null) {
                    input.setError("Enter a valid HTTP or HTTPS URL");
                    return;
                }

                preferences.edit().putString(KEY_SERVER_URL, normalized).apply();
                dialog.dismiss();
                loadServer(normalized);
            });

            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager keyboard =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) {
                    keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 150);
        });

        dialog.setOnDismissListener(ignored -> applyCinemaUi());
        dialog.show();
    }

    private class SideSubsBridge {
        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }

        @JavascriptInterface
        public void enterCinemaMode() {
            runOnUiThread(() -> setCinemaMode(true));
        }

        @JavascriptInterface
        public void exitCinemaMode() {
            runOnUiThread(() -> setCinemaMode(false));
        }
    }

    private void setCinemaMode(boolean enabled) {
        cinemaMode = enabled;

        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            enterImmersiveMode();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            exitImmersiveMode();
        }
    }

    private void applyCinemaUi() {
        if (cinemaMode) {
            enterImmersiveMode();
        } else {
            exitImmersiveMode();
        }
    }

    private String normalizeUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) {
            return null;
        }

        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }

        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null ||
            (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) ||
            uri.getHost() == null) {
            return null;
        }

        return url.replaceAll("/+$", "");
    }

    private void loadServer(String url) {
        webView.loadUrl(url);
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyCinemaUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyCinemaUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyCinemaUi();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
