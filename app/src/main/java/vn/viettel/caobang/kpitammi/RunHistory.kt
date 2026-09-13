package vn.viettel.caobang.kpitammi

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RunHistory {
    private const val PREFS = "kpi_tammi"
    private const val KEY_HISTORY = "run_history"
    private const val MAX_ITEMS = 12
    private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    fun add(context: Context, status: String, count: Int, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = LocalDateTime.now().format(fmt)
        val icon = if (status == "Thành công") "✓" else "!"
        val safeMessage = message.replace("\n", " ").trim()
        val entry = "$icon  $time • $status • $count file\n$safeMessage"
        val old = prefs.getString(KEY_HISTORY, "").orEmpty()
            .split("\n---\n")
            .filter { it.isNotBlank() }
        val merged = (listOf(entry) + old).take(MAX_ITEMS).joinToString("\n---\n")

        prefs.edit()
            .putString(KEY_HISTORY, merged)
            .putString("last_run_text", time)
            .putString("last_status", status)
            .putInt("last_count", count)
            .putString("last_message", safeMessage)
            .apply()
    }

    fun history(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "")
            .orEmpty()
            .ifBlank { "Chưa có lịch sử xử lý." }
}
