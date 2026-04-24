package com.vlcplayer.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MangaBrowserActivity extends AppCompatActivity {

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;
    private LinearLayout addressBar;
    private boolean isFullscreen = false;

    // Ad block domains
    private static final Set<String> AD_DOMAINS = new HashSet<>(Arrays.asList(
        "stripchat.com", "trafficjunky.com", "exoclick.com",
        "juicyads.com", "adnium.com", "plugrush.com",
        "tsyndicate.com", "trafficstars.com", "adspyglass.com",
        "adtng.com", "etahub.com", "silvercdn.com",
        "ads.com", "doubleclick.net", "googlesyndication.com",
        "adservice.google.com", "amazon-adsystem.com",
        "scorecardresearch.com", "quantserve.com"
    ));

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fullscreen window
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        setContentView(R.layout.activity_manga_browser);

        webView     = findViewById(R.id.webView);
        etUrl       = findViewById(R.id.et_url);
        progressBar = findViewById(R.id.progress);
        addressBar  = findViewById(R.id.address_bar);

        ImageButton btnBack    = findViewById(R.id.btn_back);
        ImageButton btnGo      = findViewById(R.id.btn_go);
        ImageButton btnRefresh = findViewById(R.id.btn_refresh);
        ImageButton btnNavBack = findViewById(R.id.btn_nav_back);
        ImageButton btnNavFwd  = findViewById(R.id.btn_nav_fwd);
        ImageButton btnFullscreen = findViewById(R.id.btn_fullscreen);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String ad : AD_DOMAINS) {
                        if (host.contains(ad)) {
                            // Block ad request
                            return new WebResourceResponse("text/plain", "utf-8",
                                new ByteArrayInputStream("".getBytes()));
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                etUrl.setText(url);
                // Inject CSS to hide common ad elements
                String css = "javascript:(function(){" +
                    "var s=document.createElement('style');" +
                    "s.innerHTML='" +
                    ".exo-container,.adnium,.ads-container," +
                    "[class*=\'ad-banner\'],[id*=\'ad-banner\']," +
                    "[class*=\'advertisement\'],[id*=\'advertisement\']," +
                    "iframe[src*=\'ads\'],iframe[src*=\'banner\']" +
                    "{display:none!important}';" +
                    "document.head.appendChild(s);" +
                    "})()";
                v.loadUrl(css);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progressBar.setProgress(p);
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });

        // Toggle address bar khi tap vao webview
        webView.setOnClickListener(v -> toggleAddressBar());

        btnBack.setOnClickListener(v -> finish());
        btnGo.setOnClickListener(v -> navigate());
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnNavBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnNavFwd.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        etUrl.setOnEditorActionListener((v, action, e) -> { navigate(); return true; });

        String startUrl = "https://nhentai.net";
        etUrl.setText(startUrl);
        webView.loadUrl(startUrl);
    }

    private void toggleAddressBar() {
        if (addressBar.getVisibility() == View.VISIBLE) {
            addressBar.setVisibility(View.GONE);
        } else {
            addressBar.setVisibility(View.VISIBLE);
        }
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        if (isFullscreen) {
            addressBar.setVisibility(View.GONE);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } else {
            addressBar.setVisibility(View.VISIBLE);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE
            );
        }
    }

    private void navigate() {
        String url = etUrl.getText().toString().trim();
        if (!url.startsWith("http")) url = "https://" + url;
        webView.loadUrl(url);
        addressBar.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            toggleFullscreen();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
