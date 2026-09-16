package com.solarpanel.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Solarpanel 安卓客户端。
 *
 * 这是一个给自托管 Solarpanel 导航面板使用的 WebView 外壳，设计要点：
 * 1. 页面内的 http/https 链接一律在当前 WebView 内打开，不跳转外部浏览器；
 * 2. 首次启动要求填写服务器地址，可在右上角菜单中随时修改；
 * 3. 允许明文 HTTP 与自签名证书，以适配局域网 / NAS 自托管环境；
 * 4. 支持后台管理面板所需的文件上传与文件下载；
 * 5. 返回键优先回退网页历史，双击退出应用；
 * 6. 适配面板 v2.1.00 的 PWA：清除数据时同步注销 Service Worker 并清空离线缓存。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final long DOUBLE_BACK_INTERVAL_MS = 2000L;

    /** 顶栏收起 / 展开的动画时长。 */
    private static final long TOP_BAR_ANIM_MS = 180L;

    /** 网页滚动超过这个像素才算一次方向变化，避免惯性滚动末尾顶栏来回闪。 */
    private static final int SCROLL_EPSILON_PX = 3;

    /** JS 注入不可用时，用手指滑动距离兜底判断方向（约 24dp）。 */
    private static final int FALLBACK_TOUCH_THRESHOLD_DP = 24;

    /**
     * 注入到网页里的滚动监听，把滚动方向回报给原生层，用于顶栏自动收放。
     *
     * - 挂在 document 的捕获阶段，页面内任意滚动容器（含懒加载列表）都能命中；
     * - 位移小于 3px 不上报，过滤抖动；
     * - 带 __solarpanelScrollHook 标记，重复注入不会叠加监听；
     * - 识别捏合缩放：双指缩放期间 scrollTop 也会抖动变化，如果不拦截顶栏会疯狂闪烁，
     *   所以用 touchstart/touchmove 的触点数量判断是否正在缩放，同时监听 visualViewport.scale。
     *   缩放结束后冷却 300ms 再恢复上报，避免缩放收尾的 scrollTop 回弹误触发。
     */
    private static final String SCROLL_HOOK_JS =
            "(function(){"
                    + "if(window.__solarpanelScrollHook){return;}"
                    + "window.__solarpanelScrollHook=true;"
                    + "var last=null;"
                    + "var pinchActive=false;"
                    + "var pinchCooldownUntil=0;"
                    + "function pos(t){"
                    + "if(!t||t===document||t===document.body||t===document.documentElement){"
                    + "return window.pageYOffset||document.documentElement.scrollTop||document.body.scrollTop||0;}"
                    + "return t.scrollTop||0;}"
                    + "function scaleNotDefault(){"
                    + "try{return window.visualViewport&&Math.abs(window.visualViewport.scale-1)>0.05;}catch(e){return false;}}"
                    + "function zoomingNow(){"
                    + "return pinchActive||scaleNotDefault()||Date.now()<pinchCooldownUntil;}"
                    + "function fire(d,y){try{SolarpanelHost.onScroll(d,y<=4?1:0);}catch(e){}}"
                    + "document.addEventListener('touchstart',function(ev){"
                    + "if(ev.touches&&ev.touches.length>=2){"
                    + "pinchActive=true;}"
                    + "},true);"
                    + "document.addEventListener('touchmove',function(ev){"
                    + "if(ev.touches&&ev.touches.length>=2){pinchActive=true;}"
                    + "},true);"
                    + "document.addEventListener('touchend',function(ev){"
                    + "if(ev.touches&&ev.touches.length>=2){pinchActive=true;}else{pinchActive=false;pinchCooldownUntil=Date.now()+300;}"
                    + "},true);"
                    + "document.addEventListener('scroll',function(ev){"
                    + "if(zoomingNow()){last=null;return;}"
                    + "var y=pos(ev.target);"
                    + "if(last===null){last=y;return;}"
                    + "var d=y-last;last=y;"
                    + "if(d>3){fire(d,y);}else if(d<-3){fire(d,y);}"
                    + "},true);"
                    + "})();";

    /**
     * 面板移动端样式的兜底修正（幂等，重复注入只生效一次）。
     *
     * 上游面板在窄屏（≤640px）下把分组头部的操作区设成 width:100%，但 .group-head
     * 自身没有开 flex-wrap，于是分组标题被挤到只剩一个字的宽度——「飞牛」这类分组名
     * 会一个字一行地竖着排下来。该问题只在管理员 / 编辑登录后出现（排序按钮只有他们
     * 有），所以容易漏掉。这里补上换行：标题回到一行，操作区落到下一行。
     * 上游若已修复，这段样式与其一致，不会冲突；不想要的话删掉本常量与 injectPanelCssFix()
     * 的调用即可。
     */
    private static final String PANEL_CSS_FIX_JS =
            "(function(){"
                    + "if(document.getElementById('solarpanel-css-fix')){return;}"
                    + "var s=document.createElement('style');"
                    + "s.id='solarpanel-css-fix';"
                    + "s.textContent='@media (max-width:640px){"
                    + ".group-head{flex-wrap:wrap;}"
                    + ".group-head h2{flex:0 0 auto;}"
                    + ".group-head .desc{min-width:0;}"
                    + "}';"
                    + "(document.head||document.documentElement).appendChild(s);"
                    + "})();";

    /**
     * 面板 v2.1.00 起自带 PWA（Service Worker + Cache Storage 离线缓存）。
     *
     * 「清除缓存与登录状态」能清掉 Cookie、HTTP 缓存与 DOM 存储，但这三类都
     * 不包含 SW 注册与 Cache Storage——不清的话，面板的离线缓存会一直残留，
     * 「清除」名不副实。这段脚本在清除数据时执行：注销全部 SW 注册并清空
     * Cache Storage。页面在非安全上下文（HTTP 内网地址）下本来就没有 SW，
     * 脚本各分支都会自动跳过，不会有副作用。
     *
     * 实现要点：把 SW 注销和 Cache 删除两个异步操作包进 Promise.all，
     * 完成后再通过 JavaScriptInterface 回调原生层，确保后续的 Cookie 清理
     * 和 loadUrl 不会抢在 SW 清理完成之前执行。
     */
    private static final String PANEL_SW_CLEANUP_JS =
            "(function(){"
                    + "var pending=[];"
                    + "try{"
                    + "if(navigator.serviceWorker&&navigator.serviceWorker.getRegistrations){"
                    + "pending.push(navigator.serviceWorker.getRegistrations().then(function(rs){"
                    + "rs.forEach(function(r){try{r.unregister();}catch(e){}});"
                    + "}).catch(function(){}));}"
                    + "if(window.caches&&caches.keys){"
                    + "pending.push(caches.keys().then(function(ks){"
                    + "ks.forEach(function(k){try{caches.delete(k);}catch(e){}});"
                    + "}).catch(function(){}));}"
                    + "}catch(e){}"
                    + "Promise.all(pending).then(function(){"
                    + "try{SolarpanelHost.onSwCleanupDone();}catch(e){}"
                    + "}).catch(function(){"
                    + "try{SolarpanelHost.onSwCleanupDone();}catch(e){}"
                    + "});"
                    + "})();";

    /**
     * 自托管服务常使用自签名证书，严格校验会导致整站无法访问。
     * 若你使用受信任的正式证书，可把这里改为 false 以恢复严格校验。
     */
    private static final boolean ALLOW_SELF_SIGNED_CERTIFICATE = true;

    private WebView webView;
    private ProgressBar progressBar;
    private MaterialToolbar toolbar;
    private View topBar;
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
    /** 等待 Service Worker 清理完成后继续执行的回调；仅在 clearWebData 过程中非 null。 */
    private Runnable swCleanupContinuation;

    /** clearWebData 里给 SW 清理加的 3 秒超时处理器，用于在 onDestroy 里移除。 */
    private final Handler swCleanupTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable swCleanupTimeoutRunnable = () -> {
        Runnable cont = swCleanupContinuation;
        if (cont != null) {
            swCleanupContinuation = null;
            cont.run();
        }
    };

    /** Android 13+ 请求通知权限的 launcher；下载完成通知依赖该权限。 */
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    /** 等待通知权限授予后再执行的下载任务；仅在权限请求进行中非 null。 */
    private Runnable pendingDownload;

    /** 顶栏实测高度（首次布局后取得）。 */
    private int topBarHeight;
    private boolean topBarHidden;
    /** 顶栏是否已按首帧状态就位，避免第一次切换时播一段没必要的动画。 */
    private boolean topBarSettled;
    /** 网页是否已经开始上报滚动（正常情况恒为 true）。 */
    private volatile boolean jsScrollHookAlive;
    private float touchAnchorY;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ActivityResultLauncher 必须在 setContentView 之前注册
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    Runnable dl = pendingDownload;
                    pendingDownload = null;
                    if (dl != null) {
                        if (!granted) {
                            toast(R.string.toast_notification_denied);
                        }
                        dl.run();
                    }
                });

        setContentView(R.layout.activity_main);

        bindViews();
        configureWebView();
        configureWebChromeClient();
        configureWebViewClient();
        configureDownloadListener();
        configureToolbar();
        configureTopBar();
        configureScrollHiding();
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
        progressBar = findViewById(R.id.progressBar);
        toolbar = findViewById(R.id.toolbar);
        topBar = findViewById(R.id.topBar);
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
        settings.setTextZoom(100);   // 锁定系统"字体大小"设置，防止面板卡片布局因系统放缩错乱
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // 站点全部来自远程，不需要本地文件访问能力
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 强制桌面端 UA：不含 "Mobile" 标记，让 fnOS / 飞牛等桌面端服务器返回桌面布局。
        // Solarpanel 自己的面板有 viewport meta + 响应式 CSS，即使收到桌面 UA 也会按 device-width 正确渲染。
        // 相当于 Chrome 浏览器里的 "桌面版网站" 模式。
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.6478.126 Safari/537.36 "
                + "SolarpanelAndroid/" + BuildConfig.VERSION_NAME;
        settings.setUserAgentString(userAgent);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.app_background));
        WebView.setWebContentsDebuggingEnabled(false);

        // 暴露给网页的最小接口：只用来上报滚动方向，供顶栏自动收放使用
        webView.addJavascriptInterface(new ScrollBridge(), "SolarpanelHost");
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

            /**
             * 网络层拦截：对外部页面（非 Solarpanel 自己）的主文档 HTML，
             * 抓取后在返回给 WebView 之前把 <meta name="viewport"> 删除。
             *
             * 这和 Chrome 浏览器的「桌面版网站」模式原理一致——
             * 渲染引擎从第一帧开始就看不到 viewport meta，
             * 自然会用默认 ~980px 桌面宽度渲染，再配合
             * setUseWideViewPort(true) + setLoadWithOverviewMode(true) +
             * setInitialScale(0) 自动等比缩小到手机屏幕。
             *
             * 之前用 evaluateJavascript 事后删除 viewport meta 是错的——
             * 等到 JS 能执行时，渲染引擎已经按 360px 完成了 layout + paint。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return null;   // 只拦主文档，图片/JS/CSS 等子资源放行
                }
                // 只拦截 GET 主文档请求。
                // POST（如表单登录提交）等带 body 的请求必须交给 WebView 原生处理，
                // 否则 fetchAndStripViewport 会把 POST 强转成 GET，body 被丢弃，
                // 服务器因收不到凭据而返回原登录页——表现为"点登录后页面刷新但登不上"。
                String method = request.getMethod();
                if (!"GET".equalsIgnoreCase(method)) {
                    return null;
                }
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return null;   // 只拦 http(s)，file/data/content 等放行
                }
                // Solarpanel 自己的面板保留 viewport meta
                String server = Prefs.getServer(MainActivity.this);
                if (!TextUtils.isEmpty(server)) {
                    try {
                        String serverHost = Uri.parse(server).getHost();
                        String pageHost = uri.getHost();
                        if (serverHost != null && serverHost.equalsIgnoreCase(pageHost)) {
                            return null;
                        }
                    } catch (Exception ignored) {}
                }
                try {
                    return fetchAndStripViewport(request);
                } catch (Exception e) {
                    return null;   // 拦截失败就让 WebView 自己加载
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mainFrameFailed = false;
                // 每次新页面加载时重置 WebView 原生缩放状态。
                // setInitialScale(0) 让 WebView 配合 setLoadWithOverviewMode 自动计算
                // "整页塞进屏幕"的缩放比例，对桌面端网页（如 fnOS）效果完美——
                // 左侧导航栏 + 中间内容 + 右侧面板的横向布局完整保留，只是整体缩小。
                // 设成 100 会让 980px 的桌面页面溢出手机屏幕只能看到左半边。
                // 用户捏合缩放后这个状态会"传染"到下一个页面，每次 loadUrl 必须重置。
                view.setInitialScale(0);
                // 新页面开始加载时先把顶栏放出来，进度条与关闭按钮才看得见
                applyTopBarState(false, true);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!mainFrameFailed) {
                    hideErrorPanel();
                }
                // viewport meta 已在 shouldInterceptRequest 网络层被提前删除，
                // 这里不需要再通过 JS 事后删除（时机太晚）
                injectPanelCssFix();
                injectScrollHook();
                updateCloseButton();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // SPA 路由切换（pushState）不会触发 onPageFinished，这里补一次
                // （viewport meta 在第一次 load 时就被删了，后续 SPA 切换不会重新插入）
                injectPanelCssFix();
                injectScrollHook();
                updateCloseButton();
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
            if (id == R.id.action_close) {
                goHome();
                return true;
            }
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
    // 顶栏自动收放 + 关闭按钮
    // ------------------------------------------------------------------

    /**
     * 顶栏（含进度条）覆盖在网页之上，网页容器整体被顶栏高度向下推一段，
     * 这样网页顶部不会被顶栏遮挡；顶栏收起时这段位移归零，
     * 可视区域立刻变大，且完全不需要让 WebView 重新布局（网页不会回流、不抖动）。
     */
    private void configureTopBar() {
        topBar.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                topBar.getViewTreeObserver().removeOnPreDrawListener(this);
                topBarHeight = topBar.getHeight();
                applyTopBarState(false, false);   // 首帧就位，避免闪一帧错位
                return true;
            }
        });
    }

    private void configureScrollHiding() {
        // 正常路径：网页里的滚动位置由注入的 JS 上报（见 SCROLL_HOOK_JS）。
        // 兜底路径：万一 JS 注入没生效（页面禁用脚本，或内容在 iframe 里滚动），
        // 就用手指的滑动方向判断——往上滑收起顶栏、往下滑放出来。
        // 两条路径不会打架：只要 JS 上报过一次，兜底就自动让位。
        webView.setOnTouchListener((v, event) -> {
            if (jsScrollHookAlive) {
                return false;
            }
            float threshold = FALLBACK_TOUCH_THRESHOLD_DP
                    * getResources().getDisplayMetrics().density;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchAnchorY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float moved = touchAnchorY - event.getY();
                    if (moved > threshold) {
                        applyTopBarState(true, true);
                        touchAnchorY = event.getY();
                    } else if (moved < -threshold) {
                        applyTopBarState(false, true);
                        touchAnchorY = event.getY();
                    }
                    break;
                default:
                    break;
            }
            return false;   // 不消费事件，网页照常滚动
        });
    }

    /**
     * 收起或展开顶栏。
     *
     * @param hide    true 收起，false 展开
     * @param animate 是否播放动画
     */
    private void applyTopBarState(boolean hide, boolean animate) {
        if (topBarHeight <= 0) {
            return;
        }
        float barTarget = hide ? -topBarHeight : 0f;
        float contentTarget = hide ? 0f : topBarHeight;

        if (!topBarSettled) {
            topBarSettled = true;
            topBarHidden = hide;
            topBar.setTranslationY(barTarget);
            webView.setTranslationY(contentTarget);
            return;
        }
        if (topBarHidden == hide) {
            return;
        }
        topBarHidden = hide;

        if (!animate) {
            topBar.setTranslationY(barTarget);
            webView.setTranslationY(contentTarget);
            return;
        }
        topBar.animate().cancel();
        webView.animate().cancel();
        topBar.animate()
                .translationY(barTarget)
                .setDuration(TOP_BAR_ANIM_MS)
                .start();
        webView.animate()
                .translationY(contentTarget)
                .setDuration(TOP_BAR_ANIM_MS)
                .start();
    }

    private void injectScrollHook() {
        webView.evaluateJavascript(SCROLL_HOOK_JS, null);
    }

    /**
     * shouldInterceptRequest 辅助方法：自己发起 HTTP 请求拿到 HTML，
     * 正则删除所有 <meta name="viewport" ...> 标签，返回改好的响应。
     *
     * 这样渲染引擎从第一帧开始就看不到 viewport meta，
     * 会用默认 ~980px 桌面宽度渲染，实现 Chrome 「桌面版网站」模式。
     */
    private WebResourceResponse fetchAndStripViewport(WebResourceRequest request) throws IOException {
        URL url = new URL(request.getUrl().toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            // 带上 Cookie（包括 Solarpanel 面板的登录态，很多反代会校验）
            String cookie = CookieManager.getInstance().getCookie(url.toString());
            if (!TextUtils.isEmpty(cookie)) {
                conn.setRequestProperty("Cookie", cookie);
            }
            // 复制其他请求头
            for (Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                String k = h.getKey();
                if (!"User-Agent".equalsIgnoreCase(k) && !"Cookie".equalsIgnoreCase(k)
                        && !"Host".equalsIgnoreCase(k) && !"Connection".equalsIgnoreCase(k)) {
                    conn.setRequestProperty(k, h.getValue());
                }
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                in = conn.getInputStream();   // 兜底
            }

            // 手动读响应体（readAllBytes 要 API 33，minSdk 24 不支持）
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            in.close();
            byte[] raw = baos.toByteArray();
            String html = new String(raw, StandardCharsets.UTF_8);

            // 正则删除所有 <meta name="viewport" ...> 标签（多行 / 大小写不敏感 / 自闭合都覆盖）
            Pattern vpPattern = Pattern.compile(
                    "<meta\\s+[^>]*name\\s*=\\s*[\"']viewport[\"'][^>]*/?>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = vpPattern.matcher(html);
            if (m.find()) {
                html = m.replaceAll("");
            } else {
                // 有些页面把 name 放后面：<meta content="..." name="viewport">
                vpPattern = Pattern.compile(
                        "<meta\\s+[^>]*[\"']viewport[\"'][^>]*name\\s*=\\s*[\"'][^\"']+[\"'][^>]*/?>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                m = vpPattern.matcher(html);
                if (m.find()) {
                    html = m.replaceAll("");
                }
            }

            // 拿 Content-Type 确定 MIME type
            String contentType = conn.getContentType();
            String mimeType = "text/html";
            String encoding = "utf-8";
            if (!TextUtils.isEmpty(contentType)) {
                int semi = contentType.indexOf(';');
                if (semi > 0) {
                    mimeType = contentType.substring(0, semi).trim();
                    int eq = contentType.indexOf("charset=", semi + 1);
                    if (eq >= 0) {
                        encoding = contentType.substring(eq + 8).trim();
                    }
                } else {
                    mimeType = contentType.trim();
                }
            }

            // 复制响应头（主要是 Content-Type）
            Map<String, String> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    respHeaders.put(e.getKey(), e.getValue().get(0));
                }
            }

            ByteArrayInputStream responseBody = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
            return new WebResourceResponse(mimeType, encoding, code, conn.getResponseMessage(), respHeaders, responseBody);
        } finally {
            conn.disconnect();
        }
    }

    /** 修正面板窄屏下分组标题被压成竖排的问题，详见 PANEL_CSS_FIX_JS。 */
    private void injectPanelCssFix() {
        webView.evaluateJavascript(PANEL_CSS_FIX_JS, null);
    }

    /** 只有在"当前不在面板首页"时，顶栏才显示关闭按钮。 */
    private void updateCloseButton() {
        MenuItem item = toolbar.getMenu().findItem(R.id.action_close);
        if (item == null) {
            return;
        }
        String home = Prefs.getServer(this);
        boolean atHome = !TextUtils.isEmpty(home) && Urls.isSamePage(webView.getUrl(), home);
        item.setVisible(!atHome);
    }

    /** 关闭当前服务页面，回到面板首页。 */
    private void goHome() {
        String home = Prefs.getServer(this);
        if (TextUtils.isEmpty(home)) {
            showSetupPanel("");
            return;
        }
        if (Urls.isSamePage(webView.getUrl(), home)) {
            return;
        }
        applyTopBarState(false, true);
        webView.loadUrl(home);
    }

    /** 暴露给网页的最小接口，用来上报滚动方向与异步清理完成事件。 */
    private final class ScrollBridge {

        @JavascriptInterface
        public void onScroll(int delta, int atTop) {
            // 该回调在 WebView 的 JavaBridge 线程上执行，必须切回主线程动 UI
            jsScrollHookAlive = true;
            boolean atTopOfPage = atTop != 0;
            if (delta < -SCROLL_EPSILON_PX || atTopOfPage) {
                // 往上滚，或已经回到顶部：展开顶栏
                runOnUiThread(() -> applyTopBarState(false, true));
            } else if (delta > SCROLL_EPSILON_PX) {
                // 往下滚：收起顶栏，把空间让给内容
                runOnUiThread(() -> applyTopBarState(true, true));
            }
        }

        @JavascriptInterface
        public void onSwCleanupDone() {
            // JS 里的 Promise.all(SW 注销 + Cache 清理) 都完成后才会走到这里。
            // 同样在 JavaBridge 线程上执行，切回主线程跑后续清理逻辑。
            runOnUiThread(() -> {
                swCleanupTimeoutHandler.removeCallbacksAndMessages(null);
                Runnable cont = swCleanupContinuation;
                swCleanupContinuation = null;
                if (cont != null) {
                    cont.run();
                }
            });
        }
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
        // 自托管场景下，面板返回的卡片链接可能写成 localhost / 127.0.0.1，
        // 手机 WebView 连自己的 localhost 当然连不上，需要把 host 重写成用户配置的服务器地址。
        Uri rewritten = Urls.rewriteLocalHost(uri, Prefs.getServer(this));
        if (rewritten != null) {
            // 让 WebView 加载重写后的地址，不再 override
            webView.loadUrl(rewritten.toString());
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
        // Android 13+ (API 33) 需要 POST_NOTIFICATIONS 权限才能显示下载完成通知；
        // 没权限也能下载，但完成后不会出通知，提前请求一下用户体验更好。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int state = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS);
            if (state != PackageManager.PERMISSION_GRANTED) {
                if (pendingDownload != null) {
                    // 已经有一个权限请求在走了，新的下载请求丢弃即可
                    return;
                }
                pendingDownload = () -> doEnqueueDownload(url, userAgentHeader, contentDisposition, mimeType);
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        doEnqueueDownload(url, userAgentHeader, contentDisposition, mimeType);
    }

    /** 真正执行 DownloadManager.enqueue；被 enqueueDownload 在权限通过后调用。 */
    private void doEnqueueDownload(String url,
                                   String userAgentHeader,
                                   String contentDisposition,
                                   String mimeType) {
        try {
            // 下载链接里的 localhost / 127.0.0.1 也要重写，和页面导航同理
            Uri downloadUri = Uri.parse(url);
            String serverUrl = Prefs.getServer(this);
            Uri rewritten = Urls.rewriteLocalHost(downloadUri, serverUrl);
            if (rewritten != null) {
                downloadUri = rewritten;
                url = downloadUri.toString();
            }

            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

            DownloadManager.Request request = new DownloadManager.Request(downloadUri);
            request.setMimeType(mimeType);
            request.setTitle(fileName);
            request.setDescription(url);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            // 备份导出等接口需要登录态，必须带上 Cookie。
            // DownloadManager 不会共享 WebView 的 CookieJar，所以手动注入；
            // 同时，面板在反代/跨域下载场景下，下载链接的域名可能和面板本身不同
            // （比如面板 IP:8080，下载重定向到另一个域名），此时 getCookie(url) 只会拿到
            // 下载域名的 Cookie，丢失面板的登录态，所以也要把面板域名的 Cookie 一并带上。
            CookieManager cm = CookieManager.getInstance();
            StringBuilder cookieHeader = new StringBuilder();
            String downloadCookie = cm.getCookie(url);
            if (!TextUtils.isEmpty(downloadCookie)) {
                cookieHeader.append(downloadCookie);
            }
            if (!TextUtils.isEmpty(serverUrl)) {
                String serverCookie = cm.getCookie(serverUrl);
                if (!TextUtils.isEmpty(serverCookie)
                        && !TextUtils.equals(serverCookie, downloadCookie)) {
                    if (cookieHeader.length() > 0) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader.append(serverCookie);
                }
            }
            if (cookieHeader.length() > 0) {
                request.addRequestHeader("Cookie", cookieHeader.toString());
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
        // 面板 v2.1.00 起自带 PWA 离线缓存（Service Worker + Cache Storage），
        // 不属于 Cookie / HTTP 缓存 / DOM 存储任何一种，需要单独清。
        // SW 注销和 Cache 删除都是 JS 异步 API，所以先注入脚本，
        // 等 Promise.all 全部 resolve 后再执行后面的 Cookie/Cache 清理。
        // 同时加 3 秒超时兜底：万一 JS 因为某种原因（被禁用、页面崩了、上下文丢失）
        // 没触发 onSwCleanupDone，也不会让用户卡在"清除"状态。
        swCleanupContinuation = () -> {
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
        };
        clearPanelServiceWorker();

        // 3 秒超时：到期后若 onSwCleanupDone 还没被回调，强制执行后续清理。
        // swCleanupTimeoutHandler 在 onDestroy 里会被 removeCallbacks，防止泄漏。
        swCleanupTimeoutHandler.postDelayed(swCleanupTimeoutRunnable, 3000L);
    }

    /** 注销面板注册的 Service Worker 并清空其离线缓存，详见 PANEL_SW_CLEANUP_JS。 */
    private void clearPanelServiceWorker() {
        webView.evaluateJavascript(PANEL_SW_CLEANUP_JS, null);
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
        // 移除 SW 清理的超时处理器，防止 Activity 被 MessageQueue 持引用延迟 GC
        swCleanupTimeoutHandler.removeCallbacksAndMessages(null);
        swCleanupContinuation = null;
        pendingDownload = null;
        if (webView != null) {
            webView.stopLoading();
            webView.removeAllViews();
            webView.removeJavascriptInterface("SolarpanelHost");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
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
