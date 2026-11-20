package com.buct.one.data.source.portal

import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalHolidayItem
import com.buct.one.data.model.PortalTermInfo
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * i北化信息门户（i.buct.edu.cn）纯函数响应解析器。
 * 遵循数据层铁律 3：改动解析逻辑必须递增 PARSER_VERSION。
 */
object PortalParser {
    const val PARSER_VERSION: Int = 1

    private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")

    data class SidPortalPageInfo(
        val execution: String,
        val croypto: String,
        val errorCode: String
    )

    private fun JSONObject.optCleanString(key: String, fallback: String = ""): String {
        if (isNull(key)) return fallback
        val v = optString(key, fallback).trim()
        return if (v.equals("null", ignoreCase = true)) fallback else v
    }

    fun parseSidPortalPage(html: String): SidPortalPageInfo {
        val doc = Jsoup.parse(html)
        fun text(id: String): String = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        return SidPortalPageInfo(
            execution = text("login-page-flowkey"),
            croypto = text("login-croypto"),
            errorCode = text("login-error-msg").ifEmpty { text("login-error-code") }
        )
    }

    fun isPortalAuthenticatedHtml(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.contains("login-page-flowkey") || html.contains("login-croypto")) {
            return false
        }
        return html.contains("fusionportal") ||
            html.contains("students_sy") ||
            html.contains("i北化") ||
            html.contains("_web/portal")
    }

    fun parseJxzResponse(rawJson: String): PortalCalendarSnapshot {
        val root = JSONObject(rawJson.trim())
        val result = root.optCleanString("result")
        if (result != "1") {
            val reason = root.optCleanString("reason")
            throw IllegalStateException(reason.ifBlank { "信息门户校历响应异常（result=$result）" })
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("信息门户校历响应缺少 data")

        val rawBeginDate = data.optCleanString("beginDate").take(10)
        val rawEndDate = data.optCleanString("endDate").take(10)
        val jxz = data.optCleanString("jxz").toIntOrNull() ?: 0

        val terms = ArrayList<PortalTermInfo>()
        val retArr = data.optJSONArray("retStr") ?: JSONArray()
        for (i in 0 until retArr.length()) {
            val obj = retArr.optJSONObject(i) ?: continue
            val xn = obj.optCleanString("xn").toIntOrNull() ?: continue
            val xq = obj.optCleanString("xq").toIntOrNull()?.takeIf { it in 1..3 } ?: continue
            val ksrq = obj.optCleanString("ksrq").take(10)
            if (!DATE_REGEX.matches(ksrq)) continue
            val jsrq = obj.optCleanString("jsrq").take(10)
            val wid = obj.optCleanString("wid")
            val isCurrent = obj.optCleanString("sfdqxq") == "1"
            terms.add(
                PortalTermInfo(
                    wid = wid,
                    academicYear = xn,
                    semester = xq,
                    termId = "$xn-${xn + 1}-$xq",
                    beginDate = ksrq,
                    endDate = jsrq,
                    isCurrent = isCurrent
                )
            )
        }

        val currentTerm = terms.firstOrNull { it.isCurrent }
            ?: terms.firstOrNull { it.beginDate == rawBeginDate && rawBeginDate.isNotBlank() }
        val currentTermId = currentTerm?.termId.orEmpty()
        val beginDate = rawBeginDate.ifBlank { currentTerm?.beginDate.orEmpty() }
        val endDate = rawEndDate.ifBlank { currentTerm?.endDate.orEmpty() }

        return PortalCalendarSnapshot(
            currentTermId = currentTermId,
            beginDate = beginDate,
            endDate = endDate,
            currentTeachingWeek = jxz,
            terms = terms,
            holidays = emptyList()
        )
    }

    fun parseCalendarDays(rawJson: String): List<PortalHolidayItem> {
        val arr = JSONArray(rawJson.trim())
        val byDate = LinkedHashMap<String, PortalHolidayItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val dayRaw = obj.optCleanString("day")
            val date = if (dayRaw.length >= 10) dayRaw.substring(0, 10) else ""
            if (!DATE_REGEX.matches(date)) continue
            val holidayName = obj.optCleanString("holidayName")
            val isHoliday = obj.optBoolean("isHoliday", false)
            if (!isHoliday && holidayName.isBlank()) continue
            val teachingWeek = obj.optInt("teachingWeek", 0)
            val candidate = PortalHolidayItem(
                date = date,
                holidayName = holidayName,
                isHoliday = isHoliday,
                teachingWeek = teachingWeek
            )
            val existing = byDate[date]
            if (existing == null || (existing.holidayName.isBlank() && candidate.holidayName.isNotBlank())) {
                byDate[date] = candidate
            }
        }
        return byDate.values.sortedBy { it.date }
    }
}
