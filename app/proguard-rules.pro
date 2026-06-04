# Keep the JavaScript bridge methods callable from the WebView.
-keepclassmembers class com.swiftdrop.** {
    @android.webkit.JavascriptInterface <methods>;
}
# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
# ZXing QR code generation
-keep class com.google.zxing.** { *; }
# Google Tink — suppress missing compile-only annotation classes
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
