package com.buct.one.data.source.theol

import com.buct.one.data.model.TheolCourseInfo
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolTestItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 北化在线（THEOL / MEOL）HTML 纯函数解析器。
 * 移植自 Ling0727-ai/go-buct-course (MIT)。
 * 改动解析逻辑时必须将 PARSER_VERSION + 1。
 */
object TheolParser {
    const val PARSER_VERSION = 1
    const val BASE_URL = "https://course.buct.edu.cn"

    private val testStartRegex = Regex("""gotostart\('(\d+)'""")
    private val deadlineFormats = listOf(
        "yyyy年M月d日 HH:mm:ss",
        "yyyy年M月d日 HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm"
    )

    data class SidPortalPage(
        val execution: String,
        val croypto: String,
        val vendor: String,
        val risk: String,
        val rule: String,
        val second: String,
        val errorCode: String,
        val captchaUrl: String
    )

    fun parseSidPortalPage(html: String): SidPortalPage {
        val doc = Jsoup.parse(html)
        fun text(id: String) = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        val code = text("login-error-msg").ifEmpty { text("login-error-code") }
        return SidPortalPage(
            execution = text("login-page-flowkey"),
            croypto = text("login-croypto"),
            vendor = text("recaptchaVendor"),
            risk = text("riskSystemSwitch"),
            rule = text("login-rule-type"),
            second = text("sso-second"),
            errorCode = code,
            captchaUrl = text("captcha-url")
        )
    }

    /** 解析 interaction_reminder_v8.jsp 中的待办作业与测验课程 LID */
    fun parsePendingCourses(html: String, baseUrl: String = BASE_URL): Pair<List<TheolCourseInfo>, List<TheolCourseInfo>> {
        val doc = Jsoup.parse(html, baseUrl)
        val homeworks = mutableListOf<TheolCourseInfo>()
        val tests = mutableListOf<TheolCourseInfo>()
        val seenHw = mutableSetOf<String>()
        val seenTest = mutableSetOf<String>()

        for (a in doc.select("a[onclick]")) {
            val onclick = a.attr("onclick")
            if (onclick.contains("&t=hw")) {
                extractPendingCourse(a, "homework", baseUrl)?.let {
                    if (seenHw.add(it.lid)) homeworks.add(it)
                }
            }
            if (onclick.contains("&t=test")) {
                extractPendingCourse(a, "test", baseUrl)?.let {
                    if (seenTest.add(it.lid)) tests.add(it)
                }
            }
        }
        return homeworks to tests
    }

    private fun extractPendingCourse(a: Element, expectedType: String, baseUrl: String): TheolCourseInfo? {
        val courseName = a.text().trim()
        val onclick = a.attr("onclick")
        if (!onclick.contains("lid=")) return null
        val lid = onclick.substringAfter("lid=").substringBefore("&").substringBefore("'").substringBefore("\"").trim()
        if (lid.isEmpty()) return null
        if (courseName.contains("门课程") && courseName.contains("待提交")) return null
        if (expectedType == "test" && (lid == "24199" || lid == "27215")) return null

        val url = if (expectedType == "test") {
            "$baseUrl/meol/common/question/test/student/list.jsp?sortColumn=createTime&status=1&tagbug=client&sortDirection=-1&strStyle=lesson19&cateId=$lid&pagingPage=1&pagingNumberPer=30"
        } else {
            "$baseUrl/meol/jpk/course/layout/newpage/index.jsp?courseId=$lid"
        }
        return TheolCourseInfo(lid = lid, courseName = courseName, type = expectedType, url = url)
    }

    /** 解析单个页面中的所有作业（支持横表与竖表两种结构） */
    fun parseHomeworkPage(
        html: String,
        sourceUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): List<TheolHomeworkItem> {
        val doc = Jsoup.parse(html, sourceUrl)
        val result = mutableListOf<TheolHomeworkItem>()
        val seen = mutableSetOf<String>()

        for (table in doc.select("table.valuelist")) {
            val vertical = parseVerticalHomeworkTable(table, sourceUrl, lid, courseName, nowMillis)
            if (vertical != null) {
                if (vertical.hwtid.isNotEmpty() && seen.add(vertical.hwtid)) {
                    result.add(vertical)
                }
                continue
            }
            for (row in table.select("tr")) {
                val item = parseHomeworkRow(row, sourceUrl, lid, courseName, nowMillis)
                if (item != null && seen.add(item.hwtid)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    private fun parseVerticalHomeworkTable(
        table: Element,
        sourceUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): TheolHomeworkItem? {
        val rows = table.select("tr")
        val isVertical = rows.any { tr ->
            val ths = tr.select("> th")
            val tds = tr.select("> td")
            ths.size == 1 && tds.size == 1 && ths.text().contains("标题")
        }
        if (!isVertical) return null

        var title = ""
        var hwtid = ""
        var detailHref = ""
        var isGroup = false
        var deadline = ""
        var score = ""
        var publisher = ""
        var submitHref = ""
        var resultHref = ""

        for (tr in rows) {
            val ths = tr.select("> th")
            val tds = tr.select("> td")
            if (ths.size != 1 || tds.size != 1) continue
            val label = ths.text().trim()
            val value = tds.text().trim()
            val td = tds.first() ?: continue
            when {
                label.contains("标题") -> {
                    title = value
                    for (a in td.select("a[href]")) {
                        val href = resolveActiveLink(a, sourceUrl)
                        val id = extractHwtid(href)
                        if (id.isNotEmpty()) {
                            hwtid = id
                            detailHref = href
                            break
                        }
                    }
                    isGroup = td.select("img[title=分组作业]").isNotEmpty()
                }
                label.contains("截止") -> deadline = value
                label.contains("评分") || label.contains("分数") -> score = value
                label.contains("发布人") -> publisher = value
                label.contains("提交作业") -> submitHref = firstActiveLink(td.select("a[href]"), sourceUrl)
                label.contains("查看结果") -> resultHref = firstActiveLink(td.select("a[href]"), sourceUrl)
            }
        }

        if (hwtid.isEmpty()) {
            hwtid = listOf(submitHref, resultHref, sourceUrl)
                .firstNotNullOfOrNull { extractHwtid(it).takeIf(String::isNotEmpty) }
                .orEmpty()
        }
        if (hwtid.isEmpty()) return null

        return finalizeHomework(
            hwtid = hwtid,
            lid = lid,
            courseName = courseName,
            title = title,
            deadline = deadline,
            score = score,
            publisher = publisher,
            isGroup = isGroup,
            detailHref = detailHref.ifEmpty { "$BASE_URL/meol/common/hw/student/hwtask.view.jsp?hwtid=$hwtid" },
            submitHref = submitHref,
            resultHref = resultHref,
            nowMillis = nowMillis
        )
    }

    private fun parseHomeworkRow(
        row: Element,
        sourceUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): TheolHomeworkItem? {
        val cells = row.select("> td")
        if (cells.size < 4) return null

        var title = ""
        var hwtid = ""
        var detailHref = ""
        for (a in cells[0].select("a[href]")) {
            val href = resolveActiveLink(a, sourceUrl)
            val id = extractHwtid(href)
            if (id.isNotEmpty()) {
                title = a.text().trim()
                hwtid = id
                detailHref = href
                break
            }
        }
        if (hwtid.isEmpty()) return null

        return finalizeHomework(
            hwtid = hwtid,
            lid = lid,
            courseName = courseName,
            title = title,
            deadline = cells[1].text().trim(),
            score = cells[2].text().trim(),
            publisher = cells[3].text().trim(),
            isGroup = row.select("img[title=分组作业]").isNotEmpty(),
            detailHref = detailHref,
            submitHref = firstActiveLink(row.select("a.enter[href]"), sourceUrl),
            resultHref = firstActiveLink(row.select("a.view[href]"), sourceUrl),
            nowMillis = nowMillis
        )
    }

    private fun finalizeHomework(
        hwtid: String,
        lid: String,
        courseName: String,
        title: String,
        deadline: String,
        score: String,
        publisher: String,
        isGroup: Boolean,
        detailHref: String,
        submitHref: String,
        resultHref: String,
        nowMillis: Long
    ): TheolHomeworkItem {
        val hasResult = resultHref.isNotEmpty()
        val trimmedScore = score.trim()
        val completed = hasResult || (
            trimmedScore.isNotEmpty() &&
                trimmedScore != "暂无分数" &&
                trimmedScore != "未评分" &&
                trimmedScore != "暂无成绩"
            )
        val deadlineMillis = parseDeadlineMillis(deadline)
        val open = when {
            deadline.trim() in setOf("", "不限", "无限制", "无截止时间") -> true
            deadlineMillis == null -> false
            else -> deadlineMillis > nowMillis
        }
        val canSubmit = submitHref.isNotEmpty() && !completed && open
        val status = when {
            completed -> "已完成"
            deadline.trim() !in setOf("", "不限", "无限制", "无截止时间") && deadlineMillis == null -> "截止时间未知"
            !open -> "已截止"
            canSubmit -> "未提交"
            else -> "不可提交"
        }
        return TheolHomeworkItem(
            hwtid = hwtid,
            lid = lid,
            courseName = courseName,
            title = title.ifEmpty { "未命名作业 #$hwtid" },
            deadline = deadline,
            deadlineMillis = deadlineMillis,
            score = trimmedScore,
            publisher = publisher,
            canSubmit = canSubmit,
            hasResult = hasResult,
            isGroup = isGroup,
            status = status,
            detailHref = detailHref,
            submitHref = submitHref
        )
    }

    /** 解析在线测验列表页 */
    fun parseTestList(html: String, lid: String, fallbackCourseName: String, baseUrl: String = BASE_URL): List<TheolTestItem> {
        val doc: Document = Jsoup.parse(html, baseUrl)
        val titleText = doc.title().trim()
        val courseName = if (titleText.contains("测试")) {
            titleText.replace("测试", "").trim().ifEmpty { fallbackCourseName }
        } else {
            fallbackCourseName
        }

        val table = doc.selectFirst("table.valuelist")
            ?: doc.selectFirst("table[border=0][cellspacing=0][cellpadding=0]")
            ?: return emptyList()

        val result = mutableListOf<TheolTestItem>()
        val rows = table.select("tr")
        for (i in 1 until rows.size) {
            val cells = rows[i].select("td")
            if (cells.size < 8) continue

            val title = cells[0].text().trim()
            val startTime = cells[1].text().trim()
            val endTime = cells[2].text().trim()
            val allowedAttempts = cells[3].text().trim()
            val duration = cells[4].text().trim()

            val onclick = cells[5].selectFirst("a")?.attr("onclick").orEmpty()
            val testId = testStartRegex.find(onclick)?.groupValues?.getOrNull(1).orEmpty()
            val canStart = testId.isNotEmpty()
            val resultHref = cells[7].selectFirst("a")?.attr("href").orEmpty()
            val hasResult = resultHref.isNotEmpty()
            val status = when {
                hasResult -> "已完成"
                canStart -> "可进行"
                else -> "未开始"
            }

            result.add(
                TheolTestItem(
                    testId = testId.ifEmpty { "${lid}_$i" },
                    lid = lid,
                    courseName = courseName,
                    title = title,
                    startTime = startTime,
                    endTime = endTime,
                    endTimeMillis = parseDeadlineMillis(endTime),
                    duration = duration,
                    allowedAttempts = allowedAttempts,
                    canStart = canStart,
                    hasResult = hasResult,
                    status = status,
                    testUrl = if (testId.isNotEmpty()) "$baseUrl/meol/common/question/test/student/test_start.jsp?testId=$testId" else ""
                )
            )
        }
        return result
    }

    fun parseDeadlineMillis(raw: String): Long? {
        val normalized = raw.trim().replace(Regex("""\s+"""), " ")
        if (normalized.isEmpty()) return null
        val tz = TimeZone.getTimeZone("GMT+8")
        for (pattern in deadlineFormats) {
            val sdf = SimpleDateFormat(pattern, Locale.CHINA).apply {
                isLenient = false
                timeZone = tz
            }
            val parsed = runCatching { sdf.parse(normalized)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun firstActiveLink(links: List<Element>, sourceUrl: String): String {
        for (a in links) {
            val resolved = resolveActiveLink(a, sourceUrl)
            if (resolved.isNotEmpty()) return resolved
        }
        return ""
    }

    private fun resolveActiveLink(a: Element, sourceUrl: String): String {
        if (a.hasAttr("disabled")) return ""
        if (a.attr("aria-disabled").equals("true", ignoreCase = true)) return ""
        if (a.attr("class").lowercase(Locale.ROOT).contains("disabled")) return ""
        val href = a.attr("href").trim()
        if (href.isEmpty() || href.startsWith("#")) return ""
        return runCatching {
            val resolved = URL(URL(sourceUrl), href)
            if (resolved.protocol == "http" || resolved.protocol == "https") resolved.toString() else ""
        }.getOrDefault("")
    }

    private fun extractHwtid(href: String): String {
        if (href.isEmpty()) return ""
        val query = runCatching { URI(href).rawQuery }.getOrNull().orEmpty()
        val id = query.split('&')
            .firstOrNull { it.startsWith("hwtid=") }
            ?.substringAfter("hwtid=")
            ?.trim()
            .orEmpty()
        if (id.isEmpty() || !id.all(Char::isDigit) || id.all { it == '0' }) return ""
        return id
    }
}
