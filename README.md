# Solarpanel

安卓客户端导航面板，来自 https://github.com/Ozero-top/Solar-Panel

这是一个用 WebView 封装的轻量外壳，把你部署在飞牛 / NAS 上的 Solarpanel 变成手机上可以一键打开的独立应用。

## 特点

- **全内嵌**：页面里的所有链接、`target="_blank"`、`window.open()` 都在应用内的 WebView 中打开，不会跳到系统浏览器。
- **首次填址**：第一次启动时填写服务器地址，之后自动记住；地址随时可在右上角菜单里修改。
- **局域网友好**：允许明文 HTTP，兼容自签名 HTTPS 证书，适合内网自建服务。
- **后台可管**：支持后台管理面板所需的文件上传（图标 / 壁纸）与文件下载（备份导出），并自动带上登录 Cookie。
- **返回键符合直觉**：优先回退网页历史，回到首页后双击退出。
- **顶栏自动收放**：往下滑页面时顶栏自动收起，把空间让给内容；往上滑或回到页面顶部时自动出现。
- **一键回首页**：进入任意服务页面后，顶栏右上角会出现 `✕`，点一下立刻回到面板首页。
- **适配面板 PWA**：面板 v2.1.00 起自带 Service Worker 离线缓存，「清除缓存与登录状态」会同步注销 SW 并清空其缓存；离线兜底交给面板自带的 `offline.html`。
- **自适应图标**：适配 Android 8.0+ 的圆形 / 方形 / 圆角方形各种启动器遮罩。

## 安装

1. 打开本仓库的 [Releases](../../releases) 页面，下载最新的 `Solarpanel-x.x.x.apk`。
2. 在手机上允许「安装未知来源应用」，然后直接安装。
3. 首次打开时填写你部署在飞牛上的 Solarpanel 地址，例如 `192.168.1.10:8080`。
   - 可以省略 `http://`，程序会自动补全。
   - 如果是反代域名，填 `https://panel.example.com`。

> 本应用不上架任何应用商店，仅供自用侧载安装。

## 首次配置说明

地址栏支持三种写法，都会被正确识别：

| 你的服务地址 | 填写内容 |
|---|---|
| 局域网 IP + 端口 | `192.168.1.10:8080` |
| 明文 HTTP | `http://192.168.1.10:8080` |
| HTTPS 反代 | `https://panel.example.com` |

地址会被规范化处理（去掉末尾多余的 `/`、补全协议头）。

## 在线构建

仓库内置了 GitHub Actions 工作流，**不需要在本地安装 Android SDK**：

- 推送到 `main` 分支，或在 Actions 页面手动触发 `Build APK`。
- 工作流会安装 JDK 17 与 Android SDK，构建 release APK。
- 构建完成后自动创建 GitHub Release 并把 APK 挂上去，同时保留一份 artifact。

构建过程会自动完成三件事：

1. 若仓库里没有 `keystore/solarpanel.jks`，自动生成一个（RSA 2048，有效期 10000 天）。
2. 用该密钥库签名 APK。
3. 打印 `aapt2 dump badging` 与 `apksigner verify` 结果，便于核对包名、版本与签名。

### 关于签名密钥库

仓库里的 `keystore/solarpanel.jks` 口令固定为 `solarpanel`，这是**刻意为之**：只有密钥库固定，后续重新构建的 APK 才能覆盖安装旧版本，不会因为签名不同而必须卸载重装。

**但这意味着任何拿到这个仓库的人都能签出与本应用同名同签名的 APK。** 因此：

- 它只适合个人自用侧载，**绝对不要**用这个密钥库去做任何对外分发的版本。
- 想换一套独占签名：删掉 `keystore/solarpanel.jks` 并重新触发构建，工作流会生成新的密钥库；代价是这次换签必须卸载旧版再装新版。

## 本地构建（可选）

如果你确实想在本地编译：

```bash
# 需要 JDK 17 与 Android SDK（platform 34 + build-tools 34.0.0）
gradle assembleRelease
```

本工程没有提交 Gradle Wrapper，因此请使用系统安装的 Gradle 8.9 或更高版本；也可以执行一次 `gradle wrapper` 自行生成 wrapper。

## 工程结构

```
Solarpanel/
├── .github/workflows/build.yml            在线构建 + 自动发 Release
├── keystore/solarpanel.jks                固定签名密钥库（见上文说明）
├── docs/                                  图标展示图与启动器效果预览
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/solarpanel/app/
        │   ├── MainActivity.java          WebView 外壳主逻辑
        │   ├── Prefs.java                 服务器地址存储
        │   └── Urls.java                  地址规范化与校验
        └── res/
            ├── layout/activity_main.xml   主界面 / 首次配置 / 错误页
            ├── menu/main_menu.xml         右上角菜单
            ├── drawable/ic_close.xml      顶栏「关闭并返回首页」图标
            ├── values/                    颜色、字符串、主题
            ├── values-night/colors.xml    深色模式配色
            ├── xml/network_security_config.xml
            └── mipmap-*/                  各密度图标
```

## 关键实现说明

### 为什么链接不会跳到浏览器

`configureWebView()` 中关闭了多窗口支持：

```java
settings.setSupportMultipleWindows(false);          // target="_blank" 留在本 WebView
settings.setJavaScriptCanOpenWindowsAutomatically(true);
```

再配合 `WebViewClient.shouldOverrideUrlLoading()` 对 `http/https` 返回 `false`，页面导航全部留在应用内。只有 `tel:`、`mailto:`、`sms:`、`geo:`、`weixin://` 这类**非网页**协议才会交给系统对应的应用处理。

### 顶栏自动收放与一键回首页

顶栏是**覆盖在网页之上的图层**：网页容器整体被顶栏高度向下推一段，所以网页顶部不会被顶栏挡住；顶栏收起时这段位移归零，可视区域立刻变大，而且**不需要让 WebView 重新布局**——网页不会回流，滚动过程中不会抖。

滚动方向由注入到页面的 JS 上报（`MainActivity.SCROLL_HOOK_JS`）：

- 监听挂在 `document` 的**捕获阶段**，因此页面内部任何滚动容器（含懒加载列表）都能命中；
- 位移小于 3px 不上报，避免惯性滚动末尾顶栏来回闪动；
- 往下滑 → 收起顶栏；往上滑、或回到页面顶部 → 顶栏自动出现；每次开始加载新页面时也会先出现。

万一 JS 注入没生效（页面禁用脚本，或内容在跨域 iframe 里滚动），会自动退化为**按手指滑动方向**判断，功能不会整个失效；只要 JS 上报过一次，兜底逻辑就自动让位。

顶栏右侧的 `✕`（`action_close`）只在**当前不在面板首页**时出现。地址比较忽略查询串与锚点，并把末尾斜杠、默认端口统一归一化（`Urls.isSamePage()`），所以 `http://ip:8080`、`http://ip:8080/`、`http://ip:8080/#xx` 会被认定是同一页。点一下即回到面板首页。

### 安全取舍（请知悉）

为了适配自托管环境，工程做了两处**降低默认安全性**的配置：

1. `network_security_config.xml` 允许明文 HTTP，并信任用户安装的 CA 证书。
2. `MainActivity.ALLOW_SELF_SIGNED_CERTIFICATE = true`，自签名证书校验失败时继续加载。

这在内网访问自己的服务时是方便且合理的；但请**不要**用这个应用去访问不受信任的公网站点。若你的服务使用受信任的正规证书，把该常量改为 `false` 即可恢复严格校验。

### 文件上传与下载

- 上传：`WebChromeClient.onShowFileChooser()` 唤起系统文件选择器，后台管理面板换图标 / 壁纸时可用。
- 下载：`DownloadListener` 交给系统 `DownloadManager` 处理，并手动注入 `Cookie` 头，因此「备份导出」这类需要登录态的接口也能正常下载。

## 自定义

| 想改什么 | 改哪里 |
|---|---|
| 应用名称 | `app/src/main/res/values/strings.xml` 的 `app_name` |
| 版本号 | `app/build.gradle` 的 `versionCode` / `versionName` |
| 主题色 | `app/src/main/res/values/colors.xml` |
| 图标 | 替换 `app/src/main/res/mipmap-*/` 下的 PNG，或改 `res/values/colors.xml` 中的 `ic_launcher_background` |
| 包名 | `app/build.gradle` 的 `applicationId` 与 `namespace`，以及 `AndroidManifest.xml` |

## 常见问题

**打开是白屏？**
先确认手机和服务器在同一网络、服务已启动。右上角菜单 →「修改服务器地址」可以改地址；菜单 →「用系统浏览器打开」可以快速验证地址本身是否可达。

**提示证书错误？**
程序默认已允许自签名证书。如果仍然失败，检查服务端是否强制跳转到了另一个端口或域名。

**登录后一刷新就退出登录？**
菜单 →「清除缓存与登录状态」后重新登录一次。反向代理的 Cookie 域配置不当时会出现这种情况。

**能上架应用商店吗？**
不能，也不必要。这是给自托管面板配套的自用外壳。

## 更新记录

### v1.2.09

- **彻底修复桌面端页面堆砌问题**：v1.2.07 改了 UA、v1.2.08 加了 JS 事后删除 viewport meta，
  都不行——因为等到 JS 能执行时，渲染引擎已经按 viewport meta 在 360px 完成了 layout + paint。
  这次改为**网络层拦截**：用 `shouldInterceptRequest` 捕获外部页面（非 Solarpanel 自己）
  的主文档 HTML，自己发起 HTTP 请求（带桌面 UA + Cookie），正则删掉
  `<meta name="viewport" ...>` 标签后再返回给 WebView。
  渲染引擎从第一帧开始就看不到 viewport meta，直接用默认 ~980px 桌面宽度渲染，
  再配合 `setLoadWithOverviewMode(true)` + `setInitialScale(0)` 自动等比缩小到手机屏幕。
  效果等同于 Chrome 浏览器的「桌面版网站」模式。
- 删除 v1.2.08 基于 JS 的 `injectDesktopViewport()` 方法（时机太晚，无效）。

### v1.2.08

- **根因修复（补全 Chrome 桌面模式的第二步）**：v1.2.07 只做了「改 UA 为桌面 Chrome」，
  但 fnOS 等桌面端 HTML 里有 `<meta name="viewport" content="width=device-width">`，
  WebView 还是按 360px 手机宽度渲染，CSS media query 照样触发移动端堆叠布局。
  Chrome 的「桌面版网站」实际做了**两件事**：①改 UA ②忽略 viewport meta。
  这次补上第 2 步：新增 `injectDesktopViewport()` 在 `onPageCommitVisible`（最早
  可操作 DOM 的时机）和 `onPageFinished` 里删除 viewport meta，强制 WebView
  用默认 ~980px 桌面宽度渲染，再配合 `setLoadWithOverviewMode` +
  `setInitialScale(0)` 自动等比缩小到手机屏幕。
- **Solarpanel 自身页面跳过 viewport 删除**：通过 URL host 匹配判断是不是
  用户配置的 Solarpanel 服务器地址，是的话不动它的 viewport（它有响应式 CSS，
  手机宽度渲染效果更好）。只有外部链接（fnOS 等）才强制用桌面宽度。

### v1.2.07

- **根因修复**：飞牛 fnOS / 桌面端 NAS 后台出现"横向堆砌"（导航栏压成窄条、
  监控面板截断）的根因是 WebView UA 字符串里带了 "Mobile" 标记，服务器据此
  返回了为移动端设计的布局。改为**桌面端 Chrome UA**：
  `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ... Chrome/126 ...`
  不含 "Mobile"，效果等同于 Chrome 浏览器的「桌面版网站」模式。
  Solarpanel 自己有 viewport meta + 响应式 CSS，收到桌面 UA 也能正确渲染。
- 移除 v1.2.06 中添加的悬浮方向切换按钮：横屏无法解决服务器返回移动端布局
  的问题，且 v1.2.07 正确的桌面布局 + `setInitialScale(0)` 整页等比缩小后，
  横屏收益有限。
- 保持 v1.2.06 的**下拉刷新**（SwipeRefreshLayout）功能不变。

### v1.2.06

- 新增：下拉刷新当前页面。`FrameLayout` 容器换成了 `SwipeRefreshLayout`，
  原生支持"在页面顶部继续下滑触发刷新"手势，和网页内部滚动完全不冲突。
- 新增：右下角悬浮按钮可一键切换竖屏 / 横屏。Manifest 已声明
  `configChanges`，旋转不会销毁 WebView，页面状态完整保留。
- 新增依赖 `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`。

### v1.2.05

- 修复：桌面端网页（如飞牛 fnOS）被 app "视觉堆叠"——左侧导航栏被压成窄条、
  右上角监控面板只露一半。根因是 v1.2.04 错误地给桌面端网页注入了
  `width=device-width` viewport，fnOS 的 CSS 布局是按桌面宽度（980px+）写死的，
  改成 device-width 后三个横向元素挤在 360px 里必然互相挤压。
  同时 `setInitialScale(100)` 锁死 100% 缩放，也让 980px 的桌面页面只能看到左半边。

  回退并重做：
  1) 彻底移除 `injectViewportOverride()` 方法及其所有调用；
  2) `setInitialScale(100)` 改为 `setInitialScale(0)`，让 WebView 配合
     `setLoadWithOverviewMode(true)` 自动算 "整页塞进屏幕" 的缩放比例；
  3) 现在桌面端页面（fnOS）会完整呈现左侧导航 + 中间内容 + 右侧面板，
     整体等比缩小约 36%，用户可双指捏合放大看细节，跳页缩放自动重置。

### v1.2.04

- 修复：桌面端网页（如飞牛 fnOS）不自动适应手机屏幕。根因是这类页面没有
  `<meta name="viewport">` 声明，WebView 默认用 980px 宽 viewport 渲染后
  再靠 overview mode 等比缩小到手机宽度，导致页面看起来特别小、
  横向表格溢出、双指缩放状态还会跨页面"传染"。

  新方案：
  1) `onPageStarted` 调 `webView.setInitialScale(100)`，用 WebView 原生 API
     在每次新页面加载时强制重置缩放状态（之前试过用 `visualViewport.setScale`
     注入 JS，但那是 Chrome 专属 API，在 Android WebView 里完全不生效）；
  2) `injectViewportOverride()` 在页面无 viewport meta 时注入
     `width=device-width,initial-scale=1.0`，让页面天然按手机宽度渲染；
  3) 如果页面已有 viewport 且声明了 width（含固定桌面宽度如 1024），
     自动覆盖成 device-width；Solarpanel 这种自己带移动端 viewport 的页面
     不受影响。

### v1.2.03

- 修复：WebView 级别的捏合缩放状态不会随页面导航重置。用户在一个页面把页面缩小后，
  跳到下一个页面会继承同样的缩小比例，越跳越小，也没有任何重置入口。
  现在每次 `onPageFinished` 和 SPA 路由切换（`doUpdateVisitedHistory`）后，
  延迟 100ms 把 `visualViewport.scale` 强制设回 1.0，
  用户手动缩放仍然可用，但不再跨页面"传染"。

### v1.2.02

- 修复：双指捏合缩放时顶栏疯狂闪烁。根因是 WebView 里缩放也会触发 scroll 事件，
  `scrollTop` 抖动变化导致 JS hook 向上层上报大量方向相反的 onScroll。
  现在通过 touch 触点数量 + `visualViewport.scale` 识别捏合手势，缩放期间屏蔽上报，
  缩放结束后还有 300ms 冷却，避免收尾回弹误触发。
- 修复：Android 系统"字体大小"设置（小/大/超大）会被 WebView 继承，导致面板卡片文字
  溢出或换行错乱。新增 `settings.setTextZoom(100)` 锁定为默认缩放。
- 修复：自托管面板在反代、容器端口映射等场景下，返回的卡片链接可能写成
  `http://localhost:8080/xxx` 或 `127.0.0.1`，手机 WebView 会尝试连手机自身的
  localhost 导致 404。新增 `Urls.rewriteLocalHost()` 在链接调度前把回环 host
  自动重写成用户配置的服务器地址。
- 修复：跨域下载（面板在 A 域名、下载链接重定向到 B 域名）时丢失登录 Cookie。
  原来只取下载目标域名的 Cookie，现在同时取面板域名的 Cookie 一并带上。

### v1.2.01

- 修复：「清除缓存与登录状态」时 Service Worker 注销 / Cache Storage 清空的 JS Promise 异步执行时序问题，
  原来 Cookie 清理和页面重载会抢在 SW 清理完成之前执行；现在用 Promise.all + JavaScriptInterface 回调
  确保先清完 SW 再做后续清理，并加 3 秒超时兜底防止 JS 异常时用户卡死。
- 修复：Android 13+ (API 33) 下载文件时缺少 POST_NOTIFICATIONS 权限导致下载完成无通知，
  现在在下载前通过 ActivityResultLauncher 运行时请求权限。
- 修复：深色模式下顶栏品牌色 `#3E7FA8` (亮蓝) 在 `#12161A` 深色背景上过于刺眼，
  为全部品牌色补充 values-night 深色版本。
- 修复：onDestroy 中 WebView 清理不彻底——遗漏 `removeAllViews`、`removeJavascriptInterface`，
  且多余创建了一个临时 `WebViewClient`。同时新增 Handler removeCallbacks 防止 SW 清理超时处理器
  在 Activity 销毁后残留 MessageQueue 中。
- 改进：Release 签名密码从硬编码改为优先读取环境变量 `SOLARPANEL_KEYSTORE_PASSWORD`，
  未配置时回退为仓库内固定密码，同时同步更新 GitHub Actions workflow 支持 Secret 注入。

### v1.2.0

- 适配上游面板 v2.1.00（2026-09-15 发布）：面板新增 PWA（Service Worker + Cache Storage 离线缓存）。
  「清除缓存与登录状态」现在会同步注销面板注册的 Service Worker 并清空其离线缓存，
  避免清除之后离线缓存仍然残留；断网时的兜底页由面板自带的 `offline.html` 承担。
- 面板其余新功能——热点新闻双视图、浏览器书签导入、TOTP 两步验证、审计日志、
  自定义 RSS、每日壁纸（Bing）、快速添加卡片等——均为面板服务端能力，
  在应用内 WebView 直接可用，无需客户端改动。

### v1.1.0

- 新增：顶栏随滚动自动收放——往下滑收起，往上滑或回到顶部自动出现。
- 新增：进入服务页面后顶栏出现 `✕`，一键回到面板首页。
- 顶栏改为覆盖式图层，收起时网页可视区域更大，且不会触发网页重排。

### v1.0.0

- 首个版本：WebView 外壳、全内嵌、填写服务器地址、上传 / 下载支持、在线构建。

## 许可

随便用，出问题不负责
