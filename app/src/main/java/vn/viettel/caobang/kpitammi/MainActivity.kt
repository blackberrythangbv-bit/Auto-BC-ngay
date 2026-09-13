package vn.viettel.caobang.kpitammi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("kpi_tammi", MODE_PRIVATE) }

    private lateinit var txtStatus: TextView
    private lateinit var txtLastRun: TextView
    private lateinit var txtFileCount: TextView
    private lateinit var txtStatusValue: TextView
    private lateinit var txtAutoTime: TextView
    private lateinit var txtHistory: TextView
    private lateinit var txtFilesSummary: TextView
    private lateinit var txtFilesList: TextView
    private lateinit var txtSourceState: TextView
    private lateinit var edtHour: EditText
    private lateinit var edtMinute: EditText
    private lateinit var edtUrlNew: EditText
    private lateinit var sourceEditorContainer: LinearLayout

    private lateinit var homeScroll: View
    private lateinit var historyScroll: View
    private lateinit var filesScroll: View
    private lateinit var settingsScroll: View

    private lateinit var iconHome: ImageView
    private lateinit var iconHistory: ImageView
    private lateinit var iconFiles: ImageView
    private lateinit var iconSettings: ImageView
    private lateinit var labelHome: TextView
    private lateinit var labelHistory: TextView
    private lateinit var labelFiles: TextView
    private lateinit var labelSettings: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotifications()
        bindViews()
        setupTabs()
        setupActions()
        refreshAll()
        showTab(0)

        if (intent.getBooleanExtra("shareNow", false)) shareExtracted()
    }

    override fun onResume() {
        super.onResume()
        if (::txtStatusValue.isInitialized) refreshDashboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("shareNow", false)) shareExtracted()
    }

    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus)
        txtLastRun = findViewById(R.id.txtLastRun)
        txtFileCount = findViewById(R.id.txtFileCount)
        txtStatusValue = findViewById(R.id.txtStatusValue)
        txtAutoTime = findViewById(R.id.txtAutoTime)
        txtHistory = findViewById(R.id.txtHistory)
        txtFilesSummary = findViewById(R.id.txtFilesSummary)
        txtFilesList = findViewById(R.id.txtFilesList)
        txtSourceState = findViewById(R.id.txtSourceState)
        edtHour = findViewById(R.id.edtHour)
        edtMinute = findViewById(R.id.edtMinute)
        edtUrlNew = findViewById(R.id.edtUrlNew)
        sourceEditorContainer = findViewById(R.id.sourceEditorContainer)

        homeScroll = findViewById(R.id.homeScroll)
        historyScroll = findViewById(R.id.historyScroll)
        filesScroll = findViewById(R.id.filesScroll)
        settingsScroll = findViewById(R.id.settingsScroll)

        iconHome = findViewById(R.id.iconHome)
        iconHistory = findViewById(R.id.iconHistory)
        iconFiles = findViewById(R.id.iconFiles)
        iconSettings = findViewById(R.id.iconSettings)
        labelHome = findViewById(R.id.labelHome)
        labelHistory = findViewById(R.id.labelHistory)
        labelFiles = findViewById(R.id.labelFiles)
        labelSettings = findViewById(R.id.labelSettings)
    }

    private fun setupTabs() {
        findViewById<View>(R.id.tabHome).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.tabHistory).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.tabFiles).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { showTab(3) }
    }

    private fun setupActions() {
        findViewById<Button>(R.id.btnRun).setOnClickListener { runReport() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareExtracted() }
        findViewById<Button>(R.id.btnShareFiles).setOnClickListener { shareExtracted() }
        findViewById<Button>(R.id.btnTestConnection).setOnClickListener {
            showTab(0)
            runReport()
        }

        findViewById<Button>(R.id.btnEditSource).setOnClickListener {
            sourceEditorContainer.visibility =
                if (sourceEditorContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (sourceEditorContainer.visibility == View.VISIBLE) {
                edtUrlNew.setText("")
                edtUrlNew.requestFocus()
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
        }
    }

    private fun showTab(index: Int) {
        homeScroll.visibility = if (index == 0) View.VISIBLE else View.GONE
        historyScroll.visibility = if (index == 1) View.VISIBLE else View.GONE
        filesScroll.visibility = if (index == 2) View.VISIBLE else View.GONE
        settingsScroll.visibility = if (index == 3) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(this, R.color.app_red)
        val inactive = ContextCompat.getColor(this, R.color.nav_inactive)
        val icons = listOf(iconHome, iconHistory, iconFiles, iconSettings)
        val labels = listOf(labelHome, labelHistory, labelFiles, labelSettings)
        icons.forEachIndexed { i, image -> image.setColorFilter(if (i == index) active else inactive) }
        labels.forEachIndexed { i, text -> text.setTextColor(if (i == index) active else inactive) }

        when (index) {
            0 -> refreshDashboard()
            1 -> refreshHistory()
            2 -> refreshFiles()
            3 -> refreshSettings()
        }
    }

    private fun refreshAll() {
        refreshDashboard()
        refreshHistory()
        refreshFiles()
        refreshSettings()
    }

    private fun refreshDashboard() {
        val lastRun = prefs.getString("last_run_text", "").orEmpty()
        val lastStatus = prefs.getString("last_status", "").orEmpty()
        val actualCount = latestFiles().size
        val storedCount = prefs.getInt("last_count", 0)
        val count = if (actualCount > 0) actualCount else storedCount
        val hour = prefs.getInt("hour", 7)
        val minute = prefs.getInt("minute", 30)

        txtLastRun.text = lastRun.ifBlank { "Chưa chạy" }
        txtFileCount.text = "$count file"
        txtAutoTime.text = "%02d:%02d".format(hour, minute)
        txtStatusValue.text = lastStatus.ifBlank { "Sẵn sàng" }

        val statusColor = when (lastStatus) {
            "Thành công" -> R.color.success
            "Lỗi" -> R.color.danger
            else -> R.color.text_secondary
        }
        txtStatusValue.setTextColor(ContextCompat.getColor(this, statusColor))

        if (txtStatus.text.isNullOrBlank() || txtStatus.text.toString() == "Sẵn sàng.") {
            val message = prefs.getString("last_message", "").orEmpty()
            txtStatus.text = message.ifBlank { "Sẵn sàng." }
        }
    }

    private fun refreshHistory() {
        txtHistory.text = RunHistory.history(this)
    }

    private fun refreshFiles() {
        val files = latestFiles()
        txtFilesSummary.text = "${files.size} file sẵn sàng"
        txtFilesList.text = if (files.isEmpty()) {
            "Chưa có tệp báo cáo."
        } else {
            files.sortedBy { it.name.lowercase() }.joinToString("\n\n") {
                "📄 ${it.name}\n${humanSize(it.length())}"
            }
        }
    }

    private fun refreshSettings() {
        val hasSource = prefs.getString("url", "").orEmpty().isNotBlank()
        txtSourceState.text = if (hasSource) "Đã cấu hình • URL/token được ẩn" else "Chưa cấu hình"
        txtSourceState.setTextColor(
            ContextCompat.getColor(this, if (hasSource) R.color.success else R.color.danger)
        )
        edtHour.setText(prefs.getInt("hour", 7).toString())
        edtMinute.setText(prefs.getInt("minute", 30).toString())
        sourceEditorContainer.visibility = if (hasSource) View.GONE else View.VISIBLE
        edtUrlNew.setText("")
    }

    private fun saveSettings() {
        val existingUrl = prefs.getString("url", "").orEmpty().trim()
        val newUrl = edtUrlNew.text.toString().trim()
        val finalUrl = if (newUrl.isNotBlank()) newUrl else existingUrl
        val h = edtHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 7
        val m = edtMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 30

        if (finalUrl.isBlank()) {
            Toast.makeText(this, "Chưa cấu hình nguồn báo cáo", Toast.LENGTH_SHORT).show()
            sourceEditorContainer.visibility = View.VISIBLE
            return
        }

        prefs.edit()
            .putString("url", finalUrl)
            .putInt("hour", h)
            .putInt("minute", m)
            .apply()
        Scheduler.scheduleDaily(this, h, m)

        edtUrlNew.setText("")
        sourceEditorContainer.visibility = View.GONE
        txtStatus.text = "Đã lưu cấu hình. Tự động chạy lúc %02d:%02d hằng ngày.".format(h, m)
        Toast.makeText(this, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
        refreshSettings()
        refreshDashboard()
    }

    private fun runReport() {
        val sourceUrl = prefs.getString("url", "").orEmpty().trim()
        if (sourceUrl.isBlank()) {
            Toast.makeText(this, "Chưa cấu hình nguồn báo cáo", Toast.LENGTH_SHORT).show()
            showTab(3)
            return
        }

        val request = OneTimeWorkRequestBuilder<ReportWorker>().build()
        val wm = WorkManager.getInstance(this)
        wm.enqueue(request)
        txtStatus.text = "Đang kết nối máy chủ báo cáo..."
        txtStatusValue.text = "Đang chạy"
        txtStatusValue.setTextColor(ContextCompat.getColor(this, R.color.warning))

        wm.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info == null) return@observe

            val current = info.progress.getInt("current", 0)
            val total = info.progress.getInt("total", 0)
            val phase = info.progress.getString("phase").orEmpty()

            when (info.state) {
                WorkInfo.State.ENQUEUED -> txtStatus.text = "Đang chờ thực hiện..."
                WorkInfo.State.RUNNING -> {
                    txtStatus.text = when {
                        total > 0 && current > 0 -> "Đang tải dữ liệu: $current/$total khối..."
                        phase.isNotBlank() -> phase
                        else -> "Đang xử lý báo cáo..."
                    }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val count = info.outputData.getInt("count", 0)
                    val cached = info.outputData.getBoolean("cached", false)
                    txtStatus.text = if (cached) {
                        "HOÀN TẤT: Nguồn chưa thay đổi, dùng $count file đã có."
                    } else {
                        "HOÀN TẤT: Đã giải nén $count file. Bấm GỬI TAMMI."
                    }
                    Toast.makeText(this, "Báo cáo đã sẵn sàng", Toast.LENGTH_LONG).show()
                    refreshAll()
                }
                WorkInfo.State.FAILED -> {
                    val error = info.outputData.getString("error") ?: "Không xác định"
                    txtStatus.text = "LỖI: $error"
                    refreshAll()
                }
                WorkInfo.State.CANCELLED -> txtStatus.text = "Đã hủy tác vụ."
                WorkInfo.State.BLOCKED -> txtStatus.text = "Đang chờ điều kiện hệ thống..."
            }
        }
    }

    private fun latestFiles(): List<File> {
        val latest = ReportFiles.latestExtractedDir(this)
        return latest?.walkTopDown()
            ?.filter { it.isFile && ReportFiles.allowed(it) }
            ?.toList()
            .orEmpty()
    }

    private fun shareExtracted() {
        val files = latestFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "Chưa có file báo cáo đã giải nén", Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList(files.map {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        })

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, "Báo cáo KPI ngày ${ReportFiles.today()}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Gửi báo cáo qua Tammi"))
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
