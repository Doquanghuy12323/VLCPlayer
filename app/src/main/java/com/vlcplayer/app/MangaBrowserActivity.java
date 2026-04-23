package com.vlcplayer.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;

public class MangaBrowserActivity extends AppCompatActivity {

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manga_browser);

        webView     = findViewById(R.id.webView);
        etUrl       = findViewById(R.id.et_url);
        progressBar = findViewById(R.id.progress);

        ImageButton btnBack    = findViewById(R.id.btn_back);
        ImageButton btnGo      = findViewById(R.id.btn_go);
        ImageButton btnRefresh = findViewById(R.id.btn_refresh);
        ImageButton btnNavBack = findViewById(R.id.btn_nav_back);
        ImageButton btnNavFwd  = findViewById(R.id.btn_nav_fwd);

        // WebView settings
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                etUrl.setText(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progressBar.setProgress(p);
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });

        btnBack.setOnClickListener(v -> finish());
        btnGo.setOnClickListener(v -> navigate());
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnNavBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnNavFwd.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });

        etUrl.setOnEditorActionListener((v, action, e) -> { navigate(); return true; });

        // Load trang mac dinh
        String startUrl = "https://nhentai.net";
        etUrl.setText(startUrl);
        webView.loadUrl(startUrl);
    }

    private void navigate() {
        String url = etUrl.getText().toString().trim();
        if (!url.startsWith("http")) url = "https://" + url;
        webView.loadUrl(url);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
