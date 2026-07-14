package com.vlcplayer.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
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
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MangaBrowserActivity extends AppCompatActivity {

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;
    private LinearLayout addressBar;

    private static final Set<String> AD_DOMAINS = new HashSet<>(Arrays.asList(
        "stripchat.com","stripchat.global","trafficjunky.com","trafficjunky.net",
        "exoclick.com","juicyads.com","adnium.com","plugrush.com",
        "tsyndicate.com","trafficstars.com","adspyglass.com","adtng.com",
        "etahub.com","silvercdn.com","ero-advertising.com","hilltopads.net",
        "popcash.net","propellerads.com","popads.net","adcash.com",
        "clickadu.com","bidvertiser.com","adsterra.com","zeropark.com",
        "doubleclick.net","googlesyndication.com","adservice.google.com",
        "amazon-adsystem.com","scorecardresearch.com","quantserve.com",
        "outbrain.com","taboola.com","criteo.com","rubiconproject.com"
    ));

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
        addressBar  = findViewById(R.id.address_bar);

        ImageButton btnBack         = findViewById(R.id.btn_back);
        ImageButton btnGo           = findViewById(R.id.btn_go);
        ImageButton btnRefresh      = findViewById(R.id.btn_refresh);
        ImageButton btnNavBack      = findViewById(R.id.btn_nav_back);
        ImageButton btnNavFwd       = findViewById(R.id.btn_nav_fwd);
        ImageButton btnFullscreen   = findViewById(R.id.btn_fullscreen);
        ImageButton btnBookmark     = findViewById(R.id.btn_bookmark);
        ImageButton btnBookmarkList = findViewById(R.id.btn_bookmark_list);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(true);
        }
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String ad : AD_DOMAINS) {
                        if (host.equals(ad) || host.endsWith("." + ad)) {
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
                // Fit anh manga vua man hinh + an quang cao
                v.loadUrl("javascript:(function(){" +
                    "var s=document.createElement('style');" +
                    "s.innerHTML='img{max-width:100vw!important;width:100%!important;height:auto!important;}" +
                    "body{overflow-x:hidden!important;margin:0!important}" +
                    "iframe,object,embed,[class*=ad],[id*=ad],[class*=banner],[id*=banner]" +
                    "{display:none!important}';" +
                    "document.head.appendChild(s);" +
                    "if(window.Notification)window.Notification.requestPermission=function(){return Promise.resolve('denied')};" +
                    "})()");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progressBar.setProgress(p);
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });

        btnBack.setOnClickListener(v -> finish());
        btnGo.setOnClickListener(v -> navigate());
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnNavBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnNavFwd.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });

        btnFullscreen.setOnClickListener(v -> {
            // Zoom fit anh lon nhat vua man hinh
            webView.loadUrl("javascript:(function(){" +
                "var imgs=Array.from(document.querySelectorAll('img'));" +
                "var img=imgs.sort(function(a,b){return b.naturalWidth-a.naturalWidth})[0];" +
                "if(!img)return;" +
                "var vw=window.innerWidth,vh=window.innerHeight;" +
                "var iw=img.naturalWidth||vw,ih=img.naturalHeight||vh;" +
                "var scale=Math.min(vw/iw,vh/ih);" +
                "var meta=document.querySelector('meta[name=viewport]');" +
                "if(!meta){meta=document.createElement('meta');meta.name='viewport';document.head.appendChild(meta);}" +
                "meta.content='width='+iw+',initial-scale='+scale+',maximum-scale=5,user-scalable=yes';" +
                "img.scrollIntoView({block:'start'});" +
                "})()");
        });

        btnBookmark.setOnClickListener(v -> saveBookmark());
        btnBookmarkList.setOnClickListener(v -> showBookmarks());

        etUrl.setOnEditorActionListener((v, action, e) -> { navigate(); return true; });

        // Nhan URL tu intent neu co
        String startUrl = "https://nhentai.net";
        android.content.Intent startIntent = getIntent();
        if (startIntent != null && startIntent.getStringExtra("start_url") != null) {
            startUrl = startIntent.getStringExtra("start_url");
        }
        etUrl.setText(startUrl);
        webView.loadUrl(startUrl);
    }

    private void saveBookmark() {
        String url = webView.getUrl();
        String title = webView.getTitle();
        if (url == null) return;
        SharedPreferences prefs = getSharedPreferences("manga_bookmarks", MODE_PRIVATE);
        String existing = prefs.getString("bookmarks", "");
        // Format: url1|title1\nurl2|title2
        String entry = url + "|" + (title != null ? title : url);
        if (!existing.contains(url)) {
            String newVal = entry + (existing.isEmpty() ? "" : "\n" + existing);
            prefs.edit().putString("bookmarks", newVal).apply();
            Toast.makeText(this, "Da luu trang!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Da luu roi", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarks() {
        SharedPreferences prefs = getSharedPreferences("manga_bookmarks", MODE_PRIVATE);
        String data = prefs.getString("bookmarks", "");
        if (data.isEmpty()) {
            Toast.makeText(this, "Chua co trang da luu", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lines = data.split("\n");
        List<String> titles = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (String line : lines) {
            int sep = line.indexOf("|");
            if (sep > 0) {
                urls.add(line.substring(0, sep));
                titles.add(line.substring(sep + 1));
            }
        }
        new AlertDialog.Builder(this)
            .setTitle("Trang da luu")
            .setItems(titles.toArray(new String[0]), (d, which) -> webView.loadUrl(urls.get(which)))
            .setNegativeButton("Xoa tat ca", (d, w) -> {
                prefs.edit().remove("bookmarks").apply();
                Toast.makeText(this, "Da xoa", Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton("Dong", null)
            .show();
    }

    private void navigate() {
        String url = etUrl.getText().toString().trim();
        if (!url.startsWith("http")) url = "https://" + url;
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
