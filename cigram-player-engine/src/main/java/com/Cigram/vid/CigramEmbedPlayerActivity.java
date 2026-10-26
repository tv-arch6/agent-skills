package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Shows a provider's OWN documented embed player (the page its documentation gives for a TMDB id). Used only when
 * no native source exists. The app's controls do not drive this player: it is the provider's page, and it says so.
 *
 * Contained: https only, top-level navigation stays on the provider's host, pop-up windows and app / intent links are
 * blocked, no file or content access. Nothing is extracted from the page.
 */
public class CigramEmbedPlayerActivity extends Activity {
    public static final String EXTRA_URL = "url";
    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private String host = "";

    /** True for a link that may be opened here: https with a host. */
    static boolean allowed(String url) {
        try { Uri u = Uri.parse(url); return "https".equals(u.getScheme()) && u.getHost() != null && u.getHost().length() > 0; }
        catch (Throwable t) { return false; }
    }

    /** Same host or one of its subdomains. */
    static boolean sameSite(String host, String other) {
        if (host == null || other == null || host.length() == 0) return false;
        String h = host.toLowerCase(java.util.Locale.US), o = other.toLowerCase(java.util.Locale.US);
        return o.equals(h) || o.endsWith("." + h);
    }

    public static void open(Activity from, String url) {
        Intent i = new Intent(from, CigramEmbedPlayerActivity.class);
        i.putExtra(EXTRA_URL, url);
        from.startActivity(i);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        if (url == null || !allowed(url)) { finish(); return; }
        host = Uri.parse(url).getHost();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); } catch (Throwable ignored) { }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);                    // the provider's player needs it
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                return !(allowed(u) && sameSite(host, r.getUrl().getHost()));      // true = blocked
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, android.os.Message m) { return false; }
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view; customCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
            }
            @Override public void onHideCustomView() { hideCustom(); }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(20);
        close.setPadding(30, 14, 30, 14);
        close.setBackgroundColor(0x66000000);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        root.addView(close, cl);
        setContentView(root);
        web.loadUrl(url);
    }

    private void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customCallback != null) { try { customCallback.onCustomViewHidden(); } catch (Throwable ignored) { } }
    }

    @Override public void onBackPressed() {
        if (customView != null) { hideCustom(); return; }
        finish();
    }

    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }

    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }

    @Override protected void onDestroy() {
        if (web != null) {
            try { ((ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) { }
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
