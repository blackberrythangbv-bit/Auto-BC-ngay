package vn.viettel.caobang.kpitammi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ReportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("kpi_tammi", Context.MODE_PRIVATE)
        val sourceUrl = prefs.getString("url", "")?.trim().orEmpty()
        if (sourceUrl.isBlank()) return Result.failure()

        return try {
            val dir = ReportFiles.dayDir(applicationContext)
            val zip = File(dir, "KPI_ngay_${ReportFiles.today()}.zip")
            download(sourceUrl, zip)

            val out = ReportFiles.extractedDir(applicationContext)
            out.deleteRecursively()
            out.mkdirs()
            unzip(zip, out)

            val count = out.walkTopDown().count { it.isFile && ReportFiles.allowed(it) }
            cleanupOld(3)
            if (count > 0) {
                notifyReady(count)
                Result.success()
            } else {
                notifyError("ZIP không có file báo cáo hợp lệ")
                Result.failure()
            }
        } catch (e: Exception) {
            notifyError(e.message ?: "Không xác định")
            Result.retry()
        }
    }

    private fun download(src: String, target: File) {
        val conn = URL(src).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 KPI-Tammi")
            conn.connect()
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            target.outputStream().use { out -> conn.inputStream.use { input -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }

        val signature = ByteArray(2)
        val read = target.inputStream().use { it.read(signature) }
        if (read != 2 || signature[0].toInt() != 0x50 || signature[1].toInt() != 0x4B) {
            throw IOException("Nguồn tải về không phải ZIP hợp lệ")
        }
    }

    private fun unzip(zipFile: File, dest: File) {
        val canonicalDest = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                val canonicalOut = out.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest)) {
                    throw SecurityException("ZIP chứa đường dẫn không an toàn")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun cleanupOld(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        ReportFiles.baseDir(applicationContext).listFiles()?.forEach {
            if (it.lastModified() < cutoff) it.deleteRecursively()
        }
    }

    private fun notifyReady(count: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "kpi_report"
        nm.createNotificationChannel(
            NotificationChannel(channel, "Báo cáo KPI", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val shareIntent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("shareNow", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            730,
            shareIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        nm.notify(
            730,
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Báo cáo KPI đã sẵn sàng")
                .setContentText("Đã giải nén $count file. Chạm để Gửi Tammi.")
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_share, "Gửi Tammi", pi)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notifyError(msg: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "kpi_report"
        nm.createNotificationChannel(
            NotificationChannel(channel, "Báo cáo KPI", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.notify(
            731,
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Lỗi tải báo cáo KPI")
                .setContentText(msg.take(120))
                .build()
        )
    }
}
