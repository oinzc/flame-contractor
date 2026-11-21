# Keep @JavascriptInterface methods intact for WebView bridges
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
