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

MIT，与上游 Solarpanel 项目保持一致。
