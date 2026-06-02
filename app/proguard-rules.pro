# Keep the JavaScript bridge methods callable from the WebView.
-keepclassmembers class com.swiftdrop.** {
    @android.webkit.JavascriptInterface <methods>;
}
# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
# ZXing QR code generation
-keep class com.google.zxing.** { *; }
