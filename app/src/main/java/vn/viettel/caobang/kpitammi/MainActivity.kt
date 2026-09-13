package vn.viettel.caobang.kpitammi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("kpi_tammi", MODE_PRIVATE) }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotifications()

        val url = findViewById<EditText>(R.id.edtUrl)
        val hour = findViewById<EditText>(R.id.edtHour)
        val minute = findViewById<EditText>(R.id.edtMinute)
        status = findViewById(R.id.txtStatus)

        url.setText(prefs.getString("url", ""))
        hour.setText(prefs.getInt("hour", 7).toString())
        minute.setText(prefs.getInt("minute", 30).toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val u = url.text.toString().trim()
            val h = hour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 7
            val m = minute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 30
            if (u.isBlank()) {
                Toast.makeText(this, "Nhập URL Apps Script", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("url", u).putInt("hour", h).putInt("minute", m).apply()
            Scheduler.scheduleDaily(this, h, m)
            status.text = "Đã bật tự động lúc %02d:%02d hằng ngày.".format(h, m)
        }

        findViewById<Button>(R.id.btnRun).setOnClickListener {
            val u = url.text.toString().trim()
            if (u.isBlank()) {
                Toast.makeText(this, "Nhập URL Apps Script", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("url", u).apply()

            val request = OneTimeWorkRequestBuilder<ReportWorker>().build()
            val wm = WorkManager.getInstance(this)
            wm.enqueue(request)
            status.text = "Đang kết nối máy chủ báo cáo..."

            wm.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
                if (info == null) return@observe

                val current = info.progress.getInt("current", 0)
                val total = info.progress.getInt("total", 0)
                val phase = info.progress.getString("phase").orEmpty()

                when (info.state) {
                    WorkInfo.State.ENQUEUED -> status.text = "Đang chờ thực hiện..."
                    WorkInfo.State.RUNNING -> {
                        status.text = when {
                            total > 0 && current > 0 -> "Đang tải dữ liệu: $current/$total khối..."
                            phase.isNotBlank() -> phase
                            else -> "Đang tải và giải nén báo cáo..."
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val count = info.outputData.getInt("count", 0)
                        status.text = "HOÀN TẤT: Đã giải nén $count file. Bấm GỬI TAMMI."
                        Toast.makeText(this, "Báo cáo đã sẵn sàng", Toast.LENGTH_LONG).show()
                    }
                    WorkInfo.State.FAILED -> {
                        val error = info.outputData.getString("error") ?: "Không xác định"
                        status.text = "LỖI: $error"
                    }
                    WorkInfo.State.CANCELLED -> status.text = "Đã hủy tác vụ."
                    WorkInfo.State.BLOCKED -> status.text = "Đang chờ điều kiện hệ thống..."
                }
            }
        }

        findViewById<Button>(R.id.btnShare).setOnClickListener { shareExtracted() }
        if (intent.getBooleanExtra("shareNow", false)) shareExtracted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("shareNow", false)) shareExtracted()
    }

    private fun shareExtracted() {
        val latest = ReportFiles.latestExtractedDir(this)
        val files = latest?.walkTopDown()?.filter { it.isFile && ReportFiles.allowed(it) }?.toList().orEmpty()
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

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
