package vn.viettel.caobang.kpitammi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

class ReportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("kpi_tammi", Context.MODE_PRIVATE)
        val sourceUrl = prefs.getString("url", "")?.trim().orEmpty()
        if (sourceUrl.isBlank()) {
            return Result.failure(workDataOf("error" to "Chưa cấu hình URL Apps Script"))
        }

        return try {
            setProgress(workDataOf("phase" to "Đang kiểm tra nguồn báo cáo..."))
            val dir = ReportFiles.dayDir(applicationContext)
            val zip = File(dir, "KPI_ngay_${ReportFiles.today()}.zip")
            downloadFlexible(sourceUrl, zip)

            setProgress(workDataOf("phase" to "Đã tải ZIP, đang giải nén..."))
            val out = ReportFiles.extractedDir(applicationContext)
            out.deleteRecursively()
            out.mkdirs()
            unzip(zip, out)

            val count = out.walkTopDown().count { it.isFile && ReportFiles.allowed(it) }
            cleanupOld(3)
            if (count > 0) {
                notifyReady(count)
                Result.success(workDataOf("count" to count))
            } else {
                val msg = "ZIP không có file báo cáo hợp lệ"
                notifyError(msg)
                Result.failure(workDataOf("error" to msg))
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Không xác định"
            notifyError(msg)
            Result.failure(workDataOf("error" to msg))
        }
    }

    private suspend fun downloadFlexible(src: String, target: File) {
        val infoUrl = addParam(src, "action", "info")
        val infoText = httpGetText(infoUrl)
        val info = try { JSONObject(infoText) } catch (_: Exception) { null }

        if (info != null && info.optBoolean("ok", false) &&
            info.optString("mode") == "chunked-base64") {
            val total = info.optInt("totalChunks", 0)
            if (total <= 0) throw IOException("Apps Script không trả số chunk hợp lệ")

            FileOutputStream(target).use { fos ->
                for (i in 0 until total) {
                    setProgress(workDataOf(
                        "phase" to "Đang tải dữ liệu...",
                        "current" to (i + 1),
                        "total" to total
                    ))
                    val chunkUrl = addParam(addParam(src, "action", "chunk"), "index", i.toString())
                    val chunkText = httpGetText(chunkUrl)
                    val obj = JSONObject(chunkText)
                    if (!obj.optBoolean("ok", false)) {
                        throw IOException(obj.optString("error", "Lỗi tải chunk $i"))
                    }
                    val b64 = obj.optString("dataBase64", "")
                    if (b64.isBlank()) throw IOException("Chunk $i rỗng")
                    fos.write(Base64.decode(b64, Base64.DEFAULT))
                }
            }
        } else {
            setProgress(workDataOf("phase" to "Đang tải ZIP trực tiếp..."))
            val bytes = httpGetBytes(src)
            if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                target.writeBytes(bytes)
            } else {
                val text = bytes.toString(Charsets.UTF_8).trim()
                val json = try { JSONObject(text) } catch (_: Exception) {
                    throw IOException("Nguồn trả về không phải ZIP/JSON hợp lệ")
                }
                if (!json.optBoolean("ok", false)) {
                    throw IOException(json.optString("error", "Nguồn trả về lỗi"))
                }
                val b64 = json.optString("dataBase64", "")
                if (b64.isBlank()) throw IOException("Không có dataBase64")
                target.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            }
        }

        val sig = ByteArray(2)
        val n = target.inputStream().use { it.read(sig) }
        if (n != 2 || sig[0].toInt() != 0x50 || sig[1].toInt() != 0x4B) {
            throw IOException("File tải về không phải ZIP hợp lệ")
        }
    }

    private fun httpGetText(url: String): String =
        httpGetBytes(url).toString(Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 KPI-Tammi")
            conn.setRequestProperty("Accept", "application/json, application/zip, text/plain, */*")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun addParam(url: String, key: String, value: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + URLEncoder.encode(key, "UTF-8") + "=" +
            URLEncoder.encode(value, "UTF-8")
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
