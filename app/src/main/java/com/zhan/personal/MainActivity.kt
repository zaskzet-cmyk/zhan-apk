package com.zhan.personal

import android.app.ActivityManager
import android.os.BatteryManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile
    private var selectedTreeUri: Uri? = null
    private val storageExecutor = Executors.newSingleThreadExecutor()
    private val scanGeneration = AtomicInteger()
    private val storageFileUris = ConcurrentHashMap<String, Uri>()

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        pendingFileCallback?.onReceiveValue(uris.toTypedArray())
        pendingFileCallback = null
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            deliverFolderResult(JSONObject().put("cancelled", true))
            return@registerForActivityResult
        }
        selectedTreeUri = uri
        scanSelectedFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(9, 9, 11)
        window.navigationBarColor = Color.rgb(9, 9, 11)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 11))
            isVerticalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                        ?: super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    return url.scheme != "https" ||
                        url.host != ASSET_HOST ||
                        !url.path.orEmpty().startsWith("/assets/")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    this@MainActivity.filePicker.launch("*/*")
                    return true
                }
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "ZhanAndroid")
        setContentView(webView)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl("https://$ASSET_HOST/assets/index.html?platform=android")
        }
    }

    private fun scanSelectedFolder(treeUri: Uri) {
        val generation = scanGeneration.incrementAndGet()
        storageExecutor.execute {
            val result = try {
                scanFolder(treeUri)
            } catch (error: SecurityException) {
                Log.e(TAG, "Folder pilihan tidak dapat dibaca.", error)
                JSONObject().put("error", "Akses ke folder pilihan tidak tersedia.")
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Folder pilihan memiliki URI yang tidak valid.", error)
                JSONObject().put("error", "Folder pilihan tidak valid.")
            } catch (error: UnsupportedOperationException) {
                Log.e(TAG, "Penyedia dokumen tidak mendukung pemindaian folder.", error)
                JSONObject().put("error", "Folder ini tidak mendukung peninjauan file.")
            }
            runOnUiThread {
                if (generation == scanGeneration.get() && !isFinishing) {
                    deliverFolderResult(result)
                }
            }
        }
    }

    private fun scanFolder(treeUri: Uri): JSONObject {
        storageFileUris.clear()
        val files = JSONArray()
        val errors = JSONArray()
        val queue = ArrayDeque<FolderEntry>()
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        val folderName = queryDisplayName(rootUri) ?: "Folder dipilih"
        queue.add(FolderEntry(rootDocumentId, "", 0))
        var visited = 0

        while (queue.isNotEmpty() && files.length() < MAX_STORAGE_FILES && visited < MAX_STORAGE_ENTRIES) {
            val entry = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, entry.documentId)
            val cursor = try {
                contentResolver.query(
                    childrenUri,
                    STORAGE_PROJECTION,
                    null,
                    null,
                    null
                )
            } catch (error: SecurityException) {
                Log.e(TAG, "Tidak dapat membaca item di ${entry.relativePath}.", error)
                errors.put(entry.relativePath)
                null
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "URI isi folder tidak valid: ${entry.relativePath}.", error)
                errors.put(entry.relativePath)
                null
            } catch (error: UnsupportedOperationException) {
                Log.e(TAG, "Penyedia dokumen tidak mendukung pembacaan folder ${entry.relativePath}.", error)
                errors.put(entry.relativePath)
                null
            }
            if (cursor == null) {
                Log.w(TAG, "Penyedia dokumen tidak memberi daftar untuk ${entry.relativePath}.")
                errors.put(entry.relativePath)
                continue
            }

            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (
                    it.moveToNext() &&
                    files.length() < MAX_STORAGE_FILES &&
                    visited < MAX_STORAGE_ENTRIES
                ) {
                    visited += 1
                    val documentId = it.getString(idColumn) ?: continue
                    val name = it.getString(nameColumn) ?: continue
                    val path = if (entry.relativePath.isEmpty()) name else "${entry.relativePath}/$name"
                    if (it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (entry.depth < MAX_STORAGE_DEPTH) {
                            queue.addLast(FolderEntry(documentId, path, entry.depth + 1))
                        } else {
                            errors.put(path)
                        }
                        continue
                    }

                    val fileId = UUID.randomUUID().toString()
                    storageFileUris[fileId] = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    files.put(
                        JSONObject()
                            .put("androidId", fileId)
                            .put("name", name)
                            .put("size", if (it.isNull(sizeColumn)) 0 else it.getLong(sizeColumn))
                            .put("lastModified", if (it.isNull(modifiedColumn)) 0 else it.getLong(modifiedColumn))
                            .put("relativePath", path)
                    )
                }
            }
        }
        if (queue.isNotEmpty() || visited >= MAX_STORAGE_ENTRIES || files.length() >= MAX_STORAGE_FILES) {
            errors.put("Pemindaian dibatasi untuk menjaga kinerja perangkat.")
        }
        return JSONObject()
            .put("folderName", folderName)
            .put("files", files)
            .put("errors", errors)
    }

    private fun queryDisplayName(documentUri: Uri): String? {
        val cursor = contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            return it.getString(nameColumn)
        }
    }

    private fun requestDeleteStorageFiles(idsJson: String) {
        if (idsJson.length > MAX_DELETE_REQUEST_CHARS) {
            deliverDeleteResult(JSONObject().put("error", "Daftar file pilihan terlalu besar."))
            return
        }
        val ids = try {
            JSONArray(idsJson)
        } catch (error: JSONException) {
            deliverDeleteResult(JSONObject().put("error", "Daftar file pilihan tidak valid."))
            return
        }
        if (ids.length() > MAX_STORAGE_FILES) {
            deliverDeleteResult(JSONObject().put("error", "Jumlah file pilihan melebihi batas."))
            return
        }

        storageExecutor.execute {
            val result = deleteStorageFiles(ids)
            runOnUiThread {
                deliverDeleteResult(result)
            }
        }
    }

    private fun deleteStorageFiles(ids: JSONArray): JSONObject {
        var deleted = 0
        var failed = 0
        for (index in 0 until ids.length()) {
            val fileId = ids.optString(index)
            val documentUri = storageFileUris.remove(fileId)
            if (documentUri == null) {
                failed += 1
                continue
            }
            try {
                if (DocumentsContract.deleteDocument(contentResolver, documentUri)) deleted += 1
                else failed += 1
            } catch (error: SecurityException) {
                Log.e(TAG, "Izin menghapus file pilihan tidak tersedia.", error)
                failed += 1
            } catch (error: FileNotFoundException) {
                Log.e(TAG, "File pilihan tidak ditemukan saat dihapus.", error)
                failed += 1
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "URI file pilihan tidak valid saat dihapus.", error)
                failed += 1
            } catch (error: UnsupportedOperationException) {
                Log.e(TAG, "Penyedia dokumen tidak mendukung penghapusan file pilihan.", error)
                failed += 1
            }
        }
        return JSONObject().put("deleted", deleted).put("failed", failed)
    }

    private fun deliverFolderResult(result: JSONObject) {
        if (!::webView.isInitialized || isFinishing) return
        val serialized = JSONObject.quote(result.toString())
        webView.post {
            webView.evaluateJavascript(
                "window.onZhanAndroidFolderResult && window.onZhanAndroidFolderResult($serialized);",
                null
            )
        }
    }

    private fun deliverDeleteResult(result: JSONObject) {
        if (!::webView.isInitialized || isFinishing) return
        val serialized = JSONObject.quote(result.toString())
        webView.post {
            webView.evaluateJavascript(
                "window.onZhanAndroidDeleteResult && window.onZhanAndroidDeleteResult($serialized);",
                null
            )
        }
    }

    private fun buildDeviceInfo(): JSONObject {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val storage = StatFs(Environment.getExternalStorageDirectory().path)
        val batteryManager = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val display = resources.displayMetrics
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdkLevel", Build.VERSION.SDK_INT)
            .put("appVersion", packageInfo.versionName ?: "—")
            .put("batteryPercent", batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("ramTotalBytes", memory.totalMem)
            .put("ramAvailableBytes", memory.availMem)
            .put("storageTotalBytes", storage.totalBytes)
            .put("storageAvailableBytes", storage.availableBytes)
            .put("displayWidth", display.widthPixels)
            .put("displayHeight", display.heightPixels)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun getDeviceInfo(): String = buildDeviceInfo().toString()

        @JavascriptInterface
        fun chooseStorageFolder() {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) folderPicker.launch(null)
            }
        }

        @JavascriptInterface
        fun deleteStorageFiles(idsJson: String) = this@MainActivity.requestDeleteStorageFiles(idsJson)

        @JavascriptInterface
        fun refreshStorageFolder() {
            val uri = selectedTreeUri
            if (uri == null) {
                deliverFolderResult(JSONObject().put("error", "Pilih folder terlebih dahulu."))
                return
            }
            scanSelectedFolder(uri)
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        storageExecutor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    private data class FolderEntry(val documentId: String, val relativePath: String, val depth: Int)

    companion object {
        private const val TAG = "ZhanSystem"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val MAX_STORAGE_FILES = 1500
        private const val MAX_STORAGE_ENTRIES = 5000
        private const val MAX_STORAGE_DEPTH = 10
        private const val MAX_DELETE_REQUEST_CHARS = MAX_STORAGE_FILES * 64
        private val STORAGE_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
