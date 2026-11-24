package dev.foistracker

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

object Reports {
    private fun data(freight: Freight, generated: Long = System.currentTimeMillis()): List<Pair<String, String>> =
        listOf("Report generated" to displayTime(generated), "FOIS retrieved" to displayTime(freight.retrieved),
            "Enquiry source" to freight.source) + (fields + (freight.values.keys - fields.toSet())).distinct().map { it to freight.get(it) } +
            listOf("Previous ETA" to (freight.previousEta ?: "Not Reported")) +
            freight.journey.map { row ->
                "Journey station" to listOf(row.station, "Arrival ${row.arrival.ifBlank { "Not Reported" }}",
                    "Departure ${row.departure.ifBlank { "Not Reported" }}", "Zone ${row.zone.ifBlank { "Not Reported" }}",
                    "Division ${row.division.ifBlank { "Not Reported" }}", "Movement ${row.movement.ifBlank { "Not Reported" }}").joinToString(" | ")
            }

    fun text(freight: Freight) = "FOIS Tracker • Freight report\n\n" + data(freight).joinToString("\n") { (key, value) -> "$key: $value" }
}
