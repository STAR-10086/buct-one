package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalHolidayItem
import com.buct.one.data.model.PortalTermInfo
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * i北化官方校历（i.buct.edu.cn）本地快照存储。
 *
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class PortalStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "campus/default").apply {
        if (!exists()) mkdirs()
    }

    private val snapshotFile = File(baseDir, "portal_calendar.snapshot.v1.json")

    @Synchronized
    fun read(currentParserVersion: Int): DomainSlice<PortalCalendarSnapshot> {
        if (!snapshotFile.exists()) {
            return DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion)
        }
        return runCatching {
            val root = JSONObject(snapshotFile.readText(Charsets.UTF_8))
            val provObj = root.getJSONObject("provenance")
            val savedParserVersion = provObj.optInt("parserVersion", 1)
            val savedState = RefreshState.valueOf(
                provObj.optString("refreshState", RefreshState.NEVER.name)
            )
            val dataObj = root.optJSONObject("data")
            val snapshot = dataObj?.let { deserializeSnapshot(it) }
            val effectiveState = if (snapshot != null && savedParserVersion < currentParserVersion) {
                RefreshState.STALE
            } else {
                savedState
            }
            val provenance = Provenance(
                source = provObj.optString("source", SOURCE_ID),
                capturedAt = provObj.optLong("capturedAt", 0L),
                parserVersion = max(savedParserVersion, currentParserVersion),
                complete = provObj.optBoolean("complete", true),
                incompleteReason = provObj.optString("incompleteReason", "").ifBlank { null },
                refreshState = effectiveState
            )
            DomainSlice(
                data = snapshot,
                provenance = provenance,
                lastError = root.optString("lastError", "").ifBlank { null }
            )
        }.getOrElse {
            DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion)
        }
    }

    @Synchronized
    fun commitSuccess(
        snapshot: PortalCalendarSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<PortalCalendarSnapshot> {
        val prev = read(parserVersion)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val isEmpty = snapshot.beginDate.isBlank() && snapshot.terms.isEmpty()
        val state = if (isEmpty) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH
        val next = DomainSlice(
            data = snapshot,
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicTime,
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                complete = true,
                incompleteReason = null,
                refreshState = state
            ),
            lastError = null
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<PortalCalendarSnapshot> {
        val prev = read(parserVersion)
        val nextState = if (prev.data != null) RefreshState.FAILED_FALLBACK else RefreshState.NEVER
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { "官方校历同步失败" }
        )
        writeSlice(next)
        return next
    }

    private fun writeSlice(slice: DomainSlice<PortalCalendarSnapshot>) {
        val root = JSONObject().apply {
            put(
                "provenance",
                JSONObject().apply {
                    put("source", slice.provenance.source)
                    put("capturedAt", slice.provenance.capturedAt)
                    put("parserVersion", slice.provenance.parserVersion)
                    put("complete", slice.provenance.complete)
                    if (slice.provenance.incompleteReason != null) {
                        put("incompleteReason", slice.provenance.incompleteReason)
                    }
                    put("refreshState", slice.provenance.refreshState.name)
                }
            )
            if (slice.data != null) {
                put("data", serializeSnapshot(slice.data))
            }
            if (slice.lastError != null) {
                put("lastError", slice.lastError)
            }
        }
        val tmp = File(baseDir, "portal_calendar.snapshot.v1.json.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(root.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(snapshotFile)) {
            snapshotFile.delete()
            tmp.renameTo(snapshotFile)
        }
    }

    private fun serializeSnapshot(s: PortalCalendarSnapshot): JSONObject = JSONObject().apply {
        put("currentTermId", s.currentTermId)
        put("beginDate", s.beginDate)
        put("endDate", s.endDate)
        put("currentTeachingWeek", s.currentTeachingWeek)
        put(
            "terms",
            JSONArray().apply {
                for (t in s.terms) {
                    put(
                        JSONObject().apply {
                            put("wid", t.wid)
                            put("academicYear", t.academicYear)
                            put("semester", t.semester)
                            put("termId", t.termId)
                            put("beginDate", t.beginDate)
                            put("endDate", t.endDate)
                            put("isCurrent", t.isCurrent)
                        }
                    )
                }
            }
        )
        put(
            "holidays",
            JSONArray().apply {
                for (h in s.holidays) {
                    put(
                        JSONObject().apply {
                            put("date", h.date)
                            put("holidayName", h.holidayName)
                            put("isHoliday", h.isHoliday)
                            put("teachingWeek", h.teachingWeek)
                        }
                    )
                }
            }
        )
    }

    private fun deserializeSnapshot(o: JSONObject): PortalCalendarSnapshot {
        val terms = ArrayList<PortalTermInfo>()
        val termsArr = o.optJSONArray("terms")
        if (termsArr != null) {
            for (i in 0 until termsArr.length()) {
                val t = termsArr.optJSONObject(i) ?: continue
                terms.add(
                    PortalTermInfo(
                        wid = t.optString("wid"),
                        academicYear = t.optInt("academicYear", 0),
                        semester = t.optInt("semester", 1),
                        termId = t.optString("termId"),
                        beginDate = t.optString("beginDate"),
                        endDate = t.optString("endDate"),
                        isCurrent = t.optBoolean("isCurrent", false)
                    )
                )
            }
        }

        val holidays = ArrayList<PortalHolidayItem>()
        val holidaysArr = o.optJSONArray("holidays")
        if (holidaysArr != null) {
            for (i in 0 until holidaysArr.length()) {
                val h = holidaysArr.optJSONObject(i) ?: continue
                holidays.add(
                    PortalHolidayItem(
                        date = h.optString("date"),
                        holidayName = h.optString("holidayName"),
                        isHoliday = h.optBoolean("isHoliday", false),
                        teachingWeek = h.optInt("teachingWeek", 0)
                    )
                )
            }
        }

        return PortalCalendarSnapshot(
            currentTermId = o.optString("currentTermId"),
            beginDate = o.optString("beginDate"),
            endDate = o.optString("endDate"),
            currentTeachingWeek = o.optInt("currentTeachingWeek", 0),
            terms = terms,
            holidays = holidays
        )
    }

    companion object {
        private const val SOURCE_ID = "i.buct.edu.cn"
    }
}
