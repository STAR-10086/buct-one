package com.buct.one.data.source.theol

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheolParserResourceTest {

    @Test
    fun discoverResourceEntryUrls_matchesTextUrlAndOnclick() {
        val baseUrl = "https://course.buct.edu.cn/meol/jpk/course/layout/newpage/index.jsp?courseId=12345"
        val html = """
            <html><body>
              <a href="#">忽略锚点</a>
              <a href="javascript:void(0)">忽略JS</a>
              <a href="../../course_column_preview_transfer.jsp?tagbug=client&columnId=99"><span>课程资源</span></a>
              <a href="javascript:void(0)" onclick="openCol('/meol/jpk/course/course_column_preview_transfer.jsp?columnId=100')">教学课件</a>
              <a href="/meol/common/hw/student/hwtask.jsp">课程作业</a>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, baseUrl)
        val urls = TheolParser.discoverResourceEntryUrls(doc, baseUrl)
        assertEquals(
            listOf(
                "https://course.buct.edu.cn/meol/jpk/course/course_column_preview_transfer.jsp?tagbug=client&columnId=99",
                "https://course.buct.edu.cn/meol/jpk/course/course_column_preview_transfer.jsp?columnId=100"
            ),
            urls
        )
    }

    @Test
    fun extractResourceRedirectUrl_prefersMainFrameListviewAndSkipsLeftFrame() {
        val baseUrl = "https://course.buct.edu.cn/meol/common/script/courseResource.jsp?tagbug=client&folderid=0&lid=12345&groupid=4"
        val framesetHtml = """
            <html>
              <frameset rows="*" cols="20%,*">
                <frame src="left.jsp?lid=12345&groupid=4" name="leftFrame" id="leftFrame"></frame>
                <frame src="listview.jsp?groupid=4&lid=12345&folderid=0" name="mainFrame" id="mainFrame"></frame>
              </frameset>
            </html>
        """.trimIndent()
        assertEquals(
            "https://course.buct.edu.cn/meol/common/script/listview.jsp?groupid=4&lid=12345&folderid=0",
            TheolParser.extractResourceRedirectUrl(Jsoup.parse(framesetHtml, baseUrl), baseUrl)
        )

        val transferUrl = "https://course.buct.edu.cn/meol/jpk/course/course_column_preview_transfer.jsp?columnId=99"
        val jsHtml = """
            <html><head><script>
              window.location.href = '/meol/common/script/listview.jsp?lid=12345&folderid=88';
            </script></head></html>
        """.trimIndent()
        assertEquals(
            "https://course.buct.edu.cn/meol/common/script/listview.jsp?lid=12345&folderid=88",
            TheolParser.extractResourceRedirectUrl(Jsoup.parse(jsHtml, transferUrl), transferUrl)
        )
    }

    @Test
    fun parseResourceList_handlesLiveListviewDomRewritesPreviewAndInfersIconExt() {
        val pageUrl = "https://course.buct.edu.cn/meol/common/script/listview.jsp?acttype=enter&folderid=202&lid=12345"
        val html = """
            <html><body>
              <div class="wrap">
                <div class="subtitle">
                  <span>课程资源</span>
                  <span class="subright">
                    <a href="listview.jsp?acttype=enter&folderid=0&lid=12345">返回上一级目录</a>
                    <a href="search.jsp?folderid=202&lid=12345&groupid=4">查询</a>
                  </span>
                </div>
                <table cellspacing="0" cellpadding="0" class="valuelist">
                  <tr class="table-margin-alternation-td">
                    <td class="common indentten">
                      <img alt="" src="/meol/common/icons/powerpoint.gif">&nbsp;
                      <a href="preview/download_preview.jsp?fileid=101&resid=501&lid=12345" target="_blank">第1章 绪论</a>
                    </td>
                    <td class="common align_c"><div align="center">34</div></td>
                    <td class="common align_c"><div align="center">0</div></td>
                    <td class="common align_c">课件</td>
                    <td class="align_c"><a href="###" class="search"></a></td>
                  </tr>
                  <tr class="even">
                    <td class="common indentten" colspan="4">
                      <img alt="" src="./styles/default/image/folder.gif">&nbsp;
                      <a href="listview.jsp?acttype=enter&folderid=303&lid=12345">实验指导书</a>
                    </td>
                    <td class="align_c"><a href="###" class="search"></a></td>
                  </tr>
                </table>
              </div>
            </body></html>
        """.trimIndent()

        val page = TheolParser.parseResourceList(
            html = html,
            pageUrl = pageUrl,
            lid = "12345",
            courseName = "高等数学",
            fallbackFolderTitle = "第一章"
        )
        assertEquals("第一章", page.folderTitle)
        assertEquals(2, page.items.size)

        val folder = page.items[0]
        assertTrue(folder.isFolder)
        assertEquals("实验指导书", folder.name)
        assertEquals("https://course.buct.edu.cn/meol/common/script/listview.jsp?acttype=enter&folderid=303&lid=12345", folder.url)

        val file = page.items[1]
        assertFalse(file.isFolder)
        assertEquals("第1章 绪论", file.name)
        assertEquals("https://course.buct.edu.cn/meol/common/script/download.jsp?fileid=101&resid=501&lid=12345", file.url)
        assertEquals("https://course.buct.edu.cn/meol/common/script/preview/download_preview.jsp?fileid=101&resid=501&lid=12345", file.previewUrl)
        assertEquals("课件", file.sizeText)
        assertEquals("ppt", file.fileExt)
    }

    @Test
    fun parseCourseUnits_extractsUnitsFromMenuAdvanceAndFallback() {
        val homeUrl = "https://course.buct.edu.cn/meol/jpk/course/layout/newpage/index.jsp?courseId=11034"
        val html = """
            <html><body>
              <div id="menu" class="wrap-menu">
                <ul id="ul_advance" style="display: block;">
                  <li><a href="../../course_column_preview_transfer.jsp?tagbug=client&columnId=88981"><span>引言 静力学公理</span></a></li>
                  <li><a href="../../course_column_preview_transfer.jsp?tagbug=client&columnId=312709"><span>受力分析</span></a></li>
                  <li><a href="../../course_column_preview_transfer.jsp?tagbug=client&columnId=88981"><span>重复节点</span></a></li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()
        val units = TheolParser.parseCourseUnits(html, homeUrl)
        assertEquals(2, units.size)
        assertEquals("88981", units[0].columnId)
        assertEquals("引言 静力学公理", units[0].title)
        assertEquals("https://course.buct.edu.cn/meol/jpk/course/course_column_preview_transfer.jsp?tagbug=client&columnId=88981", units[0].url)
        assertEquals("312709", units[1].columnId)
        assertEquals("受力分析", units[1].title)

        val fallbackHtml = """
            <html><body>
              <a onclick="menuUrl('1','25843','单元学习','1',$(this))" href="../../course_column_preview_transfer.jsp?columnId=88981">单元学习</a>
              <div id="menu" class="wrap-menu">
                <ul class="ul_25843">
                  <li><a href="../../course_column_preview_transfer.jsp?columnId=99001"><span>第一章</span></a></li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()
        val fallbackUnits = TheolParser.parseCourseUnits(fallbackHtml, homeUrl)
        assertEquals(1, fallbackUnits.size)
        assertEquals("99001", fallbackUnits[0].columnId)
        assertEquals("第一章", fallbackUnits[0].title)
    }

    @Test
    fun parseResourceList_handlesUnitVariantBInlinePreviewPage() {
        val pageUrl = "https://course.buct.edu.cn/meol/buildless/resFolderViewList.do?folderid=137089&lid=11034&columnId=88981"
        val html = """
            <html><body>
              <div class="wrap">
                <div id="dowload-preview">
                  <div class="h1-title">
                    <h1>引言，静力学公理</h1>
                    <h2>
                      文件名:引言，静力学公理.pdf
                      <span>(4.3M)</span>
                      <a class="icon32 lv-download" href="/meol/common/script/download.jsp?fileid=1355846&amp;resid=137089">下载</a>
                    </h2>
                  </div>
                </div>
                <div class="bodyer">
                  <iframe id="pdfIframe" src="/meol/analytics/resPdfShow.do?lid=11034&amp;resId=137089"></iframe>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val page = TheolParser.parseResourceList(html, pageUrl, "11034", "理论力学", fallbackFolderTitle = "引言 静力学公理")
        assertEquals("引言 静力学公理", page.folderTitle)
        assertEquals(1, page.items.size)
        val item = page.items[0]
        assertFalse(item.isFolder)
        assertEquals("引言，静力学公理.pdf", item.name)
        assertEquals("4.3M", item.sizeText)
        assertEquals("pdf", item.fileExt)
        assertEquals("https://course.buct.edu.cn/meol/common/script/download.jsp?fileid=1355846&resid=137089&lid=11034", item.url)
        assertEquals(pageUrl, item.previewUrl)
    }

    @Test
    fun parseResourceList_emptyValuelistDoesNotFallbackToHeaderNavLinks() {
        val pageUrl = "https://course.buct.edu.cn/meol/common/script/listview.jsp?acttype=enter&folderid=202&lid=12345"
        val html = """
            <html><body>
              <div class="subtitle">
                <span>课程资源</span>
                <span class="subright">
                  <a href="listview.jsp?acttype=enter&folderid=0&lid=12345">返回上一级目录</a>
                  <a href="search.jsp?folderid=202&lid=12345&groupid=4">查询</a>
                </span>
              </div>
              <table cellspacing="0" cellpadding="0" class="valuelist"></table>
            </body></html>
        """.trimIndent()

        val page = TheolParser.parseResourceList(html, pageUrl, "12345", "高等数学")
        assertEquals("课程资源", page.folderTitle)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun extractDirectDownloadUrl_extractsLivePreviewDownloadAndRejectsExternalHost() {
        val pageUrl = "https://course.buct.edu.cn/meol/common/script/preview/download_preview.jsp?fileid=101&resid=501&lid=12345"
        val trustedHtml = """
            <html><body>
              <div id="dowload-preview">
                <div class="h1-title">
                  <h2>
                    <p>文件名:<span>课件.pptx</span>
                      <span class="right">(32.9M)</span>
                      <a class="icon32 lv-download" href="/meol/common/script/download.jsp?fileid=101&amp;resid=501&amp;lid=12345">下载</a>
                    </p>
                  </h2>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://course.buct.edu.cn/meol/common/script/download.jsp?fileid=101&resid=501&lid=12345",
            TheolParser.extractDirectDownloadUrl(trustedHtml, pageUrl)
        )

        val untrustedHtml = """
            <html><body>
              <a class="icon32 lv-download" href="https://evil.example.com/download.jsp?fileid=101">下载</a>
            </body></html>
        """.trimIndent()
        assertNull(TheolParser.extractDirectDownloadUrl(untrustedHtml, pageUrl))
    }
}
