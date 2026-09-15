package com.solarpanel.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

/**
 * Solarpanel 安卓客户端。
 *
 * 这是一个给自托管 Solarpanel 导航面板使用的 WebView 外壳，设计要点：
 * 1. 页面内的 http/https 链接一律在当前 WebView 内打开，不跳转外部浏览器；
 * 2. 首次启动要求填写服务器地址，可在右上角菜单中随时修改；
 * 3. 允许明文 HTTP 与自签名证书，以适配局域网 / NAS 自托管环境；
 * 4. 支持后台管理面板所需的文件上传与文件下载；
 * 5. 返回键优先回退网页历史，双击退出应用。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final long DOUBLE_BACK_INTERVAL_MS = 2000L;

    /**
     * 自托管服务常使用自签名证书，严格校验会导致整站无法访问。
     * 若你使用受信任的正式证书，可把这里改为 false 以恢复严格校验。
     */
    private static final boolean ALLOW_SELF_SIGNED_CERTIFICATE = true;

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private MaterialToolbar toolbar;
    private View setupPanel;
    private View errorPanel;
    private TextInputLayout serverInputLayout;
    private EditText serverInput;
    private MaterialButton connectButton;

    private ValueCallback<Uri[]> filePathCallback;
    private String userAgent;

    private boolean mainFrameFailed;
    private boolean sslWarningShown;
    private long lastBackPressedAt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        configureWebView();
        configureWebChromeClient();
        configureWebViewClient();
        configureDownloadListener();
        configureToolbar();
        configureBackHandling();
        configureSetupPanel();

        String savedServer = Prefs.getServer(this);
        if (TextUtils.isEmpty(savedServer)) {
            showSetupPanel("");
        } else {
            loadServer(savedServer);
        }
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private void bindViews() {
        webView = findViewById(R.id.webView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);
        toolbar = findViewById(R.id.toolbar);
        setupPanel = findViewById(R.id.setupPanel);
        errorPanel = findViewById(R.id.errorPanel);
        serverInputLayout = findViewById(R.id.serverInputLayout);
        serverInput = findViewById(R.id.serverInput);
        connectButton = findViewById(R.id.connectButton);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // 关键：关闭多窗口，使 target="_blank" / window.open() 的链接
        // 留在当前 WebView 中打开，而不是弹到系统浏览器。
        settings.setSupportMultipleWindows(false);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // 站点全部来自远程，不需要本地文件访问能力
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        userAgent = settings.getUserAgentString() + " SolarpanelAndroid/" + BuildConfig.VERSION_NAME;
        settings.setUserAgentString(userAgent);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.app_background));
        WebView.setWebContentsDebuggingEnabled(false);

        swipeRefresh.setColorSchemeResources(R.color.brand_blue_dark, R.color.brand_tan_dark);
        swipeRefresh.setOnRefreshListener(() -> {
            if (webView.getUrl() != null) {
                webView.reload();
            } else {
                swipeRefresh.setRefreshing(false);
            }
        });
    }

    private void configureWebChromeClient() {
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setProgress(newProgress);
                    if (progressBar.getVisibility() != View.VISIBLE) {
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                toolbar.setSubtitle(TextUtils.isEmpty(title) ? null : title);
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // 后台管理面板的图标 / 壁纸上传依赖这里
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(
                            Intent.createChooser(intent, getString(R.string.file_chooser_title)),
                            REQUEST_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
    }

    private void configureWebViewClient() {
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mainFrameFailed = false;
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                swipeRefresh.setRefreshing(false);
                if (!mainFrameFailed) {
                    hideErrorPanel();
                }
            }

            @Override
            public void onReceivedError(WebView view,
                                        WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameFailed = true;
                    showErrorPanel();
                }
            }

            @Override
            public void onReceivedSslError(WebView view,
                                           SslErrorHandler handler,
                                           SslError error) {
                if (ALLOW_SELF_SIGNED_CERTIFICATE) {
                    handler.proceed();
                    if (!sslWarningShown) {
                        sslWarningShown = true;
                        toast(R.string.toast_ssl_warning);
                    }
                } else {
                    handler.cancel();
                }
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view,
                                                  HttpAuthHandler handler,
                                                  String host,
                                                  String realm) {
                // 兼容 Nginx 反代上常见的 HTTP Basic 认证
                showHttpAuthDialog(handler, host, realm);
            }
        });
    }

    private void configureDownloadListener() {
        webView.setDownloadListener((url, userAgentHeader, contentDisposition, mimeType, contentLength) ->
                enqueueDownload(url, userAgentHeader, contentDisposition, mimeType));
    }

    private void configureToolbar() {
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_refresh) {
                if (webView.getUrl() != null) {
                    webView.reload();
                }
                return true;
            }
            if (id == R.id.action_change_server) {
                showSetupPanel(Prefs.getServer(this));
                return true;
            }
            if (id == R.id.action_clear_data) {
                confirmClearWebData();
                return true;
            }
            if (id == R.id.action_open_browser) {
                openInSystemBrowser();
                return true;
            }
            if (id == R.id.action_about) {
                showAboutDialog();
                return true;
            }
            return false;
        });
    }

    private void configureBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (errorPanel.getVisibility() == View.VISIBLE) {
                    hideErrorPanel();
                    return;
                }
                if (setupPanel.getVisibility() == View.VISIBLE) {
                    if (!TextUtils.isEmpty(Prefs.getServer(MainActivity.this))) {
                        hideSetupPanel();
                    } else {
                        finishAfterDoubleBack();
                    }
                    return;
                }
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                finishAfterDoubleBack();
            }
        });
    }

    private void configureSetupPanel() {
        connectButton.setOnClickListener(v -> applyServerInput());
        serverInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEND) {
                applyServerInput();
                return true;
            }
            return false;
        });

        View.OnClickListener retry = v -> {
            hideErrorPanel();
            String server = Prefs.getServer(this);
            if (TextUtils.isEmpty(server)) {
                showSetupPanel("");
            } else if (webView.getUrl() != null) {
                webView.reload();
            } else {
                webView.loadUrl(server);
            }
        };
        findViewById(R.id.errorRetryButton).setOnClickListener(retry);
        findViewById(R.id.errorChangeButton).setOnClickListener(v -> showSetupPanel(Prefs.getServer(this)));
    }

    // ------------------------------------------------------------------
    // 链接调度
    // ------------------------------------------------------------------

    /**
     * @return true 表示已由本应用处理（不再交给 WebView），
     * false 表示交给 WebView 在当前页面内加载。
     */
    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return true;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);

        switch (scheme) {
            case "http":
            case "https":
            case "about":
            case "data":
            case "blob":
            case "javascript":
                // 全部留在应用内的 WebView 中打开
                return false;
            case "intent":
                return startAndroidIntent(uri);
            case "tel":
            case "mailto":
            case "sms":
            case "smsto":
            case "geo":
            case "market":
            case "weixin":
            case "alipays":
            case "taobao":
                // 交给系统对应的应用处理，这不属于"外部浏览器"
                return startExternal(uri);
            default:
                return startExternal(uri);
        }
    }

    private boolean startExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.toast_no_browser);
        }
        return true;
    }

    private boolean startAndroidIntent(Uri uri) {
        try {
            Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast(R.string.toast_no_browser);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 服务器地址
    // ------------------------------------------------------------------

    private void applyServerInput() {
        String normalized = Urls.normalize(serverInput.getText() == null
                ? "" : serverInput.getText().toString());
        if (normalized == null) {
            serverInputLayout.setError(getString(R.string.toast_url_invalid));
            return;
        }
        serverInputLayout.setError(null);
        Prefs.setServer(this, normalized);
        toast(R.string.toast_url_saved);

        webView.stopLoading();
        webView.clearHistory();
        loadServer(normalized);
    }

    private void loadServer(String url) {
        hideSetupPanel();
        hideErrorPanel();
        mainFrameFailed = false;
        sslWarningShown = false;
        webView.loadUrl(url);
    }

    private void showSetupPanel(String prefill) {
        setupPanel.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        serverInput.setText(prefill == null ? "" : prefill);
        serverInput.setSelection(serverInput.getText().length());
        serverInputLayout.setError(null);
        serverInput.requestFocus();
    }

    private void hideSetupPanel() {
        setupPanel.setVisibility(View.GONE);
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
    }

    private void showErrorPanel() {
        errorPanel.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
    }

    private void hideErrorPanel() {
        errorPanel.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    private void enqueueDownload(String url,
                                 String userAgentHeader,
                                 String contentDisposition,
                                 String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            request.setTitle(fileName);
            request.setDescription(url);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            // 备份导出等接口需要登录态，必须带上 Cookie
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (!TextUtils.isEmpty(userAgentHeader)) {
                request.addRequestHeader("User-Agent", userAgentHeader);
            }

            try {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
            } catch (Exception publicDirUnavailable) {
                request.setDestinationInExternalFilesDir(
                        this, Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("DownloadManager unavailable");
            }
            manager.enqueue(request);
            toast(R.string.toast_download_start);
        } catch (Exception e) {
            toast(R.string.toast_download_fail);
        }
    }

    // ------------------------------------------------------------------
    // 菜单动作
    // ------------------------------------------------------------------

    private void confirmClearWebData() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_data_title)
                .setMessage(R.string.clear_data_message)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (dialog, which) -> clearWebData())
                .show();
    }

    private void clearWebData() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();

        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();

        WebStorage.getInstance().deleteAllData();

        toast(R.string.toast_cleared);
        String server = Prefs.getServer(this);
        if (!TextUtils.isEmpty(server)) {
            webView.loadUrl(server);
        }
    }

    private void openInSystemBrowser() {
        String url = webView.getUrl();
        if (TextUtils.isEmpty(url)) {
            url = Prefs.getServer(this);
        }
        if (TextUtils.isEmpty(url)) {
            return;
        }
        startExternal(Uri.parse(url));
    }

    private void showAboutDialog() {
        String server = Prefs.getServer(this);
        if (TextUtils.isEmpty(server)) {
            server = getString(R.string.about_no_server);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, server))
                .setPositiveButton(R.string.dialog_ok, null)
                .show();
    }

    private void showHttpAuthDialog(HttpAuthHandler handler, String host, String realm) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                getResources().getDisplayMetrics());
        container.setPadding(padding, padding / 2, padding, 0);

        final EditText username = new EditText(this);
        username.setHint(R.string.auth_username);
        username.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(username, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText password = new EditText(this);
        password.setHint(R.string.auth_password);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.addView(password, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(TextUtils.isEmpty(realm) ? host : realm)
                .setView(container)
                .setCancelable(false)
                .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> handler.cancel())
                .setPositiveButton(R.string.dialog_ok, (dialog, which) ->
                        handler.proceed(username.getText().toString(), password.getText().toString()))
                .show();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void finishAfterDoubleBack() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < DOUBLE_BACK_INTERVAL_MS) {
            finish();
        } else {
            lastBackPressedAt = now;
            toast(R.string.toast_exit_hint);
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
