package com.buct.course.importer

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

data class ParsedSheetTable(
    val name: String,
    val rows: List<List<String>>,
    val hyperlinks: Map<Pair<Int, Int>, String> = emptyMap()
)

/**
 * 纯 Kotlin 零第三方依赖表格读取器：
 * - 支持 `.xlsx`（多 Sheet、共享字符串、inlineStr、合并单元格 `<mergeCells>` 展开、`<hyperlink>` 提取）
 * - 支持 `.csv`（UTF-8 BOM / UTF-8 / GBK 自动识别，兼容多行引号单元格）
 */
object XlsxSheetReader {

    private val cellRefRegex = Regex("^([A-Za-z]+)(\\d+)$")

    fun readBytes(bytes: ByteArray, fileName: String): List<ParsedSheetTable> {
        val lower = fileName.lowercase()
        return if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt")) {
            listOf(readCsv(bytes, fileName.substringBeforeLast('.')))
        } else {
            val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            if (isZip) readXlsx(bytes) else listOf(readCsv(bytes, fileName.substringBeforeLast('.')))
        }
    }

    fun readXlsx(bytes: ByteArray): List<ParsedSheetTable> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name.removePrefix("/")] = zis.readBytes()
                }
                zis.closeEntry()
            }
        }

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val workbookBytes = entries["xl/workbook.xml"]
            ?: throw IllegalArgumentException("无效的 .xlsx 文件：缺少 xl/workbook.xml")
        val workbookRels = entries["xl/_rels/workbook.xml.rels"]?.let(::parseRelationships).orEmpty()

        val wbDoc = parseXml(workbookBytes)
        val sheetNodes = wbDoc.getElementsByTagName("sheet")
        val result = mutableListOf<ParsedSheetTable>()

        for (i in 0 until sheetNodes.length) {
            val el = sheetNodes.item(i) as? Element ?: continue
            val sheetName = el.getAttribute("name").ifBlank { "Sheet${i + 1}" }
            val relId = el.getAttribute("r:id").ifBlank {
                el.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            }
            val rawTarget = workbookRels[relId] ?: "worksheets/sheet${i + 1}.xml"
            val normalizedTarget = rawTarget.removePrefix("/").let {
                if (it.startsWith("xl/")) it else "xl/$it"
            }
            val sheetBytes = entries[normalizedTarget] ?: continue
            val sheetFileName = normalizedTarget.substringAfterLast('/')
            val relsPath = "xl/worksheets/_rels/$sheetFileName.rels"
            val sheetRels = entries[relsPath]?.let(::parseRelationships).orEmpty()
            result += parseWorksheet(sheetName, sheetBytes, sharedStrings, sheetRels)
        }
        return result
    }

    fun readCsv(bytes: ByteArray, sheetName: String = "Sheet1"): ParsedSheetTable {
        val text = decodeCsvText(bytes)
        val delimiter = detectDelimiter(text)
        val rows = parseDelimitedRows(text, delimiter)
        return ParsedSheetTable(name = sheetName.ifBlank { "Sheet1" }, rows = rows)
    }

    fun parseExcelSerialToMillis(serial: Double, timeZone: TimeZone = TimeZone.getDefault()): Long? {
        if (!serial.isFinite() || serial < 20000.0 || serial > 100000.0) return null
        val wholeDays = serial.toInt()
        val fraction = serial - wholeDays
        val millisOfDay = (fraction * 86_400_000.0).roundToLong()

        val cal = Calendar.getInstance(timeZone).apply {
            clear()
            // Excel 1900 纪元含虚构的 1900-02-29，1899-12-30 加天数即可对齐 1900-03-01 之后的所有日期
            set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, wholeDays)
            add(Calendar.MILLISECOND, millisOfDay.toInt())
        }
        return cal.timeInMillis
    }

    private fun parseXml(bytes: ByteArray) =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = parseXml(bytes)
        val sis = doc.getElementsByTagName("si")
        val list = ArrayList<String>(sis.length)
        for (i in 0 until sis.length) {
            val si = sis.item(i) as? Element ?: continue
            val ts = si.getElementsByTagName("t")
            val sb = StringBuilder()
            for (j in 0 until ts.length) {
                sb.append(ts.item(j)?.textContent.orEmpty())
            }
            list += sb.toString()
        }
        return list
    }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val doc = parseXml(bytes)
        val nodes = doc.getElementsByTagName("Relationship")
        val map = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val id = el.getAttribute("Id")
            val target = el.getAttribute("Target")
            if (id.isNotBlank() && target.isNotBlank()) {
                map[id] = target
            }
        }
        return map
    }

    private fun parseWorksheet(
        sheetName: String,
        bytes: ByteArray,
        sharedStrings: List<String>,
        sheetRels: Map<String, String>
    ): ParsedSheetTable {
        val doc = parseXml(bytes)
        val cells = LinkedHashMap<Pair<Int, Int>, String>()
        var maxRow = -1
        var maxCol = -1

        val cellNodes = doc.getElementsByTagName("c")
        for (i in 0 until cellNodes.length) {
            val c = cellNodes.item(i) as? Element ?: continue
            val ref = c.getAttribute("r")
            val pos = parseCellRef(ref) ?: continue
            val (rowIdx, colIdx) = pos
            val type = c.getAttribute("t")
            val value = when (type) {
                "s" -> {
                    val v = firstChildText(c, "v")?.trim()?.toIntOrNull()
                    if (v != null && v in sharedStrings.indices) sharedStrings[v] else ""
                }
                "inlineStr" -> {
                    val isNode = firstChildElement(c, "is")
                    if (isNode != null) {
                        val ts = isNode.getElementsByTagName("t")
                        buildString {
                            for (j in 0 until ts.length) append(ts.item(j)?.textContent.orEmpty())
                        }
                    } else ""
                }
                "str", "b", "e" -> firstChildText(c, "v").orEmpty()
                else -> firstChildText(c, "v").orEmpty()
            }
            if (value.isNotEmpty()) {
                cells[rowIdx to colIdx] = value
                if (rowIdx > maxRow) maxRow = rowIdx
                if (colIdx > maxCol) maxCol = colIdx
            }
        }

        // 展开合并单元格（将左上角内容铺开到合并区域内的空单元格）
        val mergeNodes = doc.getElementsByTagName("mergeCell")
        for (i in 0 until mergeNodes.length) {
            val el = mergeNodes.item(i) as? Element ?: continue
            val range = el.getAttribute("ref")
            val parts = range.split(':')
            if (parts.size != 2) continue
            val start = parseCellRef(parts[0]) ?: continue
            val end = parseCellRef(parts[1]) ?: continue
            val anchorVal = cells[start.first to start.second].orEmpty()
            if (anchorVal.isEmpty()) continue
            for (r in start.first..end.first) {
                for (col in start.second..end.second) {
                    if (cells[r to col].isNullOrEmpty()) {
                        cells[r to col] = anchorVal
                    }
                    if (r > maxRow) maxRow = r
                    if (col > maxCol) maxCol = col
                }
            }
        }

        // 提取单元格超链接
        val hyperlinks = LinkedHashMap<Pair<Int, Int>, String>()
        val linkNodes = doc.getElementsByTagName("hyperlink")
        for (i in 0 until linkNodes.length) {
            val el = linkNodes.item(i) as? Element ?: continue
            val ref = el.getAttribute("ref")
            val pos = parseCellRef(ref) ?: continue
            val relId = el.getAttribute("r:id").ifBlank {
                el.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            }
            val url = sheetRels[relId].orEmpty()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                hyperlinks[pos] = url
            }
        }

        if (maxRow < 0 || maxCol < 0) {
            return ParsedSheetTable(sheetName, emptyList(), hyperlinks)
        }

        val rows = List(maxRow + 1) { r ->
            List(maxCol + 1) { c -> cells[r to c].orEmpty() }
        }
        return ParsedSheetTable(sheetName, rows, hyperlinks)
    }

    private fun firstChildElement(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.localName == localName || node.tagName == localName) return node
        }
        return null
    }

    private fun firstChildText(parent: Element, localName: String): String? =
        firstChildElement(parent, localName)?.textContent

    private fun parseCellRef(ref: String): Pair<Int, Int>? {
        val match = cellRefRegex.matchEntire(ref.trim()) ?: return null
        val colLetters = match.groupValues[1].uppercase()
        val rowNum = match.groupValues[2].toIntOrNull() ?: return null
        var col = 0
        for (ch in colLetters) {
            col = col * 26 + (ch - 'A' + 1)
        }
        return (rowNum - 1) to (col - 1)
    }

    private fun decodeCsvText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            runCatching { String(bytes, Charset.forName("GB18030")) }.getOrDefault(utf8)
        } else {
            utf8
        }
    }

    private fun detectDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val tabs = firstLine.count { it == '\t' }
        val commas = firstLine.count { it == ',' }
        return if (tabs > commas) '\t' else ','
    }

    private fun parseDelimitedRows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    delimiter -> {
                        currentRow += cell.toString()
                        cell.clear()
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        currentRow += cell.toString()
                        cell.clear()
                        rows += currentRow.toList()
                        currentRow.clear()
                    }
                    '\n' -> {
                        currentRow += cell.toString()
                        cell.clear()
                        rows += currentRow.toList()
                        currentRow.clear()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += cell.toString()
            rows += currentRow.toList()
        }
        return rows
    }
}
