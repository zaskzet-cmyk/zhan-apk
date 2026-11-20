package com.zhan.personal

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val background = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var deviceInfo: TextView
    private lateinit var folderStatus: TextView
    private lateinit var fileList: LinearLayout
    private var selectedTree: Uri? = null
    private val files = mutableListOf<StorageFile>()
    private val selectedFiles = linkedSetOf<Uri>()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some document providers grant access only for the current session.
        }
        selectedTree = uri
        getPreferences(MODE_PRIVATE).edit().putString(PREF_TREE_URI, uri.toString()).apply()
        scanSelectedFolder()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        buildNativeScreen()
        selectedTree = getPreferences(MODE_PRIVATE).getString(PREF_TREE_URI, null)?.let(Uri::parse)
        refreshDeviceInfo()
        if (selectedTree != null) scanSelectedFolder()
    }

    private fun buildNativeScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        scroll.addView(content)
        setContentView(scroll)

        text("ZHAN", 13, ACCENT, true).also { content.addView(it) }
        text("Panel perangkat", 29, WHITE, true).also { content.addView(it, margins(bottom = 5)) }
        text("Aplikasi Android native • Alarm, kalender, dan penyimpanan", 14, MUTED)
            .also { content.addView(it, margins(bottom = 22)) }

        section("ALARM")
        text("Kelola alarm menggunakan aplikasi Jam yang tersedia di perangkat.", 14, MUTED)
            .also { content.addView(it, margins(bottom = 10)) }
        content.addView(actionButton("Buka daftar alarm") { openAlarms() }, margins(bottom = 9))
        content.addView(actionButton("Setel alarm…") { chooseAlarmTime() }, margins(bottom = 20))

        section("KALENDER")
        text("Buka kalender atau buat acara baru. Aplikasi kalender akan meminta konfirmasi sebelum menyimpan.", 14, MUTED)
            .also { content.addView(it, margins(bottom = 10)) }
        content.addView(actionButton("Buka kalender") { openCalendar() }, margins(bottom = 9))
        content.addView(actionButton("Tambah acara…") { createCalendarEvent() }, margins(bottom = 20))

        section("INFO PERANGKAT")
        deviceInfo = text("Memuat informasi…", 14, WHITE)
        deviceInfo.setPadding(dp(14), dp(14), dp(14), dp(14))
        deviceInfo.setBackgroundColor(CARD)
        content.addView(deviceInfo, margins(bottom = 9))
        content.addView(actionButton("Perbarui info") { refreshDeviceInfo() }, margins(bottom = 20))

        section("PENYIMPANAN")
        text("Pilih folder untuk meninjau file. Akses hanya diberikan ke folder yang kamu pilih.", 14, MUTED)
            .also { content.addView(it, margins(bottom = 10)) }
        content.addView(actionButton("Pilih folder") { folderPicker.launch(null) }, margins(bottom = 9))
        folderStatus = text("Belum ada folder dipilih.", 13, MUTED)
        content.addView(folderStatus, margins(bottom = 8))
        content.addView(actionButton("Pindai ulang") { scanSelectedFolder() }, margins(bottom = 9))
        content.addView(actionButton("Hapus file terpilih") { confirmDeleteSelected() }, margins(bottom = 12))
        fileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(fileList)

        text("Alarm dan kalender dibuka melalui aplikasi Android terkait; Zhan tidak meminta izin membaca data pribadi kalender.", 12, MUTED)
            .also { content.addView(it, margins(top = 20)) }
    }

    private fun openAlarms() {
        launchIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Tidak ditemukan aplikasi Jam.")
    }

    private fun chooseAlarmTime() {
        val now = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Alarm dari Zhan")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                launchIntent(intent, "Perangkat tidak menyediakan aplikasi untuk menyetel alarm.")
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun openCalendar() {
        val calendarUri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()
        launchIntent(Intent(Intent.ACTION_VIEW, calendarUri), "Tidak ditemukan aplikasi kalender.")
    }

    private fun createCalendarEvent() {
        val start = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val end = (start.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) }
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, "Acara baru")
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.timeInMillis)
        launchIntent(intent, "Tidak ditemukan aplikasi kalender yang bisa membuat acara.")
    }

    private fun launchIntent(intent: Intent, error: String) {
        try {
            if (intent.resolveActivity(packageManager) == null) {
                toast(error)
            } else {
                startActivity(intent)
            }
        } catch (_: Exception) {
            toast(error)
        }
    }

    private fun refreshDeviceInfo() {
        val battery = (getSystemService(BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it >= 0 }
            ?.let { "$it%" } ?: "Tidak tersedia"
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val text = "Model: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Baterai: $battery\n" +
            "Penyimpanan internal: ${formatBytes(total - free)} tersedia dari ${formatBytes(total)}\n" +
            "Layar: ${resources.displayMetrics.widthPixels} × ${resources.displayMetrics.heightPixels}"
        deviceInfo.text = text
    }

    private fun scanSelectedFolder() {
        val tree = selectedTree
        if (tree == null) {
            toast("Pilih folder terlebih dahulu.")
            return
        }
        folderStatus.text = "Memindai folder…"
        fileList.removeAllViews()
        selectedFiles.clear()
        background.execute {
            val result = try {
                scanTree(tree)
            } catch (error: Exception) {
                ScanResult("Folder tidak dapat dibaca: ${error.localizedMessage ?: error.javaClass.simpleName}", emptyList())
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                files.clear()
                files.addAll(result.files)
                folderStatus.text = "${result.folderName} • ${files.size} file ditemukan${if (files.size >= MAX_FILES) " (batas pemindaian tercapai)" else ""}"
                renderFiles()
                if (files.isEmpty() && !result.folderName.startsWith("Folder tidak dapat")) {
                    fileList.addView(text("Folder ini belum berisi file yang ditemukan.", 13, MUTED), margins(top = 6))
                }
            }
        }
    }

    private fun scanTree(tree: Uri): ScanResult {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val name = queryName(rootUri) ?: "Folder dipilih"
        val queue = ArrayDeque<Entry>()
        val found = mutableListOf<StorageFile>()
        queue.add(Entry(rootId, "", 0))
        var visited = 0
        while (queue.isNotEmpty() && found.size < MAX_FILES && visited < MAX_ENTRIES) {
            val entry = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, entry.id)
            val cursor = contentResolver.query(children, PROJECTION, null, null, null) ?: continue
            cursor.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idCol < 0 || nameCol < 0 || mimeCol < 0) return@use
                while (c.moveToNext() && found.size < MAX_FILES && visited < MAX_ENTRIES) {
                    visited++
                    val id = c.getString(idCol) ?: continue
                    val displayName = c.getString(nameCol) ?: continue
                    val relative = if (entry.path.isEmpty()) displayName else "${entry.path}/$displayName"
                    if (c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (entry.depth < MAX_DEPTH) queue.add(Entry(id, relative, entry.depth + 1))
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found.add(StorageFile(uri, displayName, relative,
                            if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L,
                            if (modifiedCol >= 0 && !c.isNull(modifiedCol)) c.getLong(modifiedCol) else 0L))
                    }
                }
            }
        }
        return ScanResult(name, found)
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            val column = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (column >= 0 && c.moveToFirst()) return c.getString(column)
        }
        return null
    }

    private fun renderFiles() {
        fileList.removeAllViews()
        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(CARD)
            }
            val check = CheckBox(this).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedFiles.add(file.uri) else selectedFiles.remove(file.uri)
                }
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(file.name, 14, WHITE, true))
            val date = if (file.modified > 0) android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", file.modified).toString() else "Waktu tidak tersedia"
            labels.addView(text("${formatBytes(file.size)} • $date\n${file.path}", 11, MUTED))
            row.addView(check)
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fileList.addView(row, margins(bottom = 5))
        }
    }

    private fun confirmDeleteSelected() {
        if (selectedFiles.isEmpty()) {
            toast("Pilih file yang ingin dihapus.")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Hapus file?")
            .setMessage("${selectedFiles.size} file akan dihapus melalui penyedia dokumen Android.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> deleteSelected() }
            .show()
    }

    private fun deleteSelected() {
        val targets = selectedFiles.toList()
        folderStatus.text = "Menghapus file…"
        background.execute {
            var deleted = 0
            var failed = 0
            targets.forEach { uri ->
                try {
                    if (DocumentsContract.deleteDocument(contentResolver, uri)) deleted++ else failed++
                } catch (_: Exception) { failed++ }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast("Terhapus: $deleted • gagal: $failed")
                    scanSelectedFolder()
                }
            }
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(BUTTON))
        setOnClickListener { onClick() }
        minHeight = dp(50)
    }

    private fun section(value: String) {
        content.addView(text(value, 12, ACCENT, true), margins(bottom = 8))
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do { value /= 1024.0; unit++ } while (value >= 1024 && unit < units.lastIndex)
        return "%.1f %s".format(value, units[unit])
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private data class Entry(val id: String, val path: String, val depth: Int)
    private data class StorageFile(val uri: Uri, val name: String, val path: String, val size: Long, val modified: Long)
    private data class ScanResult(val folderName: String, val files: List<StorageFile>)

    companion object {
        private const val PREF_TREE_URI = "selected_tree_uri"
        private const val MAX_FILES = 300
        private const val MAX_ENTRIES = 1500
        private const val MAX_DEPTH = 8
        private const val BG = Color.rgb(9, 9, 11)
        private const val CARD = Color.rgb(25, 25, 30)
        private const val BUTTON = Color.rgb(43, 43, 51)
        private const val WHITE = Color.WHITE
        private const val MUTED = 0xFFAAAAB3.toInt()
        private const val ACCENT = 0xFFF04452.toInt()
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
