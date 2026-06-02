package com.swiftdrop

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val uiUrl = "http://127.0.0.1:${State.PORT}/"
    private var loadRetries = 0

    // Files shared into the app before the page is ready, flushed on load.
    private val pendingShared = mutableListOf<Uri>()
    private var pageReady = false

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) stageUris(uris)
        }

    private val askNotify =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner()
        }

    private val scanQR =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents ?: return@registerForActivityResult
            Thread { handleQRResult(contents) }.start()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        State.init(this)
        SwiftDropService.start(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF0F1115.toInt())
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    flushShared()
                }
                override fun onReceivedError(
                    view: WebView?, request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // Server may still be starting — retry the initial load.
                    if (request?.isForMainFrame == true && loadRetries < 25) {
                        loadRetries++
                        view?.postDelayed({ view.loadUrl(uiUrl) }, 250)
                    }
                }
            }
        }
        setContentView(web)
        web.loadUrl(uiUrl)

        handleShareIntent(intent)
    }

    override fun onDestroy() {
        SwiftDropService.stop(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // ---- share sheet -----------------------------------------------------

    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return

        if (pageReady) stageUris(uris) else pendingShared.addAll(uris)
    }

    private fun flushShared() {
        if (pendingShared.isNotEmpty()) {
            stageUris(pendingShared.toList())
            pendingShared.clear()
        }
    }

    // ---- staging into the web UI ----------------------------------------

    private fun stageUris(uris: List<Uri>) {
        val arr = JSONArray()
        for (u in uris) {
            var name = "file"; var size = -1L
            runCatching {
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (ni >= 0) c.getString(ni)?.let { name = it }
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            }
            arr.put(JSONObject().apply {
                put("name", name); put("size", if (size < 0) 0 else size); put("path", u.toString())
            })
        }
        runOnUiThread {
            web.evaluateJavascript("window.swiftdropOnDrop && window.swiftdropOnDrop($arr)", null)
        }
    }

    private fun launchScanner() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the QR code shown on the other device")
            setBeepEnabled(false)
            setOrientationLocked(true)
            setCameraId(0)
            setCaptureActivity(ScannerActivity::class.java)
        }
        scanQR.launch(opts)
    }

    private fun handleQRResult(contents: String) {
        try {
            val obj = JSONObject(contents)
            val host = obj.getString("host")
            val peerId = obj.getString("id")
            val token = obj.getString("token")

            // POST to the remote device's /api/pair/qr-claim
            val payload = JSONObject().apply {
                put("token", token)
                put("id", State.deviceId)
                put("name", State.deviceName)
            }.toString()

            val conn = (java.net.URL("http://$host/api/pair/qr-claim").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "pairing rejected"
                conn.disconnect()
                runOnUiThread { jsCallback(false, err) }
                return
            }

            val result = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val keyHex = result.optString("key")
            val keyBytes = ByteArray(keyHex.length / 2) { i ->
                ((Character.digit(keyHex[2 * i], 16) shl 4) + Character.digit(keyHex[2 * i + 1], 16)).toByte()
            }
            if (keyBytes.size != 32) {
                runOnUiThread { jsCallback(false, "invalid key from peer") }
                return
            }
            PairStore.storeKey(peerId, keyBytes)
            Log.i("SwiftDrop", "QR paired with $peerId")
            runOnUiThread { jsCallback(true, null) }
        } catch (e: Exception) {
            Log.e("SwiftDrop", "QR pair failed", e)
            runOnUiThread { jsCallback(false, e.message ?: "QR pairing failed") }
        }
    }

    private fun jsCallback(ok: Boolean, err: String?) {
        val js = if (ok) "window.onQRPairResult && window.onQRPairResult(true)"
        else "window.onQRPairResult && window.onQRPairResult(false, ${JSONObject.quote(err ?: "error")})"
        web.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickFiles() {
            runOnUiThread { pickFiles.launch(arrayOf("*/*")) }
        }

        @JavascriptInterface
        fun setName(name: String) {
            State.setName(name)
        }

        @JavascriptInterface
        fun openFolder() {
            runOnUiThread {
                try {
                    startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun scanQRCode() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchScanner()
                } else {
                    askCamera.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }
}

// Version-safe Parcelable extras.
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(key) as? T

private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java)
    else @Suppress("DEPRECATION") getParcelableArrayListExtra(key)
