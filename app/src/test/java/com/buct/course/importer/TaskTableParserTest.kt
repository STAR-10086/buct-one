package com.buct.course.importer

import com.buct.one.data.model.ImportDiffStatus
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.TheolHomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TaskTableParserTest {

    @Test
    fun `parses standard csv template with BOM and multiline quotes`() {
        val sheets = XlsxSheetReader.readBytes(
            TaskTableParser.STANDARD_TEMPLATE_CSV.toByteArray(Charsets.UTF_8),
            "BUCT-One-模板.csv"
        )
        val report = TaskTableParser.parse(
            sheets = sheets,
            sourceName = "BUCT-One-模板.csv",
            nowMillis = 1L
        )
        assertEquals(3, report.tasks.size)
        assertEquals(1, report.exams.size)
        assertEquals("物理化学(Ⅰ)", report.tasks[0].cleanCourseName)
        assertEquals("企业微信班级群收集表", report.tasks[0].submitWay)
        assertTrue(report.tasks[1].warnings.any { it.contains("分班") })
        assertEquals("https://tygl.buct.edu.cn/login.php", report.tasks[2].linkUrl)
        assertEquals("实验楼F-304", report.exams[0].location)
    }

    @Test
    fun `parses multi-sheet xlsx with merged cells, serial dates and exam block`() {
        val xlsxBytes = buildSampleXlsx()
        val sheets = XlsxSheetReader.readXlsx(xlsxBytes)
        assertEquals(2, sheets.size)

        val nowEarly = TaskTableParser.parseDateTimeMillis("2026-11-23 12:00")!!
        val report = TaskTableParser.parse(
            sheets = sheets,
            sourceName = "第十二周任务.xlsx",
            existingTheolHomeworks = listOf(
                TheolHomeworkItem(
                    lid = "1",
                    courseName = "线性代数A",
                    hwtid = "hw1",
                    title = "第十次作业",
                    deadlineText = "2026-11-25 23:59",
                    deadlineMillis = TaskTableParser.parseDateTimeMillis("2026-11-25 23:59"),
                    submitted = false,
                    canSubmit = true
                )
            ),
            nowMillis = nowEarly
        )

        assertEquals(1, report.exams.size)
        assertEquals("新视野-英语听说（III）", report.exams[0].cleanCourseName)
        assertEquals("实验楼F-304", report.exams[0].location)

        // 线下学习(2条: 线代 + 物化) + 英语U校园AI(2条合并单元格) = 4条（占位课与底部链接应过滤）
        assertEquals(4, report.tasks.size)

        val linalg = report.tasks[0]
        assertEquals(ImportDiffStatus.THEOL_DUPLICATE, linalg.diffStatus)
        assertFalse(linalg.selectedByDefault)

        val phychem = report.tasks[1]
        assertEquals(ImportDiffStatus.NEW, phychem.diffStatus)
        assertTrue(phychem.selectedByDefault)
        assertNotNull(phychem.deadlineMillis)

        val u2 = report.tasks[3]
        assertEquals("英语听说（III）", u2.cleanCourseName)
        assertEquals("新视野U2", u2.title)
        assertEquals("U校园AI", u2.submitWay)
        assertEquals("截止时间为强制结束时间", u2.note)
    }

    @Test
    fun `detects already imported and updated tasks by importKey`() {
        val csv = """
            课程名称,任务内容,截止时间,提交方式
            物理化学(Ⅰ),《物理化学教程》p79 T17,2026-11-24 20:00,企业微信班级群收集表
            有机化学实验 B（I）,完善实验报告,2026-11-26 08:00,线下提交
        """.trimIndent()
        val sheets = listOf(XlsxSheetReader.readCsv(csv.toByteArray(Charsets.UTF_8), "Sheet1"))
        val first = TaskTableParser.parse(sheets, "week12.csv", nowMillis = 1L)
        assertEquals(2, first.tasks.size)

        val existing = listOf(
            LocalTodoItem(
                id = "1",
                title = first.tasks[0].title,
                courseName = first.tasks[0].cleanCourseName,
                deadlineMillis = first.tasks[0].deadlineMillis,
                submitWay = first.tasks[0].submitWay,
                importKey = first.tasks[0].importKey
            ),
            LocalTodoItem(
                id = "2",
                title = first.tasks[1].title,
                courseName = first.tasks[1].cleanCourseName,
                deadlineMillis = (first.tasks[1].deadlineMillis ?: 0L) - 3600_000L, // 旧 DDL
                submitWay = first.tasks[1].submitWay,
                importKey = first.tasks[1].importKey
            )
        )

        val second = TaskTableParser.parse(sheets, "week12.csv", existingTodos = existing, nowMillis = 1L)
        assertEquals(ImportDiffStatus.ALREADY_IMPORTED, second.tasks[0].diffStatus)
        assertFalse(second.tasks[0].selectedByDefault)

        assertEquals(ImportDiffStatus.UPDATE_AVAILABLE, second.tasks[1].diffStatus)
        assertTrue(second.tasks[1].selectedByDefault)
    }

    private fun buildSampleXlsx(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun put(name: String, xml: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put(
                "xl/workbook.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="线下学习" sheetId="1" r:id="rId1"/>
                    <sheet name="英语U校园AI" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>
                """.trimIndent()
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                </Relationships>
                """.trimIndent()
            )
            // 46351.9993055556 -> 2026-11-25 23:59
            put(
                "xl/worksheets/sheet1.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>考试</t></is></c></row>
                    <row r="2">
                      <c r="A2" t="inlineStr"><is><t>课程名称</t></is></c>
                      <c r="B2" t="inlineStr"><is><t>考试时间</t></is></c>
                      <c r="C2" t="inlineStr"><is><t>考试地点</t></is></c>
                    </row>
                    <row r="3">
                      <c r="A3" t="inlineStr"><is><t>新视野-英语听说（III）●</t></is></c>
                      <c r="B3" t="inlineStr"><is><t>2026/12/16 13:30-**:**</t></is></c>
                      <c r="C3" t="inlineStr"><is><t>实验楼F-304</t></is></c>
                    </row>
                    <row r="5">
                      <c r="A5" t="inlineStr"><is><t>课程名称</t></is></c>
                      <c r="B5" t="inlineStr"><is><t>任务描述</t></is></c>
                      <c r="C5" t="inlineStr"><is><t>截止日期</t></is></c>
                      <c r="D5" t="inlineStr"><is><t>剩余时间</t></is></c>
                      <c r="E5" t="inlineStr"><is><t>提交方式</t></is></c>
                    </row>
                    <row r="6">
                      <c r="A6" t="inlineStr"><is><t>线性代数A★</t></is></c>
                      <c r="B6" t="inlineStr"><is><t>第十次作业</t></is></c>
                      <c r="C6"><v>46351.9993055556</v></c>
                      <c r="D6"><v>1.2</v></c>
                      <c r="E6" t="inlineStr"><is><t>北化在线</t></is></c>
                    </row>
                    <row r="7">
                      <c r="A7" t="inlineStr"><is><t>物理化学(Ⅰ)★</t></is></c>
                      <c r="B7" t="inlineStr"><is><t>p79 T17</t></is></c>
                      <c r="C7"><v>46350.8333333333</v></c>
                      <c r="E7" t="inlineStr"><is><t>企业微信班级群收集表</t></is></c>
                    </row>
                    <row r="8">
                      <c r="A8" t="inlineStr"><is><t>实验室技术安全与管理★</t></is></c>
                    </row>
                    <row r="9">
                      <c r="A9" t="inlineStr"><is><t>单击可访问北化在线</t></is></c>
                    </row>
                  </sheetData>
                </worksheet>
                """.trimIndent()
            )
            put(
                "xl/worksheets/sheet2.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="inlineStr"><is><t>课程名称</t></is></c>
                      <c r="B1" t="inlineStr"><is><t>任务描述</t></is></c>
                      <c r="C1" t="inlineStr"><is><t>截止日期</t></is></c>
                      <c r="D1" t="inlineStr"><is><t>备注</t></is></c>
                    </row>
                    <row r="2">
                      <c r="A2" t="inlineStr"><is><t>英语听说（III）●</t></is></c>
                      <c r="B2" t="inlineStr"><is><t>新视野U1</t></is></c>
                      <c r="C2"><v>46376.9999884259</v></c>
                      <c r="D2" t="inlineStr"><is><t>截止时间为强制结束时间</t></is></c>
                    </row>
                    <row r="3">
                      <c r="B3" t="inlineStr"><is><t>新视野U2</t></is></c>
                      <c r="C3"><v>46376.9999884259</v></c>
                    </row>
                  </sheetData>
                  <mergeCells count="2">
                    <mergeCell ref="A2:A3"/>
                    <mergeCell ref="D2:D3"/>
                  </mergeCells>
                </worksheet>
                """.trimIndent()
            )
        }
        return baos.toByteArray()
    }
}
