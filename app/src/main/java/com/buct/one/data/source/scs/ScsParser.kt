package com.buct.one.data.source.scs

import com.buct.one.data.model.ScsActivityItem
import com.buct.one.data.model.ScsAuditHistoryItem
import com.buct.one.data.model.ScsCategoryDetail
import com.buct.one.data.model.ScsCurrentEvalWindow
import com.buct.one.data.model.ScsEvalDimension
import com.buct.one.data.model.ScsEvalItem
import com.buct.one.data.model.ScsItemSelfEvalDetail
import com.buct.one.data.model.ScsOriginDetailRecord
import com.buct.one.data.model.ScsTermScore
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 第二课堂（scs.buct.edu.cn / eapi.buct.edu.cn）纯函数 JSON/HTML 解析器。
 * 改动解析逻辑时必须将 PARSER_VERSION + 1。
 */
object ScsParser {
    const val PARSER_VERSION = 2

    data class SidPortalFormParams(
        val execution: String,
        val croypto: String,
        val errorCode: String,
        val second: String,
        val captchaUrl: String
    )

    data class UserBasicInfo(
        val name: String,
        val studentId: String
    )

    data class StudentClassInfo(
        val collegeName: String,
        val className: String,
        val gradeName: String
    )

    private val DIMENSION_NAMES = mapOf(
        1 to "道德与思想素质评定",
        2 to "学术与科技创新评定",
        3 to "体育与身心健康评定",
        4 to "美学与人文素养评定",
        5 to "劳动与社会实践评定"
    )

    fun parseSidPortalPage(html: String): SidPortalFormParams {
        val doc = Jsoup.parse(html)
        fun text(id: String) = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        return SidPortalFormParams(
            execution = text("login-page-flowkey"),
            croypto = text("login-croypto"),
            errorCode = text("login-error-msg").ifEmpty { text("login-error-code") },
            second = text("sso-second"),
            captchaUrl = text("captcha-url")
        )
    }

    fun isUnauthorized(code: Int, message: String): Boolean {
        val lower = message.lowercase()
        return code == 401 || code == 403 || lower.contains("token") || message.contains("未登录") || message.contains("过期")
    }

    fun parseStringDataResponse(json: String): String {
        val obj = JSONObject(json)
        val code = obj.optInt("code", -1)
        if (code != 200) return ""
        return obj.optString("data", "").takeIf { it != "null" }.orEmpty()
    }

    fun parseUserInfo(json: String): UserBasicInfo {
        val obj = JSONObject(json)
        if (obj.optInt("code", -1) != 200) {
            throw IllegalStateException(obj.optString("message", "获取用户信息失败"))
        }
        val data = obj.optJSONObject("data") ?: return UserBasicInfo("", "")
        return UserBasicInfo(
            name = data.optString("name", "").takeIf { it != "null" }.orEmpty(),
            studentId = data.optString("gh", "").takeIf { it != "null" }.orEmpty()
        )
    }

    /** 兼容 `/scSelfEvalTerm/getSelfEvalTerm` 与 `/scSelfEvalTerm/getDefaultYearAndTerm`。 */
    fun parseCurrentEvalWindow(json: String): ScsCurrentEvalWindow? {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return null
            val data = obj.optJSONObject("data") ?: return null
            ScsCurrentEvalWindow(
                yearId = data.optInt("yearId", 0),
                termId = data.optInt("termId", 0),
                yearName = optCleanString(data, "yearName", "yearDesc"),
                termName = optCleanString(data, "termName", "termDesc"),
                isOpen = data.optBoolean("open", false),
                startTime = optCleanString(data, "selfStartTime"),
                endTime = optCleanString(data, "selfEndTime"),
                message = optCleanString(data, "msg", "message")
            )
        }.getOrNull()
    }

    fun parseEvaluationDimensions(json: String): List<ScsEvalDimension> {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return emptyList()
            val arr = obj.optJSONArray("data") ?: return emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("deleted", false)) continue
                    add(
                        ScsEvalDimension(
                            typeId = o.optInt("id", i + 1),
                            name = optCleanString(o, "description"),
                            totalMax = o.optDouble("totalScore", 0.0),
                            basicMax = o.optDouble("basicScore", 0.0),
                            extMax = o.optDouble("extScore", 0.0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun parseStudentClassInfo(json: String): StudentClassInfo {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return StudentClassInfo("", "", "")
            val arr = obj.optJSONArray("data") ?: return StudentClassInfo("", "", "")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val college = optCleanString(o, "courtName")
                val clazz = optCleanString(o, "className")
                val grade = optCleanString(o, "gradeName")
                if (college.isNotBlank() || clazz.isNotBlank()) {
                    return StudentClassInfo(college, clazz, grade)
                }
            }
            StudentClassInfo("", "", "")
        }.getOrDefault(StudentClassInfo("", "", ""))
    }

    fun parseAllTermsScores(json: String): List<ScsTermScore> {
        val obj = JSONObject(json)
        if (obj.optInt("code", -1) != 200) {
            throw IllegalStateException(obj.optString("message", "获取第二课堂成绩失败"))
        }
        val arr = obj.optJSONArray("data") ?: return emptyList()
        val result = ArrayList<ScsTermScore>()
        for (i in 0 until arr.length()) {
            val yearObj = arr.optJSONObject(i) ?: continue
            val yearName = optCleanString(yearObj, "mc")
            val yearId = yearObj.optInt("year", 0)
            val termsArr = yearObj.optJSONArray("terms") ?: continue
            for (j in 0 until termsArr.length()) {
                val t = termsArr.optJSONObject(j) ?: continue
                val termId = t.optInt("id", 0)
                val termName = optCleanString(t, "mc")
                val studentScoreObj = t.optJSONObject("studentScore")
                val detailsArr = t.optJSONArray("details")
                result.add(
                    ScsTermScore(
                        termId = termId,
                        yearId = t.optInt("yearId", yearId),
                        yearName = yearName,
                        termName = termName,
                        isFullYear = termId == 0 || termName == "全年",
                        status = t.optInt("status", 0),
                        totalScore = optNullableDouble(t, "totalScore"),
                        moral = studentScoreObj?.let { optNullableDouble(it, "moral") },
                        academic = studentScoreObj?.let { optNullableDouble(it, "academic") },
                        physical = studentScoreObj?.let { optNullableDouble(it, "physical") },
                        aesthetics = studentScoreObj?.let { optNullableDouble(it, "aesthetics") },
                        labor = studentScoreObj?.let { optNullableDouble(it, "labor") },
                        isOpenSelfEva = t.optBoolean("isOpenSelfEva", false),
                        details = parseCategoryDetails(detailsArr)
                    )
                )
            }
        }
        return result.sortedWith(
            compareByDescending<ScsTermScore> { it.yearName }
                .thenBy { if (it.isFullYear) 0 else it.termId }
        )
    }

    /** 解析 `POST /scStudentEvaluationItem/getStudentPassScore` 单维度自评细则与已通过得分。 */
    fun parseStudentPassScoreCategory(json: String, fallbackTypeId: Int): ScsCategoryDetail? {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return null
            val d = obj.optJSONObject("data") ?: return null
            val typeId = d.optInt("typeId", fallbackTypeId).takeIf { it in 1..5 } ?: fallbackTypeId
            val items = mutableListOf<ScsEvalItem>()
            appendEvalItems(d.optJSONArray("basic"), isBasic = true, fallbackTypeId = typeId, out = items)
            appendEvalItems(d.optJSONArray("ext"), isBasic = false, fallbackTypeId = typeId, out = items)
            if (items.isEmpty()) return null
            val basicFromItems = items.filter { it.isBasic }.mapNotNull { it.score }
                .takeIf { it.isNotEmpty() }?.sum()
            val extFromItems = items.filter { !it.isBasic }.mapNotNull { it.score }
                .takeIf { it.isNotEmpty() }?.sum()
            ScsCategoryDetail(
                typeId = typeId,
                title = optCleanString(d, "desc").ifBlank { DIMENSION_NAMES[typeId] ?: "维度 $typeId" },
                basicScore = optNullableDouble(d, "basicScore") ?: basicFromItems,
                extScore = optNullableDouble(d, "extScore") ?: extFromItems,
                totalScore = optNullableDouble(d, "studentTypeScore")
                    ?: optNullableDouble(d, "totalScore")
                    ?: if (basicFromItems != null || extFromItems != null) {
                        (basicFromItems ?: 0.0) + (extFromItems ?: 0.0)
                    } else null,
                maxTotalScore = optNullableDouble(d, "typeTotalScore")
                    ?: when (typeId) { 1 -> 180.0; 2 -> 120.0; else -> 100.0 },
                items = items
            )
        }.getOrNull()
    }

    fun parseCurrentTermEvalDetails(json: String): List<ScsCategoryDetail> {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return emptyList()
            val data = obj.optJSONObject("data") ?: return emptyList()
            val allItems = mutableListOf<ScsEvalItem>()
            appendEvalItems(data.optJSONArray("basic"), isBasic = true, fallbackTypeId = 0, out = allItems)
            appendEvalItems(data.optJSONArray("ext"), isBasic = false, fallbackTypeId = 0, out = allItems)
            val byType = allItems.groupBy { it.typeId }
            byType.entries.sortedBy { it.key }.map { (typeId, items) ->
                val scoredSum = items.mapNotNull { it.score }.sum().takeIf { items.any { it.score != null } }
                ScsCategoryDetail(
                    typeId = typeId,
                    title = DIMENSION_NAMES[typeId] ?: "维度 $typeId",
                    basicScore = items.filter { it.isBasic }.mapNotNull { it.score }.sum().takeIf { items.any { it.isBasic && it.score != null } },
                    extScore = items.filter { !it.isBasic }.mapNotNull { it.score }.sum().takeIf { items.any { !it.isBasic && it.score != null } },
                    totalScore = scoredSum,
                    maxTotalScore = when (typeId) { 1 -> 180.0; 2 -> 120.0; else -> 100.0 },
                    items = items
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 解析 `POST /scOriginScoreDetail/student/getStudentWebItemDetail`。
     * 当学校服务端返回非 200（如新学期未初始化报 code=500）时抛出带原始提示的异常。
     */
    fun parseItemSelfEvalDetail(
        json: String,
        yearId: Int,
        termId: Int,
        itemId: Long
    ): ScsItemSelfEvalDetail {
        val obj = JSONObject(json)
        val code = obj.optInt("code", -1)
        val msg = optCleanString(obj, "message", "msg")
        if (code != 200) {
            throw IllegalStateException(
                msg.ifBlank { "学校自评详情接口返回异常（code=$code）" }
            )
        }
        val data = obj.optJSONObject("data")
            ?: throw IllegalStateException(msg.ifBlank { "学校未返回该项自评详情数据" })

        val itemMeta = data.optJSONObject("scoreItemDetail")
            ?: data.optJSONObject("item")
            ?: data

        val gateObj = data.optJSONObject("laborHoursGate")
        val gateBlocked = gateObj?.optBoolean("blocked", false) ?: false
        val gateMsg = gateObj?.let { optCleanString(it, "message", "msg", "reason", "tip") }.orEmpty()

        val unSubmitReason = optCleanString(itemMeta, "unSubmitReason")
            .ifBlank { optCleanString(data, "unSubmitReason") }

        val detailsArr = data.optJSONArray("studentDetails")
            ?: data.optJSONArray("details")
            ?: data.optJSONArray("records")
        val records = buildList {
            if (detailsArr != null) {
                for (i in 0 until detailsArr.length()) {
                    val r = detailsArr.optJSONObject(i) ?: continue
                    val recId = r.optLong("id", r.optLong("originDetailId", i.toLong()))
                    add(
                        ScsOriginDetailRecord(
                            id = recId,
                            itemId = r.optLong("itemId", itemId),
                            score = optNullableDouble(r, "score"),
                            laborHours = optNullableDouble(r, "laborHours"),
                            mark = optCleanString(r, "mark", "reason"),
                            journalName = optCleanString(r, "journalName", "articleName"),
                            certificates = parseStringListField(r, "certificate"),
                            auditStatus = optCleanString(r, "auditStatus", "status"),
                            teacherComments = optCleanString(r, "teacherComments", "auditComment", "comment"),
                            createTime = optCleanString(r, "createTime", "submitTime"),
                            updateTime = optCleanString(r, "updateTime"),
                            source = optCleanString(r, "source"),
                            canUpdate = r.optBoolean("canUpdate", false),
                            canAudit = r.optBoolean("canAudit", false)
                        )
                    )
                }
            }
        }

        val optional = parseOptionalScores(itemMeta)
            .ifEmpty { parseOptionalScores(data) }

        return ScsItemSelfEvalDetail(
            itemId = itemId,
            yearId = yearId,
            termId = termId,
            shortDesc = optCleanString(itemMeta, "shortDesc").ifBlank { optCleanString(data, "shortDesc") },
            description = optCleanString(itemMeta, "description").ifBlank { optCleanString(data, "description") },
            itemScore = optNullableDouble(data, "itemScore") ?: optNullableDouble(itemMeta, "score"),
            itemMaxScore = optNullableDouble(itemMeta, "itemMaxScore") ?: optNullableDouble(data, "itemMaxScore"),
            optionalScores = optional,
            reasonRequired = itemMeta.optBoolean("reasonRequired", data.optBoolean("reasonRequired", false)),
            imageRequired = itemMeta.optBoolean("imageRequired", data.optBoolean("imageRequired", false)),
            journalNameRequired = itemMeta.optBoolean("journalNameRequired", data.optBoolean("journalNameRequired", false)),
            laborHoursItem = itemMeta.optBoolean("laborHoursItem", data.optBoolean("laborHoursItem", false)),
            laborHoursApproved = optNullableDouble(data, "laborHoursApproved"),
            requiredHours = optNullableDouble(data, "requiredHours"),
            laborHoursGateBlocked = gateBlocked,
            laborHoursGateMessage = gateMsg,
            singleSubmit = data.optBoolean("singleSubmit", itemMeta.optBoolean("singleSubmit", false)),
            unSubmitReason = unSubmitReason,
            records = records
        )
    }

    /** 解析 `POST /image/uploadToFiles` 上传返回的图片 URL。 */
    fun parseUploadedImageUrl(json: String): String {
        val obj = JSONObject(json)
        val code = obj.optInt("code", -1)
        val msg = optCleanString(obj, "message", "msg")
        if (code != 200) {
            throw IllegalStateException(msg.ifBlank { "图片上传失败（code=$code）" })
        }
        val dataObj = obj.optJSONObject("data")
        val url = if (dataObj != null) {
            optCleanString(dataObj, "url", "fileUrl", "path")
        } else {
            optCleanString(obj, "data", "url")
        }
        if (url.isBlank()) {
            throw IllegalStateException("图片上传成功但未返回地址")
        }
        return url
    }

    /** 校验 `updateOrCreateOriginDetail` / `revokeDeleteOriginDetail` 等写操作响应。 */
    fun ensureActionSucceeded(json: String, fallbackError: String) {
        val obj = JSONObject(json)
        val code = obj.optInt("code", -1)
        if (code != 200) {
            val msg = optCleanString(obj, "message", "msg")
            throw IllegalStateException(msg.ifBlank { "$fallbackError（code=$code）" })
        }
    }

    /** 解析 `POST /scAuditOriginHistory/listHistories`。 */
    fun parseAuditHistories(json: String): List<ScsAuditHistoryItem> {
        val obj = JSONObject(json)
        val code = obj.optInt("code", -1)
        if (code != 200) {
            val msg = optCleanString(obj, "message", "msg")
            throw IllegalStateException(msg.ifBlank { "获取评定记录失败（code=$code）" })
        }
        val arr = obj.optJSONArray("data")
            ?: obj.optJSONObject("data")?.let { d ->
                d.optJSONArray("records") ?: d.optJSONArray("list")
            }
            ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                add(
                    ScsAuditHistoryItem(
                        id = r.optLong("id", i.toLong()),
                        itemDesc = optCleanString(r, "shortDesc", "itemName", "itemDesc", "description"),
                        typeName = optCleanString(r, "typeName", "evaluationTypeName", "typeDesc"),
                        score = optNullableDouble(r, "score") ?: optNullableDouble(r, "newScore"),
                        laborHours = optNullableDouble(r, "laborHours"),
                        auditStatus = optCleanString(r, "auditStatus", "status", "operateType"),
                        mark = optCleanString(r, "mark", "reason"),
                        teacherComments = optCleanString(r, "teacherComments", "auditComment", "comment"),
                        createTime = optCleanString(r, "createTime", "operateTime", "auditTime")
                    )
                )
            }
        }
    }

    fun parseActivities(json: String): List<ScsActivityItem> {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return emptyList()
            val data = obj.optJSONObject("data") ?: return emptyList()
            val records = data.optJSONArray("records") ?: return emptyList()
            buildList(records.length()) {
                for (i in 0 until records.length()) {
                    val r = records.optJSONObject(i) ?: continue
                    val title = optCleanString(r, "activityName", "title", "name")
                    if (title.isBlank()) continue
                    add(
                        ScsActivityItem(
                            id = optCleanString(r, "id").ifBlank { i.toString() },
                            title = title,
                            categoryName = optCleanString(r, "typeName", "categoryName", "plateName"),
                            startTime = optCleanString(r, "startTime", "activityStartTime"),
                            endTime = optCleanString(r, "endTime", "activityEndTime"),
                            location = optCleanString(r, "address", "location", "place"),
                            score = optCleanString(r, "score", "maxScore"),
                            status = optCleanString(r, "statusName", "status")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseCategoryDetails(detailsArr: JSONArray?): List<ScsCategoryDetail> {
        if (detailsArr == null) return emptyList()
        val list = ArrayList<ScsCategoryDetail>(detailsArr.length())
        for (i in 0 until detailsArr.length()) {
            val d = detailsArr.optJSONObject(i) ?: continue
            val typeId = d.optInt("typeId", i + 1)
            val items = mutableListOf<ScsEvalItem>()
            appendEvalItems(d.optJSONArray("basic"), isBasic = true, fallbackTypeId = typeId, out = items)
            appendEvalItems(d.optJSONArray("ext"), isBasic = false, fallbackTypeId = typeId, out = items)
            list.add(
                ScsCategoryDetail(
                    typeId = typeId,
                    title = optCleanString(d, "desc"),
                    basicScore = optNullableDouble(d, "basicScore"),
                    extScore = optNullableDouble(d, "extScore"),
                    totalScore = optNullableDouble(d, "studentTypeScore")
                        ?: optNullableDouble(d, "totalScore"),
                    maxTotalScore = optNullableDouble(d, "typeTotalScore"),
                    items = items
                )
            )
        }
        return list.sortedBy { it.typeId }
    }

    private fun appendEvalItems(
        arr: JSONArray?,
        isBasic: Boolean,
        fallbackTypeId: Int,
        out: MutableList<ScsEvalItem>
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("deleted", false)) continue
            out.add(
                ScsEvalItem(
                    id = o.optLong("id", i.toLong()),
                    typeId = o.optInt("typeId", fallbackTypeId).takeIf { it > 0 } ?: fallbackTypeId,
                    isBasic = o.optBoolean("basic", isBasic),
                    shortDesc = optCleanString(o, "shortDesc"),
                    description = optCleanString(o, "description"),
                    score = optNullableDouble(o, "score"),
                    mark = optCleanString(o, "mark"),
                    teacherComments = optCleanString(o, "teacherComments"),
                    laborHours = optNullableDouble(o, "laborHours"),
                    stuAble = o.optBoolean("stuAble", false),
                    optionalScores = parseOptionalScores(o),
                    itemMaxScore = optNullableDouble(o, "itemMaxScore"),
                    reasonRequired = o.optBoolean("reasonRequired", false),
                    imageRequired = o.optBoolean("imageRequired", false),
                    journalNameRequired = o.optBoolean("journalNameRequired", false),
                    laborHoursItem = o.optBoolean("laborHoursItem", false),
                    laborHoursRule = optCleanString(o, "laborHoursRule"),
                    havePendApproval = o.optBoolean("havePendApproval", false),
                    lowScoreWarn = o.optBoolean("lowScoreWarn", false),
                    unSubmitReason = optCleanString(o, "unSubmitReason"),
                    problemNumber = o.optInt("problemNumber", 0)
                )
            )
        }
    }

    private fun parseOptionalScores(obj: JSONObject): List<Double> {
        if (!obj.has("optional") || obj.isNull("optional")) return emptyList()
        val arr = obj.optJSONArray("optional") ?: run {
            val raw = obj.optString("optional", "").trim()
            if (raw.startsWith("[")) runCatching { JSONArray(raw) }.getOrNull() else null
        } ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val v = arr.optDouble(i, Double.NaN)
                if (!v.isNaN()) add(v)
            }
        }.distinct().sorted()
    }

    private fun parseStringListField(obj: JSONObject, key: String): List<String> {
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        val arr = obj.optJSONArray(key) ?: run {
            val raw = obj.optString(key, "").trim()
            when {
                raw.startsWith("[") -> runCatching { JSONArray(raw) }.getOrNull()
                raw.startsWith("http", ignoreCase = true) -> return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> null
            }
        } ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) add(s)
            }
        }
    }

    private fun optNullableDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private fun optCleanString(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val s = obj.optString(k, "").trim()
            if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) {
                return s
            }
        }
        return ""
    }
}
