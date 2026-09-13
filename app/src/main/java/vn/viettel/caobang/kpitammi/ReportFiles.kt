package vn.viettel.caobang.kpitammi

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object ReportFiles {
    private val fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun today(): String = LocalDate.now().format(fmt)

    fun baseDir(context: Context): File =
        File(context.getExternalFilesDir(null), "reports").apply { mkdirs() }

    fun dayDir(context: Context): File =
        File(baseDir(context), today()).apply { mkdirs() }

    fun extractedDir(context: Context): File =
        File(dayDir(context), "extracted").apply { mkdirs() }

    fun latestExtractedDir(context: Context): File? =
        baseDir(context).listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }
            ?.let { File(it, "extracted") }

    fun allowed(file: File): Boolean {
        if (file.name.startsWith(".")) return false
        if (file.absolutePath.lowercase().contains("__macosx")) return false
        return file.extension.lowercase() in setOf("png", "jpg", "jpeg", "pdf", "xlsx", "xls", "csv", "docx")
    }
}
