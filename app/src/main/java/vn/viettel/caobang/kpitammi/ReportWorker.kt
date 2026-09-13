package vn.viettel.caobang.kpitammi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class ReportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        val prefs = applicationContext.getSharedPreferences("kpi_tammi", Context.MODE_PRIVATE)
        val sourceUrl = prefs.getString("url", "")?.trim().orEmpty()
        if (sourceUrl.isBlank()) {
            val msg = "Chưa cấu hình nguồn báo cáo"
            RunHistory.add(applicationContext, "Lỗi", 0, msg)
            return Result.failure(workDataOf("error" to msg))
        }

        return try {
            setProgress(workDataOf("phase" to "Đang kiểm tra nguồn báo cáo..."))
            val dir = ReportFiles.dayDir(applicationContext)
            val zip = File(dir, "KPI_ngay_${ReportFiles.today()}.zip")
            val cached = downloadFlexible(sourceUrl, zip, prefs)

            val out = ReportFiles.extractedDir(applicationContext)
            if (!cached) {
                setProgress(workDataOf("phase" to "Đã tải ZIP, đang giải nén..."))
                out.deleteRecursively()
                out.mkdirs()
                unzip(zip, out)
            } else {
                setProgress(workDataOf("phase" to "Nguồn chưa thay đổi, dùng dữ liệu đã có..."))
            }

            val files = out.walkTopDown().filter { it.isFile && ReportFiles.allowed(it) }.toList()
            val count = files.size
            cleanupOld(3)

            if (count > 0) {
                setProgress(workDataOf("phase" to "Đang lưu báo cáo vào thư mục Download..."))
                val export = exportToDownloads(files)
                val elapsedMs = System.currentTimeMillis() - startedAt
                val downloadPath = "Download/KPI_Tammi/${ReportFiles.today()}"
                val message = when {
                    export.count == count && cached ->
                        "Nguồn chưa thay đổi; dùng lại $count file và đã lưu vào $downloadPath."
                    export.count == count ->
                        "Đã tải, giải nén $count file và lưu vào $downloadPath trong ${elapsedMs / 1000.0}s."
                    export.count > 0 ->
                        "Đã chuẩn bị $count file; lưu được ${export.count}/$count file vào $downloadPath."
                    else ->
                        "Đã chuẩn bị $count file. Không thể tạo bản sao công khai trong Download trên thiết bị này."
                }

                prefs.edit()
                    .putString("last_export_path", downloadPath)
                    .putInt("last_export_count", export.count)
                    .apply()

                RunHistory.add(applicationContext, "Thành công", count, message)
                notifyReady(count, export.count, downloadPath)
                Result.success(
                    workDataOf(
                        "count" to count,
                        "cached" to cached,
                        "elapsedMs" to elapsedMs,
                        "exportCount" to export.count,
                        "exportPath" to downloadPath
                    )
                )
            } else {
                val msg = "ZIP không có file báo cáo hợp lệ"
                RunHistory.add(applicationContext, "Lỗi", 0, msg)
                notifyError(msg)
                Result.failure(workDataOf("error" to msg))
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Không xác định"
            RunHistory.add(applicationContext, "Lỗi", 0, msg)
            notifyError(msg)
            Result.failure(workDataOf("error" to msg))
        }
    }

    private suspend fun downloadFlexible(
        src: String,
        target: File,
        prefs: SharedPreferences
    ): Boolean {
        val infoUrl = addParam(src, "action", "info")
        val infoText = httpGetText(infoUrl)
        val info = try { JSONObject(infoText) } catch (_: Exception) { null }

        if (info != null && info.optBoolean("ok", false) &&
            info.optString("mode") == "chunked-base64") {
            val total = info.optInt("totalChunks", 0)
            val expectedSize = info.optLong("size", -1L)
            val modified = info.optString("modifiedTime", "")
            if (total <= 0) throw IOException("Apps Script không trả số chunk hợp lệ")

            val existingCount = ReportFiles.extractedDir(applicationContext)
                .walkTopDown()
                .count { it.isFile && ReportFiles.allowed(it) }
            val lastModified = prefs.getString("last_source_modified", "").orEmpty()
            if (modified.isNotBlank() && modified == lastModified && existingCount > 0) {
                return true
            }

            val executor = Executors.newFixedThreadPool(minOf(3, total))
            try {
                val futures = (0 until total).map { index ->
                    executor.submit(Callable { fetchChunk(src, index) })
                }

                FileOutputStream(target).use { fos ->
                    for (i in 0 until total) {
                        val chunk = try {
                            futures[i].get()
                        } catch (e: Exception) {
                            throw IOException(
                                e.cause?.message ?: e.message ?: "Lỗi tải khối ${i + 1}/$total"
                            )
                        }
                        fos.write(chunk)
                        setProgress(
                            workDataOf(
                                "phase" to "Đang tải dữ liệu...",
                                "current" to (i + 1),
                                "total" to total
                            )
                        )
                    }
                }
            } finally {
                executor.shutdownNow()
            }

            if (expectedSize > 0 && target.length() != expectedSize) {
                throw IOException("Dung lượng ZIP không khớp: ${target.length()}/$expectedSize byte")
            }
            if (modified.isNotBlank()) {
                prefs.edit().putString("last_source_modified", modified).apply()
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
        return false
    }

    private fun fetchChunk(src: String, index: Int): ByteArray {
        val chunkUrl = addParam(addParam(src, "action", "chunk"), "index", index.toString())
        val obj = try {
            JSONObject(httpGetText(chunkUrl))
        } catch (_: Exception) {
            throw IOException("Khối ${index + 1} trả về dữ liệu không hợp lệ")
        }
        if (!obj.optBoolean("ok", false)) {
            throw IOException(obj.optString("error", "Lỗi tải khối ${index + 1}"))
        }
        val b64 = obj.optString("dataBase64", "")
        if (b64.isBlank()) throw IOException("Khối ${index + 1} rỗng")
        return Base64.decode(b64, Base64.DEFAULT)
    }

    private fun httpGetText(url: String): String =
        httpGetBytes(url).toString(Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 45_000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("Connection", "keep-alive")
            conn.setRequestProperty("User-Agent", "KPI-Tammi/1.3.1 Android")
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
                    FileOutputStream(out).use { fos -> zis.copyTo(fos, 64 * 1024) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private data class ExportResult(val count: Int)

    private fun exportToDownloads(files: List<File>): ExportResult {
        var exported = 0
        val date = ReportFiles.today()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = applicationContext.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/KPI_Tammi/$date/"

            files.forEach { file ->
                try {
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                    resolver.delete(collection, selection, arrayOf(file.name, relativePath))

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(file))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values) ?: return@forEach
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                    } ?: run {
                        resolver.delete(uri, null, null)
                        return@forEach
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    exported++
                } catch (_: Exception) {
                    // Bản nội bộ của app vẫn được giữ nguyên nếu thiết bị chặn ghi Download.
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(root, "KPI_Tammi/$date").apply { mkdirs() }
                files.forEach { file ->
                    try {
                        file.copyTo(File(folder, file.name), overwrite = true)
                        exported++
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ExportResult(exported)
    }

    private fun mimeTypeFor(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "csv" -> "text/csv"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    private fun cleanupOld(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        ReportFiles.baseDir(applicationContext).listFiles()?.forEach {
            if (it.lastModified() < cutoff) it.deleteRecursively()
        }
    }

    private fun notifyReady(count: Int, exportCount: Int, downloadPath: String) {
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

        val detail = if (exportCount == count) {
            "Đã lưu $count file tại $downloadPath"
        } else {
            "Đã chuẩn bị $count file; lưu Download $exportCount/$count file"
        }

        nm.notify(
            730,
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Báo cáo KPI đã sẵn sàng")
                .setContentText(detail)
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
