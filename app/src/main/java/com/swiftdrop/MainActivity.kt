package com.swiftdrop

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
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

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) stageFolderTree(treeUri)
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

    override fun onResume() {
        super.onResume()
        State.foregroundActivity = this
    }

    override fun onPause() {
        if (State.foregroundActivity === this) State.foregroundActivity = null
        super.onPause()
    }

    override fun onDestroy() {
        if (State.foregroundActivity === this) State.foregroundActivity = null
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

            // SPAKE2 Phase 1: use the QR token as the password (never sent raw).
            val spakeState = Spake2.clientStart(token)
            val payload = JSONObject().apply {
                put("pake_msg", PairStore.bytesToHex(spakeState.msgA))
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
            // Verify SPAKE2 server response.
            val srvMsg = PairStore.hexToBytes(result.optString("pake_msg"))
            val srvConfirm = PairStore.hexToBytes(result.optString("pake_confirm"))
            val spakeKey = try {
                spakeState.finish(srvMsg, srvConfirm)
            } catch (e: Exception) {
                runOnUiThread { jsCallback(false, "QR pairing failed: wrong token") }
                return
            }
            val wrapped = PairStore.hexToBytes(result.optString("encrypted_key"))
            val keyBytes = Spake2.aesGcmUnwrap(spakeKey, wrapped)
            if (keyBytes.size != 32) {
                runOnUiThread { jsCallback(false, "invalid key from peer") }
                return
            }
            // SPAKE2 Phase 2: send client confirmation MAC.
            val clientConfirm = Spake2.confirmMac(spakeKey, "client")
            val confirmPayload = JSONObject().apply {
                put("pake_confirm", PairStore.bytesToHex(clientConfirm))
                put("id", State.deviceId)
            }.toString()
            val conn2 = (java.net.URL("http://$host/api/pair/pake-confirm").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            conn2.outputStream.use { it.write(confirmPayload.toByteArray()) }
            if (conn2.responseCode != 200) {
                val err = conn2.errorStream?.bufferedReader()?.use { it.readText() } ?: "confirmation rejected"
                conn2.disconnect()
                runOnUiThread { jsCallback(false, err) }
                return
            }
            conn2.disconnect()
            PairStore.storeKey(peerId, keyBytes)
            Log.i("SwiftDrop", "QR SPAKE2 paired with $peerId")
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

    /** Stage a picked folder tree as a single item with total size and file count. */
    private fun stageFolderTree(treeUri: Uri) {
        // Get the display name of the picked folder.
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        var folderName = rootDocId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Folder" }
        // Walk tree to compute total size and file count.
        var totalSize = 0L; var fileCount = 0
        fun walk(parentDocId: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            contentResolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ), null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val docId = c.getString(idIdx)
                    val mime = c.getString(mimeIdx)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(docId)
                    } else {
                        fileCount++
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) totalSize += c.getLong(sizeIdx)
                    }
                }
            }
        }
        walk(rootDocId)
        if (fileCount == 0) return
        val arr = JSONArray().put(JSONObject().apply {
            put("name", folderName)
            put("size", totalSize)
            put("path", treeUri.toString())
            put("is_folder", true)
            put("file_count", fileCount)
        })
        runOnUiThread {
            web.evaluateJavascript("window.swiftdropOnDrop && window.swiftdropOnDrop($arr)", null)
        }
    }

    /** Dark-themed bottom sheet picker matching the app's design language. */
    private fun showPickerSheet() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val dp = resources.displayMetrics.density

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1D23.toInt())
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }

        // Title
        root.addView(android.widget.TextView(this).apply {
            text = "Choose what to send"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        fun svgIcon(drawFile: Boolean): android.view.View {
            val sz = (40 * dp).toInt()
            return object : android.view.View(this) {
                init { layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (14 * dp).toInt()
                }}
                override fun onDraw(canvas: android.graphics.Canvas) {
                    super.onDraw(canvas)
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    // Circle background
                    p.color = 0xFF2E3440.toInt()
                    canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, p)
                    // Icon stroke
                    p.color = 0xFF7EB6FF.toInt()
                    p.style = android.graphics.Paint.Style.STROKE
                    p.strokeWidth = 1.8f * dp
                    p.strokeCap = android.graphics.Paint.Cap.ROUND
                    p.strokeJoin = android.graphics.Paint.Join.ROUND
                    val cx = sz / 2f; val cy = sz / 2f; val u = sz / 5f
                    if (drawFile) {
                        // Document icon: rectangle with folded corner
                        val path = android.graphics.Path()
                        path.moveTo(cx - u * 0.8f, cy - u * 1.2f)
                        path.lineTo(cx + u * 0.3f, cy - u * 1.2f)
                        path.lineTo(cx + u * 0.8f, cy - u * 0.7f)
                        path.lineTo(cx + u * 0.8f, cy + u * 1.2f)
                        path.lineTo(cx - u * 0.8f, cy + u * 1.2f)
                        path.close()
                        canvas.drawPath(path, p)
                        // Fold
                        canvas.drawLine(cx + u * 0.3f, cy - u * 1.2f, cx + u * 0.3f, cy - u * 0.7f, p)
                        canvas.drawLine(cx + u * 0.3f, cy - u * 0.7f, cx + u * 0.8f, cy - u * 0.7f, p)
                    } else {
                        // Folder icon
                        val path = android.graphics.Path()
                        path.moveTo(cx - u, cy - u * 0.7f)
                        path.lineTo(cx - u * 0.2f, cy - u * 0.7f)
                        path.lineTo(cx + u * 0.1f, cy - u * 0.2f)
                        path.lineTo(cx + u, cy - u * 0.2f)
                        path.lineTo(cx + u, cy + u * 0.8f)
                        path.lineTo(cx - u, cy + u * 0.8f)
                        path.close()
                        canvas.drawPath(path, p)
                    }
                }
            }
        }

        fun optionRow(drawFile: Boolean, label: String, sub: String, onClick: () -> Unit) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF23262E.toInt())
                    cornerRadius = 12 * dp
                }
                val lp = android.widget.LinearLayout.LayoutParams(-1, -2)
                lp.bottomMargin = (10 * dp).toInt()
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); onClick() }
            }
            row.addView(svgIcon(drawFile))
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            col.addView(android.widget.TextView(this).apply {
                text = label; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            col.addView(android.widget.TextView(this).apply {
                text = sub; setTextColor(0xFF8A8F98.toInt()); textSize = 12f
            })
            row.addView(col)
            root.addView(row)
        }

        optionRow(true, "Files", "Pick one or more files") { pickFiles.launch(arrayOf("*/*")) }
        optionRow(false, "Folder", "Pick an entire folder") { pickFolder.launch(null) }

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(-1, -2) // MATCH_PARENT width, WRAP_CONTENT height
            setGravity(android.view.Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.also {
                it.windowAnimations = android.R.style.Animation_InputMethod
            }
        }
        dialog.show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickFiles() {
            runOnUiThread { pickFiles.launch(arrayOf("*/*")) }
        }

        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread { pickFolder.launch(null) }
        }

        @JavascriptInterface
        fun showPicker() {
            runOnUiThread { showPickerSheet() }
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
