package dev.foistracker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val reportDate = DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm 'IST'", Locale.ENGLISH).withZone(ist)
fun displayTime(time: Long) = reportDate.format(Instant.ofEpochMilli(time))
private val inputDates = listOf("dd-MM-uuuu HH:mm:ss", "dd-MM-uuuu HH:mm", "dd/MM/uuuu HH:mm",
    "dd/MM/uuuu HH:mm:ss", "dd-MMM-uuuu HH:mm", "dd MMM uuuu HH:mm", "uuuu-MM-dd HH:mm:ss",
    "dd-MM-uuuu hh:mm a", "dd/MM/uuuu hh:mm a")
    .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) } + DateTimeFormatter.ISO_LOCAL_DATE_TIME

fun freightTime(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    val text = value.trim().removeSuffix(" IST")
    return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: inputDates.firstNotNullOfOrNull { parser ->
            runCatching { LocalDateTime.parse(text, parser).atZone(ist).toInstant() }.getOrNull()
        }
}

fun displayFreightTime(value: String?) = freightTime(value)?.let(reportDate::format) ?: value ?: "Not Reported"

enum class ReportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"), CSV("csv", "text/csv"), TEXT("txt", "text/plain")
}

object Reports {
    fun data(freight: Freight, generated: Long = System.currentTimeMillis()): List<Pair<String, String>> =
        listOf("Report generated" to displayTime(generated), "FOIS retrieved" to displayTime(freight.retrieved),
            "Enquiry source" to freight.source) + (fields + (freight.values.keys - fields.toSet())).distinct().map { it to freight.get(it) } +
            listOf("Previous ETA" to (freight.previousEta ?: "Not Reported")) +
            freight.journey.map { row ->
                "Journey station" to listOf(row.station, "Arrival ${row.arrival.ifBlank { "Not Reported" }}",
                    "Departure ${row.departure.ifBlank { "Not Reported" }}", "Zone ${row.zone.ifBlank { "Not Reported" }}",
                    "Division ${row.division.ifBlank { "Not Reported" }}", "Movement ${row.movement.ifBlank { "Not Reported" }}").joinToString(" | ")
            }

    fun text(freight: Freight) = "FOIS Tracker • Freight report\n\n" + data(freight).joinToString("\n") { (key, value) -> "$key: $value" }

    fun csvCell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    fun write(format: ReportFormat, freight: Freight, output: OutputStream) {
        when (format) {
            ReportFormat.TEXT -> output.write(text(freight).toByteArray())
            ReportFormat.CSV -> {
                val data = listOf("Field" to "Value") + data(freight)
                output.write(data.joinToString("\r\n", postfix = "\r\n") { (a, b) -> "${csvCell(a)},${csvCell(b)}" }.toByteArray())
            }
            ReportFormat.PDF -> pdf(freight, output)
        }
    }

    private fun pdf(freight: Freight, output: OutputStream) {
        val doc = PdfDocument()
        try {
            var number = 0
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int) {
                paint.color = color
                paint.textSize = size
                canvas.drawText(value, x, y, paint)
            }
            fun newPage(): PdfDocument.Page {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                paint.color = Color.rgb(15, 42, 51)
                page.canvas.drawRect(0f, 0f, 595f, 112f, paint)
                text(page.canvas, "FOIS TRACKER", 36f, 40f, 12f, Color.rgb(143, 245, 198))
                text(page.canvas, "Rail freight report", 36f, 75f, 26f, Color.WHITE)
                text(page.canvas, "Independent application • Page $number", 36f, 817f, 10f, Color.DKGRAY)
                return page
            }
            var page = newPage()
            var y = 146f
            data(freight).forEach { (label, value) ->
                if (y > 740f) {
                    doc.finishPage(page)
                    page = newPage()
                    y = 146f
                }
                text(page.canvas, label, 36f, y, 10f, Color.rgb(25, 97, 83))
                y += 16
                var line = ""
                value.replace(Regex("\\s+"), " ").split(' ').forEach { token ->
                    paint.textSize = 11f
                    val combined = if (line.isEmpty()) token else "$line $token"
                    if (paint.measureText(combined) > 515 && line.isNotEmpty()) {
                        text(page.canvas, line, 36f, y, 11f, Color.DKGRAY)
                        line = token
                        y += 15
                        if (y > 775f) {
                            doc.finishPage(page)
                            page = newPage()
                            y = 146f
                        }
                    } else line = combined
                }
                text(page.canvas, line, 36f, y, 11f, Color.DKGRAY)
                y += 23
            }
            doc.finishPage(page)
            doc.writeTo(output)
        } finally {
            doc.close()
        }
    }
}
