package com.bartokplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class ProgramInfo(
    val title: String,
    val start: Date,
    val end: Date,
    val description: String
)

data class ScheduleInfo(
    val current: ProgramInfo?,
    val next: ProgramInfo?
)

object ProgramSchedule {
    private const val SCHEDULE_URL = "https://mediaklikk.hu/iface/broadcast/%s/broadcast_12.xml"

    private val dateFormats = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy.MM.dd HH:mm:ss"
    )

    private fun parseDate(str: String): Date? {
        for (fmt in dateFormats) {
            try {
                return SimpleDateFormat(fmt, Locale.US).parse(str)
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun fetch(): ScheduleInfo = withContext(Dispatchers.IO) {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val url = URL(String.format(SCHEDULE_URL, dateStr))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val xml = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))

            val items = doc.getElementsByTagName("Item")
            val programs = mutableListOf<ProgramInfo>()

            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                val title = item.getElementsByTagName("Title").item(0)?.textContent ?: ""
                val beginStr = item.getElementsByTagName("BeginDate").item(0)?.textContent ?: ""
                val endStr = item.getElementsByTagName("EndDate").item(0)?.textContent ?: ""
                val desc = item.getElementsByTagName("Description").item(0)?.textContent ?: ""

                val start = parseDate(beginStr) ?: continue
                val end = parseDate(endStr) ?: continue
                programs.add(ProgramInfo(title, start, end, desc))
            }

            programs.sortBy { it.start }

            val now = Date()
            val current = programs.lastOrNull { it.start <= now && it.end > now }
            val next = if (current != null) {
                programs.firstOrNull { it.start >= current.end }
            } else {
                programs.firstOrNull { it.start > now }
            }

            ScheduleInfo(current, next)
        } catch (_: Exception) {
            ScheduleInfo(null, null)
        }
    }
}
