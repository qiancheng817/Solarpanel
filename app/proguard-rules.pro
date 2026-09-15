# Solarpanel 未开启混淆（minifyEnabled false）。
# 保留此文件以兼容 proguardFiles 配置；若后续开启混淆，
# 请确保 WebView 的 JavaScript 接口与反射调用不被裁剪。

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
