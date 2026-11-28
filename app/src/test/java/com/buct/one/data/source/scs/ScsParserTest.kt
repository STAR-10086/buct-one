package com.buct.one.data.source.scs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScsParserTest {

    @Test
    fun `parses getSelfEvalTerm response fields`() {
        val json = """
            {
              "code": 200,
              "data": {
                "open": true,
                "msg": "可以进行2026-2027第一学期的自评啦！",
                "yearId": 7,
                "termId": 31,
                "yearDesc": "2026-2027",
                "termDesc": "第一学期",
                "selfStartTime": "2026-10-24 00:00:00",
                "selfEndTime": "2027-01-01 00:00:00"
              }
            }
        """.trimIndent()

        val win = ScsParser.parseCurrentEvalWindow(json)
        assertNotNull(win)
        assertTrue(win!!.isOpen)
        assertEquals(7, win.yearId)
        assertEquals(31, win.termId)
        assertEquals("2026-2027", win.yearName)
        assertEquals("第一学期", win.termName)
        assertEquals("可以进行2026-2027第一学期的自评啦！", win.message)
    }

    @Test
    fun `parses getStudentPassScore category with optional scores and flags`() {
        val json = """
            {
              "code": 200,
              "data": {
                "typeId": 1,
                "desc": "道德与思想素质评定",
                "studentTypeScore": 20,
                "typeTotalScore": 180,
                "basic": [
                  {
                    "id": 442,
                    "typeId": 1,
                    "basic": true,
                    "shortDesc": "各类德育活动",
                    "description": "每参与一次计10分，上限50分",
                    "score": 20,
                    "optional": "[0,10,20,30,40,50]",
                    "itemMaxScore": 50,
                    "stuAble": true,
                    "reasonRequired": true,
                    "imageRequired": true,
                    "havePendApproval": true
                  }
                ],
                "ext": []
              }
            }
        """.trimIndent()

        val cat = ScsParser.parseStudentPassScoreCategory(json, 1)
        assertNotNull(cat)
        assertEquals(1, cat!!.items.size)
        val item = cat.items.first()
        assertEquals(442L, item.id)
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0), item.optionalScores)
        assertEquals(50.0, item.itemMaxScore!!, 0.001)
        assertTrue(item.reasonRequired)
        assertTrue(item.imageRequired)
        assertTrue(item.havePendApproval)
    }

    @Test
    fun `parses getStudentWebItemDetail and surfaces server 500 error message`() {
        val okJson = """
            {
              "code": 200,
              "data": {
                "itemScore": 10,
                "laborHoursApproved": 6,
                "requiredHours": 10,
                "singleSubmit": false,
                "laborHoursGate": { "blocked": false, "message": "" },
                "scoreItemDetail": {
                  "shortDesc": "德育活动",
                  "description": "10分/次",
                  "optional": "[0,10,20,30,40,50]",
                  "itemMaxScore": 50,
                  "reasonRequired": true,
                  "imageRequired": true
                },
                "studentDetails": [
                  {
                    "id": 9001,
                    "itemId": 442,
                    "score": 10,
                    "mark": "参加宏德讲坛",
                    "certificate": "[\"https://example.invalid/a.jpg\"]",
                    "auditStatus": "PEND_APPROVAL",
                    "canUpdate": true
                  }
                ]
              }
            }
        """.trimIndent()

        val detail = ScsParser.parseItemSelfEvalDetail(okJson, yearId = 7, termId = 31, itemId = 442L)
        assertEquals(1, detail.records.size)
        assertEquals("PEND_APPROVAL", detail.records.first().auditStatus)
        assertEquals(listOf("https://example.invalid/a.jpg"), detail.records.first().certificates)
        assertFalse(detail.laborHoursGateBlocked)

        val errJson = """{"code":500,"message":"系统异常，请联系开发人员","data":null}"""
        try {
            ScsParser.parseItemSelfEvalDetail(errJson, yearId = 7, termId = 31, itemId = 442L)
            fail("Expected exception on code=500")
        } catch (e: IllegalStateException) {
            assertEquals("系统异常，请联系开发人员", e.message)
        }
    }

    @Test
    fun `parses image upload url`() {
        val json = """{"code":200,"data":{"url":"https://scs.buct.edu.cn/files/proof.jpg"}}"""
        assertEquals("https://scs.buct.edu.cn/files/proof.jpg", ScsParser.parseUploadedImageUrl(json))
    }
}
