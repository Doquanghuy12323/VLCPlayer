package com.vlcplayer.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MangaBrowserActivity extends AppCompatActivity {
    private static final String DEFAULT_URL = "https://nhentai.net";
    private static final String STATE_WEB = "manga_web_state";
    private static final String STATE_URL = "manga_current_url";
    private static final String STATE_DRAFT = "manga_url_draft";
    private static final String STATE_DRAFT_DIRTY = "manga_url_draft_dirty";
    private static final String STATE_DRAFT_FOCUSED = "manga_url_draft_focused";
    private static final String STATE_SELECTION = "manga_url_selection";
    private static final String STATE_FULLSCREEN = "manga_fullscreen";
    private static final String STATE_ERROR = "manga_page_error";
    private static final String STATE_SCROLL_X = "manga_scroll_x";
    private static final String STATE_SCROLL_Y = "manga_scroll_y";

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;
    private View addressBar;
    private View errorPanel;
    private TextView errorUrl;
    private View exitFullscreen;
    private String currentUrl;
    private boolean loading;
    private boolean pageError;
    private boolean fullscreen;
    private boolean updatingAddress;
    private boolean urlDraftDirty;
    private boolean restoringError;
    private boolean awaitingPageStart;
    private String navigationStartUrl;
    private String scrollRestoreUrl;
    private int scrollRestoreX;
    private int scrollRestoreY;

    private static final Set<String> AD_DOMAINS = new HashSet<>(Arrays.asList(
        "stripchat.com", "stripchat.global", "trafficjunky.com", "trafficjunky.net",
        "exoclick.com", "juicyads.com", "adnium.com", "plugrush.com",
        "tsyndicate.com", "trafficstars.com", "adspyglass.com", "adtng.com",
        "etahub.com", "silvercdn.com", "ero-advertising.com", "hilltopads.net",
        "popcash.net", "propellerads.com", "popads.net", "adcash.com",
        "clickadu.com", "bidvertiser.com", "adsterra.com", "zeropark.com",
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "amazon-adsystem.com", "scorecardresearch.com", "quantserve.com",
        "outbrain.com", "taboola.com", "criteo.com", "rubiconproject.com"
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
        webView = findViewById(R.id.webView);
        etUrl = findViewById(R.id.et_url);
        progressBar = findViewById(R.id.progress);
        addressBar = findViewById(R.id.address_bar);
        errorPanel = findViewById(R.id.browser_error);
        errorUrl = findViewById(R.id.browser_error_url);
        exitFullscreen = findViewById(R.id.btn_exit_fullscreen);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ws.setSafeBrowsingEnabled(true);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String ad : AD_DOMAINS) {
                        if (host.equals(ad) || host.endsWith("." + ad)) {
                            return new WebResourceResponse("text/plain", "utf-8",
                                    new ByteArrayInputStream(new byte[0]));
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockNonWebNavigation(request.getUrl().toString(), request.isForMainFrame());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockNonWebNavigation(url, true);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (restoringError) {
                    view.stopLoading();
                    showPageError();
                    return;
                }
                if (!awaitingPageStart || navigationStartUrl == null) navigationStartUrl = url;
                awaitingPageStart = false;
                currentUrl = url;
                loading = true;
                pageError = false;
                showPage();
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
                updateAddressIfIdle();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // A canceled page may finish after a newer address has been requested.
                if (!isActivePage(view, url) || pageError || restoringError) return;
                currentUrl = url;
                loading = false;
                progressBar.setVisibility(View.GONE);
                updateAddressIfIdle();
                applyReadingStyle(view, () -> restoreSavedScroll(view, url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && isActivePage(view, request.getUrl().toString())) {
                    currentUrl = request.getUrl().toString();
                    showPageError();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // This older callback is only for the main resource on Android 5.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && isActivePage(view, failingUrl)) {
                    currentUrl = failingUrl;
                    showPageError();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && isActivePage(view, request.getUrl().toString())) {
                    currentUrl = request.getUrl().toString();
                    showPageError();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (pageError || restoringError || !loading) return;
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_go).setOnClickListener(v -> navigateDraft());
        findViewById(R.id.btn_browser_menu).setOnClickListener(this::showBrowserMenu);
        findViewById(R.id.btn_browser_retry).setOnClickListener(v -> reloadPage());
        exitFullscreen.setOnClickListener(v -> setFullscreen(false));
        etUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!updatingAddress) urlDraftDirty = true;
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        etUrl.setOnFocusChangeListener((v, focused) -> {
            if (!focused) updateAddressIfIdle();
        });
        etUrl.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (event == null || event.getAction() == KeyEvent.ACTION_UP) navigateDraft();
                return true;
            }
            return false;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (fullscreen) setFullscreen(false);
                else if (webView.canGoBack()) navigateHistory(false);
                else finish();
            }
        });

        if (savedInstanceState != null) {
            currentUrl = savedInstanceState.getString(STATE_URL);
            navigationStartUrl = currentUrl;
            restoringError = savedInstanceState.getBoolean(STATE_ERROR);
            pageError = restoringError;
            scrollRestoreUrl = currentUrl;
            scrollRestoreX = savedInstanceState.getInt(STATE_SCROLL_X);
            scrollRestoreY = savedInstanceState.getInt(STATE_SCROLL_Y);
            String draft = savedInstanceState.getString(STATE_DRAFT, currentUrl);
            setAddress(draft == null ? "" : draft);
            urlDraftDirty = savedInstanceState.getBoolean(STATE_DRAFT_DIRTY);
            if (savedInstanceState.getBoolean(STATE_DRAFT_FOCUSED)) {
                etUrl.requestFocus();
                etUrl.setSelection(Math.max(0, Math.min(etUrl.length(),
                        savedInstanceState.getInt(STATE_SELECTION, etUrl.length()))));
            }
            Bundle history = savedInstanceState.getBundle(STATE_WEB);
            boolean restored = history != null && webView.restoreState(history) != null;
            if (restoringError) {
                webView.stopLoading();
                showPageError();
            } else if (restored) {
                loading = true;
                progressBar.setVisibility(View.VISIBLE);
            } else {
                loadInitialPage(currentUrl);
            }
            setFullscreen(savedInstanceState.getBoolean(STATE_FULLSCREEN));
        } else {
            String startUrl = getIntent() == null ? null : getIntent().getStringExtra("start_url");
            loadInitialPage(startUrl == null ? DEFAULT_URL : startUrl);
        }
    }

    private boolean blockNonWebNavigation(String url, boolean mainFrame) {
        if (MangaBrowserUrlPolicy.normalize(url) != null) {
            if (mainFrame) {
                if (!loading) navigationStartUrl = url;
                currentUrl = url;
            }
            return false;
        }
        if (mainFrame) Toast.makeText(this, R.string.manga_browser_invalid_url, Toast.LENGTH_SHORT).show();
        return true;
    }

    private static boolean samePage(String first, String second) {
        return first != null && first.equals(second);
    }

    private boolean isActivePage(WebView view, String url) {
        return samePage(url, currentUrl)
                || (!awaitingPageStart && samePage(url, view.getUrl())
                    && samePage(navigationStartUrl, view.getOriginalUrl()));
    }

    private void loadInitialPage(String input) {
        String url = MangaBrowserUrlPolicy.normalize(input);
        if (url == null) {
            Toast.makeText(this, R.string.manga_browser_invalid_url, Toast.LENGTH_SHORT).show();
            url = DEFAULT_URL;
        }
        // A restored URL draft belongs to the user even if restoring WebView history failed.
        if (!urlDraftDirty && !etUrl.hasFocus()) setAddress(url);
        loadPage(url);
    }

    private void navigateDraft() {
        String url = MangaBrowserUrlPolicy.normalize(etUrl.getText().toString());
        if (url == null) {
            etUrl.setError(getString(R.string.manga_browser_invalid_url));
            return;
        }
        etUrl.setError(null);
        urlDraftDirty = false;
        setAddress(url);
        hideKeyboard();
        loadPage(url);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
        etUrl.clearFocus();
        webView.requestFocus();
    }

    private void loadPage(String url) {
        webView.stopLoading();
        beginNavigation(url);
        webView.loadUrl(url);
    }

    private void beginNavigation(String url) {
        restoringError = false;
        pageError = false;
        loading = true;
        awaitingPageStart = true;
        currentUrl = url;
        navigationStartUrl = url;
        scrollRestoreUrl = null;
        showPage();
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        updateAddressIfIdle();
    }

    private void reloadPage() {
        String url = MangaBrowserUrlPolicy.normalize(currentUrl);
        if (url == null) return;
        // Retry the failed address; the WebView can still have the previous page in its history.
        if (pageError) loadPage(url);
        else {
            beginNavigation(url);
            webView.reload();
        }
    }

    private void navigateHistory(boolean forward) {
        int step = forward ? 1 : -1;
        android.webkit.WebBackForwardList history = webView.copyBackForwardList();
        int index = history.getCurrentIndex() + step;
        if (index < 0 || index >= history.getSize()) return;
        String url = history.getItemAtIndex(index).getUrl();
        if (MangaBrowserUrlPolicy.normalize(url) == null) return;
        webView.stopLoading();
        beginNavigation(url);
        webView.goBackOrForward(step);
    }

    private void showPage() {
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void showPageError() {
        pageError = true;
        loading = false;
        scrollRestoreUrl = null;
        progressBar.setVisibility(View.GONE);
        errorUrl.setText(currentUrl);
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        updateAddressIfIdle();
    }

    private void setAddress(String address) {
        updatingAddress = true;
        etUrl.setText(address);
        updatingAddress = false;
    }

    private void updateAddressIfIdle() {
        if (!etUrl.hasFocus() && !urlDraftDirty && currentUrl != null) setAddress(currentUrl);
    }

    private void showBrowserMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.inflate(R.menu.manga_browser_menu);
        menu.getMenu().findItem(R.id.browser_nav_back).setEnabled(webView.canGoBack());
        menu.getMenu().findItem(R.id.browser_nav_forward).setEnabled(webView.canGoForward());
        menu.getMenu().findItem(R.id.browser_reload).setEnabled(currentUrl != null);
        boolean validPage = !loading && !pageError
                && MangaBrowserUrlPolicy.normalize(webView.getUrl()) != null;
        menu.getMenu().findItem(R.id.browser_bookmark).setEnabled(validPage);
        menu.getMenu().findItem(R.id.browser_fit_image).setEnabled(validPage);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.browser_nav_back) navigateHistory(false);
            else if (id == R.id.browser_nav_forward) navigateHistory(true);
            else if (id == R.id.browser_reload) reloadPage();
            else if (id == R.id.browser_bookmark) saveBookmark();
            else if (id == R.id.browser_bookmark_list) showBookmarks();
            else if (id == R.id.browser_fit_image) fitImage();
            else if (id == R.id.browser_fullscreen) {
                hideKeyboard();
                setFullscreen(true);
            } else return false;
            return true;
        });
        menu.show();
    }

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        addressBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        exitFullscreen.setVisibility(enabled ? View.VISIBLE : View.GONE);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars());
        else controller.show(WindowInsetsCompat.Type.systemBars());
    }

    private void applyReadingStyle(WebView view, Runnable afterStyle) {
        view.evaluateJavascript("(function(){" +
                "var s=document.getElementById('vlc-manga-style');" +
                "if(!s){s=document.createElement('style');s.id='vlc-manga-style';document.head.appendChild(s);}" +
                "s.textContent='img{max-width:100vw!important;width:100%!important;height:auto!important;}" +
                "body{overflow-x:hidden!important;margin:0!important}" +
                "iframe,object,embed,.ad,.ads,.advertisement,.ad-banner," +
                "[id=ad],[id=ads],[id^=ad-],[class~=ad],[class~=ads],[class~=advertisement]{display:none!important}';" +
                "if(window.Notification)window.Notification.requestPermission=function(){return Promise.resolve('denied')};" +
                "})()", value -> afterStyle.run());
    }

    private void restoreSavedScroll(WebView view, String url) {
        if (!samePage(url, scrollRestoreUrl) || !samePage(url, currentUrl)
                || isDestroyed() || pageError) return;
        final int x = scrollRestoreX;
        final int y = scrollRestoreY;
        scrollRestoreUrl = null;
        Runnable restore = () -> {
            if (!isDestroyed() && samePage(url, currentUrl) && !pageError) view.scrollTo(x, y);
        };
        // Wait for the renderer to apply the image sizing before restoring the reading position.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) { restore.run(); }
            });
        } else {
            view.postOnAnimation(restore);
        }
    }

    private void fitImage() {
        if (loading || pageError) return;
        webView.evaluateJavascript("(function(){" +
                "var imgs=Array.from(document.querySelectorAll('img'));" +
                "var img=imgs.sort(function(a,b){return b.naturalWidth-a.naturalWidth})[0];" +
                "if(!img)return;var vw=window.innerWidth,vh=window.innerHeight;" +
                "var iw=img.naturalWidth||vw,ih=img.naturalHeight||vh;var scale=Math.min(vw/iw,vh/ih);" +
                "var meta=document.querySelector('meta[name=viewport]');" +
                "if(!meta){meta=document.createElement('meta');meta.name='viewport';document.head.appendChild(meta);}" +
                "meta.content='width='+iw+',initial-scale='+scale+',maximum-scale=5,user-scalable=yes';" +
                "img.scrollIntoView({block:'start'});})()", null);
    }

    private void saveBookmark() {
        String url = MangaBrowserUrlPolicy.normalize(webView.getUrl());
        if (loading || pageError || url == null) return;
        SharedPreferences prefs = getSharedPreferences("manga_bookmarks", MODE_PRIVATE);
        String existing = prefs.getString("bookmarks", "");
        if (existing == null) existing = "";
        for (String line : existing.split("\n")) {
            int separator = line.indexOf('|');
            if (separator > 0 && url.equals(line.substring(0, separator))) {
                Toast.makeText(this, R.string.manga_browser_already_saved, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String title = webView.getTitle();
        if (title == null || title.trim().isEmpty()) title = url;
        String entry = url + "|" + title.replace('\n', ' ').replace('\r', ' ');
        prefs.edit().putString("bookmarks", entry + (existing.isEmpty() ? "" : "\n" + existing)).apply();
        Toast.makeText(this, R.string.manga_browser_saved, Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks() {
        SharedPreferences prefs = getSharedPreferences("manga_bookmarks", MODE_PRIVATE);
        String data = prefs.getString("bookmarks", "");
        List<String> titles = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        if (data != null) {
            for (String line : data.split("\n")) {
                int separator = line.indexOf('|');
                if (separator <= 0) continue;
                String url = MangaBrowserUrlPolicy.normalize(line.substring(0, separator));
                if (url == null) continue;
                urls.add(url);
                String title = line.substring(separator + 1);
                titles.add(title.isEmpty() ? url : title);
            }
        }
        if (urls.isEmpty()) {
            Toast.makeText(this, R.string.manga_browser_no_bookmarks, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.manga_browser_bookmarks)
                .setItems(titles.toArray(new String[0]), (dialog, index) -> {
                    hideKeyboard();
                    urlDraftDirty = false;
                    loadPage(urls.get(index));
                })
                .setNegativeButton(R.string.manga_browser_clear_bookmarks, (dialog, which) -> {
                    prefs.edit().remove("bookmarks").apply();
                    Toast.makeText(this, R.string.manga_browser_bookmarks_cleared, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.manga_browser_close_dialog, null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Bundle history = new Bundle();
        webView.saveState(history);
        outState.putBundle(STATE_WEB, history);
        outState.putString(STATE_URL, currentUrl);
        outState.putString(STATE_DRAFT, etUrl.getText().toString());
        outState.putBoolean(STATE_DRAFT_DIRTY, urlDraftDirty);
        outState.putBoolean(STATE_DRAFT_FOCUSED, etUrl.hasFocus());
        outState.putInt(STATE_SELECTION, etUrl.getSelectionStart());
        outState.putBoolean(STATE_FULLSCREEN, fullscreen);
        outState.putBoolean(STATE_ERROR, pageError);
        outState.putInt(STATE_SCROLL_X, webView.getScrollX());
        outState.putInt(STATE_SCROLL_Y, webView.getScrollY());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
