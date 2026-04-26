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
        // Live streams / cam ads
        "stripchat.com","stripchat.global","trafficjunky.com","trafficjunky.net",
        // Adult ad networks
        "exoclick.com","juicyads.com","adnium.com","plugrush.com",
        "tsyndicate.com","trafficstars.com","adspyglass.com","adtng.com",
        "etahub.com","silvercdn.com","ero-advertising.com","hilltopads.net",
        "revcontent.com","popcash.net","propellerads.com","popads.net",
        "adcash.com","clickadu.com","bidvertiser.com","yllix.com",
        "zeropark.com","clickaine.com","adsterra.com","pushcrew.com",
        // General ad networks
        "doubleclick.net","googlesyndication.com","adservice.google.com",
        "amazon-adsystem.com","scorecardresearch.com","quantserve.com",
        "outbrain.com","taboola.com","criteo.com","rubiconproject.com",
        "openx.net","pubmatic.com","appnexus.com","advertising.com",
        // Trackers
        "googletagmanager.com","hotjar.com","mixpanel.com",
        "segment.com","intercom.io","zendesk.com"
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
                // Inject advanced CSS + JS adblock
                String adblock = "javascript:(function(){" +
                    // Xoa popup overlay
                    "document.querySelectorAll('[class*=popup],[class*=modal],[id*=popup],[id*=modal],[class*=overlay]').forEach(e=>e.remove());" +
                    // CSS an quang cao
                    "var s=document.createElement('style');" +
                    "s.innerHTML='" +
                    "iframe,object,embed{display:none!important}" +
                    "[class*=ad],[id*=ad],[class*=ads],[id*=ads]," +
                    "[class*=banner],[id*=banner]," +
                    "[class*=sponsor],[id*=sponsor]," +
                    "[class*=popup],[id*=popup]," +
                    "[class*=promo],[id*=promo]," +
                    "[class*=adverti],[id*=adverti]," +
                    ".exo-container,.adnium,.adsbox," +
                    "div[style*=\'position:fixed\'],div[style*=\'position: fixed\']" +
                    "{display:none!important;visibility:hidden!important;}" +
                    // Fit anh manga vua man hinh
                    "img{max-width:100%!important;height:auto!important}" +
                    "body{overflow-x:hidden!important;margin:0!important;padding:0!important}" +
                    "';" +
                    "document.head.appendChild(s);" +
                    // Chan push notification
                    "if(window.Notification)window.Notification.requestPermission=function(){return Promise.resolve('denied')};" +
                    // An fixed elements (thường là ads)
                    "document.querySelectorAll('*').forEach(function(el){" +
                    "var style=window.getComputedStyle(el);" +
                    "if(style.position==='fixed'&&el.tagName!=='VIDEO'&&el.tagName!=='CANVAS'){" +
                    "var rect=el.getBoundingClientRect();" +
                    "if(rect.width>100&&rect.height>100)el.style.display='none'" +
                    "}});" +
                    "})()";
                v.loadUrl(adblock);
                // Fit man hinh doc truyen
                v.loadUrl("javascript:(function(){" +
                    // Fit anh truyen vua chieu rong man hinh
                    "var style=document.createElement('style');" +
                    "style.innerHTML=" +
                    "'img{width:100%!important;max-width:100vw!important;height:auto!important;display:block!important;margin:0 auto!important;}'" +
                    "+'#image-container,#image,.image-container,.reader-container{width:100%!important;max-width:100vw!important;padding:0!important;margin:0!important;}'" +
                    "+'body,html{width:100%!important;overflow-x:hidden!important;}';" +
                    "document.head.appendChild(style);" +
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

        // Toggle address bar khi tap vao webview
        webView.setOnClickListener(v -> toggleAddressBar());

        btnBack.setOnClickListener(v -> finish());
        btnGo.setOnClickListener(v -> navigate());
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnNavBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnNavFwd.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnFullscreen.setOnClickListener(v -> {
            // Fit anh truyen vua man hinh, zoom out de thay ca trang
            webView.loadUrl("javascript:(function(){" +
                "var imgs=document.querySelectorAll('#image-container img,#image img,.page img,img[id*=image],img[class*=image]');" +
                "if(imgs.length==0)imgs=document.querySelectorAll('img');" +
                "imgs.forEach(function(img){" +
                "img.style.width='100vw';" +
                "img.style.height='100vh';" +
                "img.style.objectFit='contain';" +
                "img.style.display='block';" +
                "img.style.margin='0 auto';" +
                "});" +
                "window.scrollTo(0,0);" +
                "})()");
        });

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
