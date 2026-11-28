package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.ScsManualDimensionRule
import com.buct.one.data.model.ScsManualEntry
import com.buct.one.data.model.ScsManualStoreData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 第二课堂（五育并举）本地自填估分存储。
 *
 * 独立存储于 `campus/default/scs.manual.v1.json`，与官方快照 [ScsStore] 完全物理隔离：
 * 官方刷新成功或失败绝不覆盖、丢失用户平时手动记下的活动分值与备忘。
 */
class ScsManualStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "campus/default").apply {
        if (!exists()) mkdirs()
    }

    private val storeFile = File(baseDir, "scs.manual.v1.json")

    @Synchronized
    fun read(): ScsManualStoreData {
        if (!storeFile.exists()) return ScsManualStoreData()
        return runCatching {
            val root = JSONObject(storeFile.readText(Charsets.UTF_8))
            val entriesArr = root.optJSONArray("entries") ?: JSONArray()
            val entries = buildList(entriesArr.length()) {
                for (i in 0 until entriesArr.length()) {
                    val o = entriesArr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    val linkedId = if (o.has("linkedItemId") && !o.isNull("linkedItemId")) {
                        o.optLong("linkedItemId", -1L).takeIf { it >= 0L }
                    } else null
                    val linkedMax = if (o.has("linkedItemMaxScore") && !o.isNull("linkedItemMaxScore")) {
                        o.optDouble("linkedItemMaxScore", Double.NaN).takeIf { !it.isNaN() }
                    } else null
                    add(
                        ScsManualEntry(
                            id = id,
                            termKey = o.optString("termKey", DEFAULT_TERM_KEY).ifBlank { DEFAULT_TERM_KEY },
                            typeId = o.optInt("typeId", 1).coerceIn(1, 5),
                            isBasic = o.optBoolean("isBasic", true),
                            title = o.optString("title", ""),
                            score = o.optDouble("score", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                            laborHours = o.optDouble("laborHours", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                            linkedItemId = linkedId,
                            linkedItemMaxScore = linkedMax,
                            linkedItemShortDesc = o.optString("linkedItemShortDesc", ""),
                            dateText = o.optString("dateText", ""),
                            note = o.optString("note", ""),
                            enabled = o.optBoolean("enabled", true),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }

            val rulesArr = root.optJSONArray("customRules") ?: JSONArray()
            val customRules = buildList(rulesArr.length()) {
                for (i in 0 until rulesArr.length()) {
                    val r = rulesArr.optJSONObject(i) ?: continue
                    add(
                        ScsManualDimensionRule(
                            typeId = r.optInt("typeId", i + 1).coerceIn(1, 5),
                            shortLabel = r.optString("shortLabel", ""),
                            fullName = r.optString("fullName", ""),
                            totalMax = r.optDouble("totalMax", 100.0).takeIf { !it.isNaN() } ?: 100.0,
                            basicMax = r.optDouble("basicMax", 80.0).takeIf { !it.isNaN() } ?: 80.0,
                            extMax = r.optDouble("extMax", 20.0).takeIf { !it.isNaN() } ?: 20.0
                        )
                    )
                }
            }

            ScsManualStoreData(
                entries = entries,
                includeOfficialBaseline = root.optBoolean("includeOfficialBaseline", true),
                customRules = customRules
            )
        }.getOrElse {
            ScsManualStoreData()
        }
    }

    @Synchronized
    fun upsertEntry(entry: ScsManualEntry): ScsManualStoreData {
        val current = read()
        val list = current.entries.toMutableList()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            list[idx] = entry
        } else {
            list.add(0, entry)
        }
        val next = current.copy(entries = list.sortedByDescending { it.createdAt })
        write(next)
        return next
    }

    @Synchronized
    fun deleteEntry(entryId: String): ScsManualStoreData {
        val current = read()
        val next = current.copy(entries = current.entries.filterNot { it.id == entryId })
        write(next)
        return next
    }

    @Synchronized
    fun toggleEntry(entryId: String): ScsManualStoreData {
        val current = read()
        val next = current.copy(
            entries = current.entries.map { if (it.id == entryId) it.copy(enabled = !it.enabled) else it }
        )
        write(next)
        return next
    }

    @Synchronized
    fun setIncludeOfficialBaseline(include: Boolean): ScsManualStoreData {
        val current = read()
        val next = current.copy(includeOfficialBaseline = include)
        write(next)
        return next
    }

    @Synchronized
    fun saveCustomRules(rules: List<ScsManualDimensionRule>): ScsManualStoreData {
        val current = read()
        val next = current.copy(customRules = rules)
        write(next)
        return next
    }

    @Synchronized
    fun clearTermEntries(termKey: String): ScsManualStoreData {
        val current = read()
        val next = current.copy(entries = current.entries.filterNot { it.termKey == termKey })
        write(next)
        return next
    }

    private fun write(data: ScsManualStoreData) {
        val root = JSONObject().apply {
            put("includeOfficialBaseline", data.includeOfficialBaseline)
            put(
                "entries",
                JSONArray().apply {
                    for (e in data.entries) {
                        put(
                            JSONObject().apply {
                                put("id", e.id)
                                put("termKey", e.termKey)
                                put("typeId", e.typeId)
                                put("isBasic", e.isBasic)
                                put("title", e.title)
                                put("score", e.score)
                                put("laborHours", e.laborHours)
                                e.linkedItemId?.let { put("linkedItemId", it) }
                                e.linkedItemMaxScore?.takeIf { !it.isNaN() }?.let { put("linkedItemMaxScore", it) }
                                put("linkedItemShortDesc", e.linkedItemShortDesc)
                                put("dateText", e.dateText)
                                put("note", e.note)
                                put("enabled", e.enabled)
                                put("createdAt", e.createdAt)
                            }
                        )
                    }
                }
            )
            put(
                "customRules",
                JSONArray().apply {
                    for (r in data.customRules) {
                        put(
                            JSONObject().apply {
                                put("typeId", r.typeId)
                                put("shortLabel", r.shortLabel)
                                put("fullName", r.fullName)
                                put("totalMax", r.totalMax)
                                put("basicMax", r.basicMax)
                                put("extMax", r.extMax)
                            }
                        )
                    }
                }
            )
        }
        val tmp = File(baseDir, "scs.manual.v1.json.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(root.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(storeFile)) {
            storeFile.delete()
            tmp.renameTo(storeFile)
        }
    }

    companion object {
        const val DEFAULT_TERM_KEY = "current"
    }
}
