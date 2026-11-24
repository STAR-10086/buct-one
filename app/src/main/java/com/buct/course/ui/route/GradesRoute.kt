package com.buct.course.ui.route

import com.buct.course.ui.system.GlassToaster
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.buct.course.ui.system.rememberPageData
import androidx.compose.ui.platform.LocalContext
import com.buct.course.demo.DemoData
import com.buct.course.manager.UserManager
import com.buct.course.manager.SessionToken
import com.buct.course.manager.SessionRequestTicket
import com.buct.course.manager.SessionRequestGate
import com.buct.course.model.SchoolConfig
import com.buct.course.network.CourseApiClient
import com.buct.course.ui.screen.CurriculumCourseUi
import com.buct.course.ui.screen.CurriculumNodeUi
import com.buct.course.ui.screen.ExamItemUi
import com.buct.course.ui.screen.GradeBucket
import com.buct.course.ui.screen.GradeItemUi
import com.buct.course.ui.screen.GradesScreen
import com.buct.course.ui.screen.OverallStatsUi
import com.buct.course.ui.screen.classifyGradeBucket
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Calendar
import java.util.regex.Pattern

@Composable
fun GradesRoute() {
    val academicSchool = UserManager.getInstance().currentSchool
    if (!UserManager.getInstance().isDemoMode && academicSchool != null && com.buct.course.academic.AcademicGatewayFactory.supports(academicSchool)) {
        AcademicGradesRoute(academicSchool)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val requests = remember { SessionRequestGate(sessions) }
    DisposableEffect(requests) { onDispose { requests.cancelAll() } }
    
    // State
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var currentSemester by rememberSaveable { mutableStateOf("") }
    
    var semesterGrades by rememberPageData<List<GradeItemUi>>("grades.semester.$currentSemester") { emptyList() }
    var semesterLoaded by rememberPageData("grades.semester.loaded.$currentSemester") { false }
    var semesters by remember { mutableStateOf<List<String>>(emptyList()) }
    var semesterIsLoading by remember { mutableStateOf(false) }

    var overallGrades by rememberPageData<List<GradeItemUi>>("grades.overall") { emptyList() }
    var overallStats by rememberPageData("grades.stats") { OverallStatsUi("0.0", "0", 0, 0, 0, 0, 0) }
    var overallLoaded by rememberPageData("grades.overall.loaded") { false }
    var overallIsLoading by remember { mutableStateOf(false) }
    // 官方绩点（N105515 学业情况页 GPA）：学期「全部」页直接展示官方口径，避免合并接口绩点字段差异
    var officialOverallGpa by remember { mutableStateOf("") }

    // 培养方案状态（N105515 树结构 + 各模块课程明细）
    var curriculumNodes by rememberPageData<List<CurriculumNodeUi>>("grades.curriculum") { emptyList() }
    var curriculumLoaded by rememberPageData("grades.curriculum.loaded") { false }
    var curriculumIsLoading by remember { mutableStateOf(false) }

    // 考试安排状态（考试学期独立选择，缓存按学期）
    var examSemester by rememberSaveable { mutableStateOf("") }
    var examList by rememberPageData<List<ExamItemUi>>("grades.exams.$examSemester") { emptyList() }
    var examsLoaded by rememberPageData("grades.exams.loaded.$examSemester") { false }
    var examIsLoading by remember { mutableStateOf(false) }

    // Init semesters
    LaunchedEffect(Unit) {
        val list = if (isDemoMode) {
            listOf("2025-2026-2", "2025-2026-1", "2024-2025-2", "2024-2025-1")
        } else {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val startYear = if (month >= 7) year else year - 1
            buildList {
                for (y in startYear downTo startYear - 3) {
                    add("$y-${y + 1}-1")
                    add("$y-${y + 1}-2")
                    add("$y-${y + 1}-3")
                }
            }
        }
        semesters = listOf("全部") + list
        if (list.isNotEmpty() && currentSemester !in semesters) currentSemester = list.firstOrNull { it != "全部" } ?: "全部"
        if (list.isNotEmpty() && examSemester !in semesters) examSemester = list.firstOrNull { it != "全部" } ?: "全部"
    }

    // Handlers
    fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    fun isCurrentAccount(accountKey: SessionRequestTicket): Boolean {
        return requests.isCurrent(accountKey)
    }

    fun runOnUiThreadForAccount(accountKey: SessionRequestTicket, action: () -> Unit) {
        runOnUiThread {
            if (isCurrentAccount(accountKey)) action()
        }
    }

    fun isLoginPageHtml(html: String): Boolean {
        return html.contains("用户登录") || html.contains("登 录") ||
               html.contains("slogin.html") || html.contains("id=\"pwd\"") ||
               html.contains("name=\"yhm\"") || html.contains("notLogin")
    }

    // "全部"通道：循环所有学期拉取 N305007 成绩并合并（与教务「成绩明细-学年/学期=全部」一致，仅保留有成绩课程）
    fun fetchAllSemestersGrades(school: SchoolConfig, onDone: (List<GradeItemUi>) -> Unit) {
        val sems = semesters.filter { it != "全部" }
        var semIdx = 0
        val merged = mutableListOf<GradeItemUi>()
        fun nextSemester() {
            if (semIdx >= sems.size) { onDone(merged); return }
            val semester = sems[semIdx]
            semIdx++
            CourseApiClient.getInstance().fetchGrades(school, semester, object : Callback {
                override fun onFailure(call: Call, e: IOException) { nextSemester() }
                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    if (isLoginPageHtml(json)) { nextSemester(); return }
                    val items = GradesLogic.parseGradesJson(json).filter {
                        val t = it.grade.trim(); t.isNotEmpty() && t != "--" && t != "null"
                    }
                    val y = semester.substringBefore("-")
                    val t = semester.substringAfterLast("-")
                    merged.addAll(items.map { it.copy(year = y, term = t) })
                    nextSemester()
                }
            })
        }
        nextSemester()
    }

    fun shouldFetchComponentDetail(item: GradeItemUi): Boolean {
        if (item.detail.isNotBlank() || item.year.isBlank() || item.examNature.contains("补考")) return false
        val g = item.grade.trim()
        if (g.isEmpty() || g == "--" || g.equals("null", ignoreCase = true)) return false
        if (g == "合格" || g == "不合格" || g == "通过" || g == "不通过" ||
            g == "缺考" || g == "缓考" || g == "免修" ||
            g.equals("P", ignoreCase = true) || g.equals("U", ignoreCase = true)
        ) return false
        return true
    }

    // 对已合并列表逐课补拉完整占比分项（平时(60%) 97 / 期末(40%) 80）
    fun enrichDetails(school: SchoolConfig, list: MutableList<GradeItemUi>, onDone: () -> Unit) {
        var idx = 0
        fun next() {
            if (idx >= list.size) { onDone(); return }
            val item = list[idx]
            idx++
            if (!shouldFetchComponentDetail(item)) { next(); return }
            val jid = item.jxbId.ifEmpty { item.jobId }
            if (jid.isBlank()) { next(); return }
            val year = item.year.toIntOrNull() ?: 2024
            val termLabel = when (item.term) { "12" -> "2"; "16" -> "3"; "2" -> "2"; "3" -> "3"; else -> "1" }
            val semester = "$year-${year + 1}-$termLabel"
            CourseApiClient.getInstance().fetchGradeDetails(school, semester, jid, object : Callback {
                override fun onFailure(call: Call, e: IOException) { next() }
                override fun onResponse(call: Call, response: Response) {
                    val comps = GradesLogic.parseComponentsXml(response.body?.string() ?: "")
                    if (comps.isNotEmpty()) list[idx - 1] = list[idx - 1].copy(detail = comps)
                    next()
                }
            })
        }
        next()
    }

    // 只拉官方绩点（N105515 学业情况页 GPA），供学期「全部」页展示官方口径
    fun fetchOverallGpaOnly(school: SchoolConfig) {
        CourseApiClient.getInstance().fetchOverallGradesIndex(school, object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                if (isLoginPageHtml(html)) return
                val doc = Jsoup.parse(html)
                val (gpa, _, _) = GradesLogic.extractSummaryInfo(doc, html)
                if (gpa.isNotBlank()) officialOverallGpa = gpa
            }
        })
    }

    // Logic for Semester Grades
    fun loadSemesterGrades() {
        if (isDemoMode) {
            semesterGrades = DemoData.semesterGrades()
            semesterLoaded = true
            semesterIsLoading = false
            return
        }
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool
        val requestSemester = currentSemester
        Log.e("GradesRoute", "loadSemesterGrades called. sync=" + com.buct.course.manager.SyncSettings.enabled(context)
                + " school=" + (school?.baseUrl ?: "null") + " semester=" + requestSemester + " demo=" + isDemoMode)
        if (!com.buct.course.manager.SyncSettings.enabled(context)) {
            // 在线同步关闭：只读本地成绩文件，不联网
            val local = loadGradesLocal(context, currentSemester)
            semesterGrades = local ?: emptyList()
            if (local == null) com.buct.course.ui.system.GlassToaster.show("在线同步未开启，暂无本地成绩，请到设置页开启「在线同步」")
            semesterLoaded = true
            semesterIsLoading = false
            return
        }
        if (school != null && requestSemester.isNotEmpty() && !semesterIsLoading) {
            val requestAccountKey = requests.begin("semester")
            val cachedSem = loadGradesLocal(context, requestSemester).orEmpty()
            if (cachedSem.isNotEmpty() && semesterGrades.isEmpty()) {
                semesterGrades = cachedSem
                semesterLoaded = true
            }

            semesterIsLoading = true
            if (requestSemester == "全部") {
                fetchAllSemestersGrades(school) { merged ->
                    val working = merged.toMutableList()
                    enrichDetails(school, working) {
                        runOnUiThreadForAccount(requestAccountKey) {
                            semesterIsLoading = false
                            semesterGrades = working
                            semesterLoaded = true
                            saveGradesLocal(context, "全部", working)
                        }
                        // 学期「全部」的平均绩点直接取官方口径（N105515 GPA 2.82），
                        // 合并接口的绩点字段口径与学业情况页不完全一致
                        if (officialOverallGpa.isBlank() || officialOverallGpa == "0" || officialOverallGpa == "0.0") {
                            fetchOverallGpaOnly(school)
                        }
                    }
                }
                return
            }
            CourseApiClient.getInstance().fetchGrades(school, requestSemester, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThreadForAccount(requestAccountKey) {
                        semesterIsLoading = false
                        GlassToaster.show("加载失败：${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    Log.e("GradesRoute", "GRADE_RAW_800: " + json.take(800))
                    if (!requests.isCurrent(requestAccountKey)) return

                    // 检测Cookie过期
                    if (isLoginPageHtml(json)) {
                        runOnUiThreadForAccount(requestAccountKey) { semesterIsLoading = false }
                        CourseApiClient.getInstance().notifyCookieExpired(requestAccountKey.session)
                        return
                    }

                    // 先从接口B解析基础成绩（含北化单行分项字段 pscj/qzcj/qmcj/sycj/sjcj）
                    val items = GradesLogic.parseGradesJson(json)

                    if (items.isEmpty()) {
                        runOnUiThreadForAccount(requestAccountKey) {
                            semesterIsLoading = false
                            semesterGrades = emptyList()
                            semesterLoaded = true
                        }
                    } else {
                        // 1) 先查 N305007 页面同款课程列表（行含 job_id），用于逐课查询分项
                        CourseApiClient.getInstance().fetchGradeCourseList(school, requestSemester, object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                Log.w("GradesRoute", "Course list fetch failed: ${e.message}")
                                runOnUiThreadForAccount(requestAccountKey) {
                                    semesterIsLoading = false
                                    semesterGrades = items
                                    semesterLoaded = true
                                    saveGradesLocal(context, currentSemester, items)
                                }
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val courseListJson = response.body?.string() ?: ""
                                Log.e("GradesRoute", "COURSE_LIST_RAW: " + courseListJson.take(1200))
                                if (!requests.isCurrent(requestAccountKey)) return
                                val jobIdMap = GradesLogic.parseCourseJobIds(courseListJson)

                                // 2) 逐课查询分项（带 job_id，对齐北化 N305007 页面行为）
                                val workingList = items.map { it.copy(detail = "") }.toMutableList()
                                var detailIndex = 0
                                fun nextDetail() {
                                    if (detailIndex >= workingList.size) {
                                        runOnUiThreadForAccount(requestAccountKey) {
                                            semesterIsLoading = false
                                            semesterGrades = workingList
                                            semesterLoaded = true
                                            saveGradesLocal(context, currentSemester, workingList)
                                        }
                                        return
                                    }
                                    val current = workingList[detailIndex]
                                    val jobId = GradesLogic.lookupCourseJobId(jobIdMap, current.courseName, current.courseCode)
                                        ?: current.jobId.ifEmpty { current.jxbId }
                                    if (jobId.isNullOrEmpty()) {
                                        detailIndex++
                                        nextDetail()
                                        return
                                    }
                                    CourseApiClient.getInstance().fetchGradeDetails(school, requestSemester, jobId, object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            Log.w("GradesRoute", "Detail fetch failed for ${current.courseName}: ${e.message}")
                                            detailIndex++
                                            nextDetail()
                                        }

                                        override fun onResponse(call: Call, response: Response) {
                                            val detailJson = response.body?.string() ?: ""
                                            Log.e("GradesRoute", "GRADE_DETAIL_RAW[$detailIndex]: " + detailJson.take(900))
                                            if (!requests.isCurrent(requestAccountKey)) return
                                            val comps = GradesLogic.parseComponentsXml(detailJson)
                                            if (comps.isNotEmpty()) {
                                                workingList[detailIndex] = workingList[detailIndex].copy(
                                                    detail = comps,
                                                    jxbId = workingList[detailIndex].jxbId.ifEmpty { jobId },
                                                    jobId = workingList[detailIndex].jobId.ifEmpty { jobId }
                                                )
                                            }
                                            detailIndex++
                                            nextDetail()
                                        }
                                    })
                                }
                                nextDetail()
                            }
                        })
                    }
                }
            })
        }
    }
    
    // Trigger load on semester change
    LaunchedEffect(currentSemester, session.token) {
        requests.cancel("semester")
        semesterIsLoading = false
        // 在线同步开启时强制走网络（忽略 semesterLoaded 缓存），保证拿到官网最新成绩
        val forceRefresh = com.buct.course.manager.SyncSettings.enabled(context)
        if (currentSemester.isNotEmpty() && (!semesterLoaded || forceRefresh)) loadSemesterGrades()
    }

    // Logic for Overall Grades
    // 第二阶段：总体接口部分课程只有课程代码(kch_id)而非教学班ID(jxb_id)，分项接口查不到；
    // 按学期拉 N305007 课程列表(cjcx_cxXsKcList)拿到真实 jxb_id，再逐课补分项
    fun fetchMissingJxbDetails(school: SchoolConfig) {
        val missing = overallGrades.filter { shouldFetchComponentDetail(it) }
        if (missing.isEmpty()) { Log.d("GradesRoute", "OVERALL_DETAIL_ALL_DONE"); return }
        val semGroups = missing.groupBy {
            val year = it.year.toIntOrNull() ?: 2024
            val termLabel = when (it.term) { "12" -> "2"; "16" -> "3"; "2" -> "2"; "3" -> "3"; else -> "1" }
            "$year-${year + 1}-$termLabel"
        }
        val semesters = semGroups.keys.toList()
        Log.d("GradesRoute", "OVERALL_MISSING: ${missing.size} sems=${semesters}")
        var semIdx = 0
        fun nextSemester() {
            if (semIdx >= semesters.size) { Log.d("GradesRoute", "OVERALL_DETAIL_ALL_DONE"); return }
            val semester = semesters[semIdx]
            semIdx++
            CourseApiClient.getInstance().fetchGradeCourseList(school, semester, object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { nextSemester() }
                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    val jobIdMap = GradesLogic.parseCourseJobIds(json)
                    val semCourses = missing.filter {
                        val year = it.year.toIntOrNull() ?: 2024
                        val termLabel = when (it.term) { "12" -> "2"; "16" -> "3"; "2" -> "2"; "3" -> "3"; else -> "1" }
                        "$year-${year + 1}-$termLabel" == semester
                    }
                    var cIdx = 0
                    fun nextCourse() {
                        if (cIdx >= semCourses.size) { nextSemester(); return }
                        val course = semCourses[cIdx]
                        cIdx++
                        val realJxb = GradesLogic.lookupCourseJobId(jobIdMap, course.courseName, course.courseCode)
                        if (realJxb.isNullOrBlank()) { nextCourse(); return }
                        Log.d("GradesRoute", "OVERALL_REJXB: ${course.courseName} -> $realJxb ($semester)")
                        CourseApiClient.getInstance().fetchGradeDetails(school, semester, realJxb, object : Callback {
                            override fun onFailure(call: Call, e: java.io.IOException) { nextCourse() }
                            override fun onResponse(call: Call, response: Response) {
                                val detailJson = response.body?.string() ?: ""
                                val comps = GradesLogic.parseComponentsXml(detailJson)
                                if (comps.isNotEmpty()) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        overallGrades = overallGrades.map {
                                            val sameCourse = (it.courseCode.isNotBlank() && it.courseCode == course.courseCode) ||
                                                GradesLogic.normalizeCourseKey(it.courseName) == GradesLogic.normalizeCourseKey(course.courseName)
                                            if (sameCourse && it.detail.isBlank()) it.copy(detail = comps, jxbId = realJxb, jobId = realJxb) else it
                                        }
                                        saveOverallLocal(context, overallGrades)
                                    }
                                }
                                nextCourse()
                            }
                        })
                    }
                    nextCourse()
                }
            })
        }
        nextSemester()
    }

    // 总体成绩页：N105515 返回的课程行无分项（平时/期末），按 jxb_id 逐课调用 N305007 分项接口补全 detail
    fun fetchOverallGradeDetails(school: SchoolConfig) {
        if (isDemoMode) return
        val targets = overallGrades.filter {
            shouldFetchComponentDetail(it) &&
                (it.jxbId.isNotBlank() || it.jobId.isNotBlank())
        }
        Log.d("GradesRoute", "OVERALL_DETAIL: targets=${targets.size} all=${overallGrades.size}")
        targets.forEach {
            Log.d("GradesRoute", "OVERALL_DETAIL_ITEM: ${it.courseName} jxb='${it.jxbId}' job='${it.jobId}' year='${it.year}' term='${it.term}'")
        }
        if (targets.isEmpty()) { fetchMissingJxbDetails(school); return }
        var idx = 0
        fun next() {
            if (idx >= targets.size) { fetchMissingJxbDetails(school); return }
            val item = targets[idx]
            idx++
            val jid = item.jxbId.ifEmpty { item.jobId }
            if (jid.isBlank()) { next(); return }
            val year = item.year.toIntOrNull() ?: 2024
            val termLabel = when (item.term) { "12" -> "2"; "16" -> "3"; "2" -> "2"; "3" -> "3"; else -> "1" }
            val semester = "$year-${year + 1}-$termLabel"
            Log.d("GradesRoute", "OVERALL_DETAIL_FETCH: ${item.courseName} jxb=$jid sem=$semester")
            CourseApiClient.getInstance().fetchGradeDetails(school, semester, jid, object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { next() }
                override fun onResponse(call: Call, response: Response) {
                    val detailJson = response.body?.string() ?: ""
                    val comps = GradesLogic.parseComponentsXml(detailJson)
                    if (comps.isNotEmpty()) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            overallGrades = overallGrades.map {
                                if (it.courseName == item.courseName && it.jxbId == item.jxbId) it.copy(detail = comps) else it
                            }
                            saveOverallLocal(context, overallGrades)
                        }
                    }
                    next()
                }
            })
        }
        next()
    }

    fun loadOverallGrades() {
        if (isDemoMode) {
            overallGrades = DemoData.overallGrades()
            overallStats = DemoData.overallStats
            overallLoaded = true
            overallIsLoading = false
            curriculumLoaded = true
            curriculumIsLoading = false
            return
        }
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool
        if (!com.buct.course.manager.SyncSettings.enabled(context)) {
            val local = loadOverallLocal(context)
            overallGrades = local ?: emptyList()
            if (local == null) com.buct.course.ui.system.GlassToaster.show("在线同步未开启，暂无本地成绩，请到设置页开启「在线同步」")
            overallStats = com.buct.course.ui.route.GradesLogic.calculateStats(overallGrades, "", 0.0, 0)
            overallLoaded = true
            overallIsLoading = false
            val localCurr = loadCurriculumLocal(context)
            if (localCurr != null) curriculumNodes = localCurr
            curriculumLoaded = true
            curriculumIsLoading = false
            return
        }
        if (school != null && !overallIsLoading) {
            val requestAccountKey = requests.begin("overall")
            // 双通道之一：先把本地缓存读出来做兜底。网络链路很长（逐学期+逐课），
            // 有缓存就直接上屏，加载圈只在完全没有数据时才出现；之后网络刷新静默更新
            val cachedOverall = loadOverallLocal(context).orEmpty().map {
                if (it.detail.isNotBlank() && !com.buct.course.ui.screen.isDetailConsistentWithGrade(it.detail, it.grade)) {
                    it.copy(detail = "", jxbId = "", jobId = "")
                } else it
            }
            if (cachedOverall.isNotEmpty() && overallGrades.isEmpty()) {
                overallGrades = cachedOverall
                overallStats = GradesLogic.calculateStats(cachedOverall, "", 0.0, 0)
                overallLoaded = true
            }
            val cachedCurriculum = loadCurriculumLocal(context).orEmpty()
            if (cachedCurriculum.isNotEmpty() && curriculumNodes.isEmpty()) {
                curriculumNodes = cachedCurriculum
                curriculumLoaded = true
            }

            overallIsLoading = true
            curriculumIsLoading = true

            CourseApiClient.getInstance().fetchOverallGradesIndex(school, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThreadForAccount(requestAccountKey) {
                        overallIsLoading = false
                        curriculumIsLoading = false
                        GlassToaster.show("获取参数失败：${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    
                    // Parse Index Logic
                    if (isLoginPageHtml(html)) {
                        runOnUiThreadForAccount(requestAccountKey) {
                            overallIsLoading = false
                            curriculumIsLoading = false
                        }
                        CourseApiClient.getInstance().notifyCookieExpired(requestAccountKey.session)
                        return
                    }

                    val doc = Jsoup.parse(html)
                    val (gpa, credits, count) = GradesLogic.extractSummaryInfo(doc, html)
                    
                    // 记录官方绩点（学期「全部」页展示官方口径）
                    officialOverallGpa = gpa
                    
                    // Update initial stats from summary
                    runOnUiThreadForAccount(requestAccountKey) {
                        overallStats = OverallStatsUi(gpa, credits.toString(), count, 0, 0, 0, 0)
                    }

                    val xfyqjdIds = GradesLogic.extractXfyqjdIds(doc, html)
                    val parsedTreeNodes = GradesLogic.extractCurriculumTreeNodes(html)
                    val xh_id = doc.selectFirst("input[name=xh_id]")?.attr("value") ?: ""
                    val cjlrxn = doc.selectFirst("input[name=cjlrxn]")?.attr("value") ?: ""
                    val cjlrxq = doc.selectFirst("input[name=cjlrxq]")?.attr("value") ?: ""

                    if (parsedTreeNodes.isNotEmpty() && curriculumNodes.isEmpty()) {
                        runOnUiThreadForAccount(requestAccountKey) {
                            curriculumNodes = parsedTreeNodes
                        }
                    }

                    if (xfyqjdIds.isEmpty()) {
                        runOnUiThreadForAccount(requestAccountKey) {
                            overallIsLoading = false
                            overallLoaded = true
                            curriculumIsLoading = false
                            curriculumLoaded = true
                        }
                        return
                    }
                    
                    // Recursive Fetch
                    GradesLogic.fetchGradesDetailsRecursive(
                        school, xfyqjdIds.toList(), 0, xh_id, cjlrxn, cjlrxq, mutableListOf(),
                        linkedMapOf(),
                        isCurrent = { requests.isCurrent(requestAccountKey) },
                        onComplete = { resultGrades, coursesByNodeId ->
                             Log.d("GradesRoute", "OVERALL_ONCOMPLETE size=${resultGrades.size} isCurrent=${requests.isCurrent(requestAccountKey)}")
                             runOnUiThreadForAccount(requestAccountKey) {
                                 overallIsLoading = false
                                 curriculumIsLoading = false
                                 curriculumLoaded = true
                                 if (parsedTreeNodes.isNotEmpty()) {
                                     val enrichedNodes = parsedTreeNodes.map { node ->
                                         node.copy(courses = coursesByNodeId[node.id].orEmpty())
                                     }
                                     curriculumNodes = enrichedNodes
                                     saveCurriculumLocal(context, enrichedNodes)
                                 }
                                 // 显示全部课程（含未出分/在读，像学业情况页那样信息全）；未出分的在分布区单列「未出分」
                                 // 网络返回不带分项，必须先把缓存里已有的分项（含已查询过的单学期成绩缓存）接过来，否则每次刷新都会把「成绩构成」抹掉
                                 val cachedSemesterGrades = semesters.flatMap { loadGradesLocal(context, it).orEmpty() }
                                 val carried = carryDetails(cachedOverall + overallGrades + semesterGrades + cachedSemesterGrades, resultGrades)
                                 overallGrades = carried
                                 overallLoaded = true
                                 overallStats = GradesLogic.calculateStats(carried, gpa, credits, count)
                                 saveOverallLocal(context, carried)

                                 fun applyUpdate(list: List<GradeItemUi>) {
                                     overallGrades = list
                                     overallStats = GradesLogic.calculateStats(list, gpa, credits, count)
                                     saveOverallLocal(context, list)
                                 }
                                 fun needsDetail(item: GradeItemUi) = shouldFetchComponentDetail(item)

                                 // 已出分但连 jxbId 都没有的，或缺少考试性质（重修/补考）标记，或重修/补考尚缺分项的，走全学期发现通道
                                 val missingJxb = carried.any { needsDetail(it) && it.jxbId.isBlank() && it.jobId.isBlank() }
                                 val missingExamNature = carried.any {
                                     it.examNature.isBlank() &&
                                         it.grade.isNotBlank() &&
                                         it.grade != "--" &&
                                         it.grade != "null"
                                 }
                                 val hasCorruptedOrMissingRetakeJxb = carried.any {
                                     (it.examNature.contains("重修") || it.examNature.contains("补考")) && it.detail.isBlank()
                                 }
                                 val workList = carried.toMutableList()
                                 if (missingJxb || missingExamNature || hasCorruptedOrMissingRetakeJxb) {
                                     // 融合：后台用 N305007「全部」通道覆盖同课程的明细，未出分的课程保持原样
                                     fetchAllSemestersGrades(school) { allMerged ->
                                         if (allMerged.isEmpty()) { fetchOverallGradeDetails(school); return@fetchAllSemestersGrades }
                                         val mergedByCode = LinkedHashMap<String, GradeItemUi>()
                                         val mergedByName = LinkedHashMap<String, GradeItemUi>()
                                         allMerged.forEach { item ->
                                             if (item.courseCode.isNotBlank()) {
                                                 val prev = mergedByCode[item.courseCode]
                                                 mergedByCode[item.courseCode] = if (prev == null) item else com.buct.course.ui.screen.pickBetterGradeAttempt(prev, item)
                                             }
                                             val nameKey = GradesLogic.normalizeCourseKey(item.courseName)
                                             val prevName = mergedByName[nameKey]
                                             mergedByName[nameKey] = if (prevName == null) item else com.buct.course.ui.screen.pickBetterGradeAttempt(prevName, item)
                                         }
                                         val merged = workList.map { g ->
                                             val m = (if (g.courseCode.isNotBlank()) mergedByCode[g.courseCode] else null)
                                                 ?: mergedByName[GradesLogic.normalizeCourseKey(g.courseName)]
                                             if (m != null) {
                                                 val best = com.buct.course.ui.screen.pickBetterGradeAttempt(g, m)
                                                 val mWinsOrTies = best === m || m.grade.trim() == best.grade.trim()
                                                 val gDetailValid = g.detail.isNotBlank() &&
                                                     g.grade.trim() == best.grade.trim() &&
                                                     com.buct.course.ui.screen.isDetailConsistentWithGrade(g.detail, best.grade)
                                                 best.copy(
                                                     detail = m.detail.ifBlank { if (gDetailValid) g.detail else "" },
                                                     jxbId = if (mWinsOrTies && m.jxbId.isNotBlank()) m.jxbId else g.jxbId.ifBlank { m.jxbId },
                                                     jobId = if (mWinsOrTies && m.jobId.isNotBlank()) m.jobId else g.jobId.ifBlank { m.jobId },
                                                     year = if (mWinsOrTies && m.year.isNotBlank()) m.year else best.year.ifBlank { m.year.ifBlank { g.year } },
                                                     term = if (mWinsOrTies && m.term.isNotBlank()) m.term else best.term.ifBlank { m.term.ifBlank { g.term } },
                                                     gpa = best.gpa.ifBlank { m.gpa.ifBlank { g.gpa } },
                                                     examNature = m.examNature.ifBlank { best.examNature.ifBlank { g.examNature } },
                                                     teachingClass = if (mWinsOrTies && m.teachingClass.isNotBlank()) m.teachingClass else best.teachingClass.ifBlank { g.teachingClass }
                                                 )
                                             } else g
                                         }.toMutableList()
                                         Log.d("GradesRoute", "ALLSEM_MERGED: ${allMerged.size} merged=${merged.size}")
                                         runOnUiThreadForAccount(requestAccountKey) { applyUpdate(merged) }
                                         enrichDetails(school, merged) {
                                             runOnUiThreadForAccount(requestAccountKey) {
                                                 applyUpdate(merged)
                                                 if (merged.any { needsDetail(it) && it.jxbId.isBlank() && it.jobId.isBlank() }) {
                                                     fetchMissingJxbDetails(school)
                                                 }
                                             }
                                         }
                                     }
                                 } else {
                                     enrichDetails(school, workList) {
                                         runOnUiThreadForAccount(requestAccountKey) {
                                             applyUpdate(workList)
                                             if (workList.any { needsDetail(it) && it.jxbId.isBlank() && it.jobId.isBlank() }) {
                                                 fetchMissingJxbDetails(school)
                                             }
                                         }
                                     }
                                 }
                             }
                        }
                    )
                }
            })
        }
    }

    // Logic for Exam Schedule（双通道：考试时间查询 + 考试信息查询，按课程名合并）
    fun loadExamSchedule() {
        if (isDemoMode) {
            examList = DemoData.exams()
            examsLoaded = true
            examIsLoading = false
            return
        }
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool
        val requestSemester = examSemester
        if (!com.buct.course.manager.SyncSettings.enabled(context)) {
            val local = loadExamsLocal(context)
            examList = local ?: emptyList()
            if (local == null) com.buct.course.ui.system.GlassToaster.show("在线同步未开启，暂无本地考试数据，请到设置页开启「在线同步」")
            examsLoaded = true
            examIsLoading = false
            return
        }
        if (school != null && !examIsLoading && requestSemester.isNotEmpty()) {
            val requestAccountKey = requests.begin("exams")
            val cachedExams = loadExamsLocal(context).orEmpty()
            if (cachedExams.isNotEmpty() && examList.isEmpty()) {
                examList = cachedExams
                examsLoaded = true
            }

            examIsLoading = true

            // 「全部」= 循环所有学期逐个拉取再合并（与成绩页同款做法）。
            // 之前直接把「全部」当学期号拼进请求，教务返回错误页面，解析必崩——这就是
            // 考试页切「全部」闪退的根因。
            val targetSemesters = if (requestSemester == "全部") {
                semesters.filter { it != "全部" }
            } else {
                listOf(requestSemester)
            }

            val collected = java.util.Collections.synchronizedList(mutableListOf<ExamItemUi>())
            val anyExamCallSucceeded = java.util.concurrent.atomic.AtomicBoolean(false)
            val semesterRemaining = java.util.concurrent.atomic.AtomicInteger(targetSemesters.size.coerceAtLeast(1))

            fun finishAll() {
                if (semesterRemaining.decrementAndGet() > 0) return
                if (!anyExamCallSucceeded.get() && collected.isEmpty()) {
                    runOnUiThreadForAccount(requestAccountKey) {
                        examIsLoading = false
                    }
                    return
                }
                val merged = collected.distinctBy { it.courseName to it.examTime }.sortedBy { it.examTime }
                runOnUiThreadForAccount(requestAccountKey) {
                    examIsLoading = false
                    examList = merged
                    examsLoaded = true
                }
                com.buct.course.ui.screen.ExamScheduleSharedCache.examList = merged
                com.buct.course.ui.screen.ExamScheduleSharedCache.examSemester = examSemester
                com.buct.course.ui.screen.ExamScheduleSharedCache.updatedAt = System.currentTimeMillis()
                // 持久化考试数据，供桌面小组件读取
                runCatching {
                    val jsonArr = org.json.JSONArray()
                    merged.forEach { e ->
                        jsonArr.put(org.json.JSONObject().apply {
                            put("courseName", e.courseName)
                            put("examTime", e.examTime)
                            put("location", e.location)
                            put("seatNumber", e.seatNumber)
                            put("examName", e.examName)
                            put("teacher", e.teacher)
                        })
                    }
                    context.getSharedPreferences("exam_cache", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("exams", jsonArr.toString())
                        .putString("semester", examSemester)
                        .putLong("updatedAt", System.currentTimeMillis())
                        .apply()
                    saveExamsLocal(context, merged)
                }
            }

            if (targetSemesters.isEmpty()) {
                finishAll()
                return
            }

            targetSemesters.forEach { semester ->
                val xnm = semester.substringBefore("-")
                val termNum = semester.substringAfterLast("-")
                val xqm = when (termNum) { "1" -> "3"; "2" -> "12"; "3" -> "16"; else -> "3" }

                val remaining = java.util.concurrent.atomic.AtomicInteger(2)
                var timeItems: List<ExamItemUi>? = null
                var infoItems: List<ExamItemUi>? = null

                fun tryFinish() {
                    if (remaining.decrementAndGet() > 0) return
                    val merged = GradesLogic.mergeExams(timeItems ?: emptyList(), infoItems ?: emptyList())
                    collected.addAll(merged)
                    finishAll()
                }

                // 通道A：考试时间查询（gnmkdm=N358163）
                CourseApiClient.getInstance().fetchExamTime(school, xnm, xqm, object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        tryFinish()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val json = response.body?.string() ?: ""
                        if (isLoginPageHtml(json)) {
                            CourseApiClient.getInstance().notifyCookieExpired(requestAccountKey.session)
                            tryFinish()
                            return
                        }
                        anyExamCallSucceeded.set(true)
                        // 教务对异常学期号会返回错误页面，解析必须兜住，否则整个应用闪退
                        runCatching { GradesLogic.parseExamTimeJson(json, xnm, termNum) }
                            .onSuccess { timeItems = it }
                        tryFinish()
                    }
                })

                // 通道B：考试信息查询（gnmkdm=N358105，地点/座位号/考试类型）
                CourseApiClient.getInstance().fetchExamSchedule(school, xnm, xqm, object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        tryFinish()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val json = response.body?.string() ?: ""
                        if (isLoginPageHtml(json)) {
                            CourseApiClient.getInstance().notifyCookieExpired(requestAccountKey.session)
                            tryFinish()
                            return
                        }
                        anyExamCallSucceeded.set(true)
                        runCatching { GradesLogic.parseExamJson(json) }
                            .onSuccess { infoItems = it }
                        tryFinish()
                    }
                })
            }
        }
    }

    LaunchedEffect(session.token) {
        requests.cancel("overall")
        requests.cancel("exams")
        overallIsLoading = false
        curriculumIsLoading = false
        examIsLoading = false
    }
    LaunchedEffect(currentTab, examSemester, session.token) {
        if ((currentTab == 1 && !overallLoaded) || (currentTab == 2 && !curriculumLoaded)) loadOverallGrades()
        if (currentTab == 3 && !examsLoaded) loadExamSchedule()
    }

    com.buct.course.ui.system.ReportPageContent(
        semesterGrades.isNotEmpty() || overallGrades.isNotEmpty() || curriculumNodes.isNotEmpty() || examList.isNotEmpty()
    )

    // 自建考试与教务考试合并展示；CustomExamStore.revision 变化会自动重组
    val localExamRevision = com.buct.course.manager.CustomExamStore.revision
    val displayExamList = remember(examList, localExamRevision) {
        com.buct.course.ui.screen.mergeExamsWithLocal(context, examList)
    }
    LaunchedEffect(displayExamList) {
        com.buct.course.ui.screen.ExamScheduleSharedCache.examList = displayExamList
    }
    var showExamEditor by remember { mutableStateOf(false) }
    var editingExam by remember { mutableStateOf<com.buct.course.model.CustomExam?>(null) }

    GradesScreen(
        currentTab = currentTab,
        onTabChange = { 
            currentTab = it
        },
        semesterGrades = semesterGrades,
        semesters = semesters,
        currentSemester = currentSemester,
        onSemesterChange = { currentSemester = it },
        semesterIsLoading = semesterIsLoading,
        overallGrades = remember(overallGrades) { sortOverallGrades(overallGrades) },
        overallStats = overallStats,
        overallIsLoading = overallIsLoading,
        officialOverallGpa = officialOverallGpa,
        examList = displayExamList,
        examSemesters = semesters,
        currentExamSemester = examSemester,
        onExamSemesterChange = { examSemester = it },
        examIsLoading = examIsLoading,
        onAddExam = {
            editingExam = null
            showExamEditor = true
        },
        onEditExam = { item ->
            val id = item.id
            if (id.isNotBlank()) {
                val target = com.buct.course.manager.CustomExamStore.get(context, id)
                if (target != null) {
                    editingExam = target
                    showExamEditor = true
                }
            }
        },
        onRefresh = {
            when (currentTab) {
                0 -> loadSemesterGrades()
                1, 2 -> loadOverallGrades()
                else -> loadExamSchedule()
            }
        },
        curriculumNodes = curriculumNodes,
        curriculumIsLoading = curriculumIsLoading,
        onExportGrades = { grades ->
            try {
                // 生成 CSV 内容（带 BOM，确保 Excel 正确识别 UTF-8）
                val csv = buildString {
                    // UTF-8 BOM
                    append('\uFEFF')
                    // 表头 - 与用户截图一致
                    appendLine("学年,学期,课程名称,课程代码,开课学院,学分,成绩,成绩分项")
                    // 数据行
                    grades.forEach { item ->
                        // 学年: xnm "2024" -> "2024-2025"
                        val yearRaw = item.year.ifEmpty { "--" }
                        val yearDisplay = yearRaw.toIntOrNull()?.let { "$it-${it + 1}" } ?: yearRaw
                        // 学期: xqm "3" -> "1", "12" -> "2"
val termDisplay = when (item.term) {
                            "3" -> "1"
                            "12" -> "2"
                            "16" -> "3"
                            else -> item.term.ifEmpty { "--" }
                        }
                        val year = escapeCsv(yearDisplay)
                        val term = escapeCsv(termDisplay)
                        val name = escapeCsv(item.courseName)
                        val code = escapeCsv(item.courseCode.ifEmpty { "--" })
                        val college = escapeCsv(item.college.ifEmpty { "--" })
                        val credits = escapeCsv(item.credits)
                        val grade = escapeCsv(item.grade)
                        val detail = escapeCsv(item.detail)
                        appendLine("$year,$term,$name,$code,$college,$credits,$grade,$detail")
                    }
                }

                // 写入外部缓存目录
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val fileName = "成绩单_$timestamp.csv"
                val cacheDir = java.io.File(context.externalCacheDir, "exports")
                cacheDir.mkdirs()
                val file = java.io.File(cacheDir, fileName)
                file.writeText(csv, Charsets.UTF_8)

                // 通过 FileProvider 分享
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "导出成绩单"))
            } catch (e: Exception) {
                Log.e("GradesRoute", "导出成绩失败: ${e.message}", e)
                GlassToaster.show("导出失败：${e.message}")
            }
        }
    )

    if (showExamEditor) {
        com.buct.course.ui.screen.ExamEditorDialog(
            existing = editingExam,
            onSave = { exam ->
                com.buct.course.manager.CustomExamStore.save(context, exam)
                com.buct.course.schedule.ExamReminderScheduler.reschedule(context)
                showExamEditor = false
                editingExam = null
            },
            onDelete = { exam ->
                com.buct.course.manager.CustomExamStore.delete(context, exam.id)
                com.buct.course.schedule.ExamReminderScheduler.reschedule(context)
                showExamEditor = false
                editingExam = null
            },
            onDismiss = {
                showExamEditor = false
                editingExam = null
            }
        )
    }
}
/**
 * 总体页拿到的新成绩不含「成绩构成」，直接覆盖会把之前补好的分项冲掉。
 * 以**课程代码**为准匹配（同一门课跨学期代码不变），代码为空时退回规范化课程名。
 * 同时只在缓存里的 jxbId 确实不是 kch_id（即非 N105515 伪教学班 ID）时才继承 jxbId。
 */
private fun carryDetails(
    previous: List<com.buct.course.ui.screen.GradeItemUi>,
    incoming: List<com.buct.course.ui.screen.GradeItemUi>
): List<com.buct.course.ui.screen.GradeItemUi> {
    val withDetail = previous.filter {
        it.detail.isNotBlank() && com.buct.course.ui.screen.isDetailConsistentWithGrade(it.detail, it.grade)
    }
    if (withDetail.isEmpty()) return incoming
    val byCode = withDetail.filter { it.courseCode.isNotBlank() }.groupBy { it.courseCode }
    val byName = withDetail.groupBy { GradesLogic.normalizeCourseKey(it.courseName) }
    val result = incoming.map { item ->
        if (item.detail.isNotBlank() && com.buct.course.ui.screen.isDetailConsistentWithGrade(item.detail, item.grade)) {
            return@map item
        }
        val itemGrade = item.grade.trim()
        val candidates = ((if (item.courseCode.isNotBlank()) byCode[item.courseCode] else null)
            ?: byName[GradesLogic.normalizeCourseKey(item.courseName)])
            ?.filter {
                it.grade.trim() == itemGrade &&
                    com.buct.course.ui.screen.isDetailConsistentWithGrade(it.detail, item.grade)
            }
            .orEmpty()
        if (candidates.isEmpty()) return@map item.copy(detail = "")
        val itemYear = com.buct.course.ui.screen.normalizeGradeYear(item.year)
        val itemTerm = com.buct.course.ui.screen.normalizeGradeTerm(item.term)
        val picked = candidates.firstOrNull {
            com.buct.course.ui.screen.normalizeGradeYear(it.year) == itemYear &&
                com.buct.course.ui.screen.normalizeGradeTerm(it.term) == itemTerm
        } ?: candidates.first()
        item.copy(
            detail = picked.detail,
            jxbId = item.jxbId.ifBlank { picked.jxbId },
            jobId = item.jobId.ifBlank { picked.jobId },
            year = picked.year.ifBlank { item.year },
            term = picked.term.ifBlank { item.term },
            examNature = item.examNature.ifBlank { picked.examNature }
        )
    }
    Log.d("GradesRoute", "CARRY_DETAIL: prev=${previous.size} withDetail=${withDetail.size} incoming=${incoming.size} out=${result.count { it.detail.isNotBlank() }}")
    return result
}

/**
 * 总体课程明细的排序。
 *
 * 原先直接用教务返回顺序（实际是按课程代码排），导致顶部全是「未出分」的课，
 * 翻半天看不到一个分数。这里改成：已出分在前，学期新的优先、同学期分数高的在前；
 * 未出分统一沉底，内部仍按学期新的优先。
 */
private fun sortOverallGrades(list: List<com.buct.course.ui.screen.GradeItemUi>): List<com.buct.course.ui.screen.GradeItemUi> {
    fun graded(item: com.buct.course.ui.screen.GradeItemUi): Boolean =
        !(item.grade.isBlank() || item.grade == "--" || item.grade == "null")

    fun termKey(item: com.buct.course.ui.screen.GradeItemUi): Int =
        (item.year.toIntOrNull() ?: 0) * 100 + (item.term.toIntOrNull() ?: 0)

    fun score(item: com.buct.course.ui.screen.GradeItemUi): Double =
        item.grade.toDoubleOrNull() ?: -1.0

    return list.sortedWith(
        compareBy<com.buct.course.ui.screen.GradeItemUi> { if (graded(it)) 0 else 1 }
            .thenByDescending { termKey(it) }
            .thenByDescending { score(it) }
            .thenBy { it.courseName }
    )
}

private fun escapeCsv(value: String): String {
    return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
}

// Logic Object
private object GradesLogic {
    /** 规范化课程名用于跨接口对齐（统一全角/半角括号与空白） */
    fun normalizeCourseKey(name: String): String =
        name.trim()
            .replace('（', '(')
            .replace('）', ')')
            .replace(" ", "")

    /** 解析 N305007 课程列表响应：同时按原课程名、规范化课程名、课程代码提取真实 jxb_id（用于逐课查分项） */
    fun parseCourseJobIds(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(json)
            val items = obj.optJSONArray("items") ?: return map
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val name = it.optString("kcmc", "").trim()
                val code = it.optString("kch", "").trim()
                    .ifEmpty { it.optString("kch_id", "").trim() }
                val job = it.optString("jxb_id", "").trim()
                    .ifEmpty { it.optString("job_id", "").trim() }
                    .ifEmpty { it.optString("JOB_ID", "").trim() }
                if (job.isEmpty()) continue
                if (name.isNotEmpty()) {
                    map[name] = job
                    map["norm:${normalizeCourseKey(name)}"] = job
                }
                if (code.isNotEmpty()) {
                    map["code:$code"] = job
                }
            }
        } catch (e: Exception) {}
        return map
    }

    fun lookupCourseJobId(jobIdMap: Map<String, String>, courseName: String, courseCode: String): String? {
        if (courseCode.isNotBlank()) {
            jobIdMap["code:${courseCode.trim()}"]?.let { if (it.isNotBlank()) return it }
        }
        jobIdMap[courseName]?.let { if (it.isNotBlank()) return it }
        jobIdMap["norm:${normalizeCourseKey(courseName)}"]?.let { if (it.isNotBlank()) return it }
        return null
    }

    /** 解析 N305007 分项接口响应：行字段 xmlInc（含占比）+ xmoJ（分数） */
    fun parseComponentsXml(json: String): String {
        try {
            val obj = JSONObject(json)
            val items = obj.optJSONArray("items") ?: return ""
            val parts = mutableListOf<String>()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val name = it.optString("xmlInc", "").trim()
                    .ifEmpty { it.optString("xmblmc", "").trim() }
                val score = it.optString("xmoJ", "").trim()
                    .ifEmpty { it.optString("xmcj", "").trim() }
                if (name.isEmpty() || score.isEmpty() || score == "null" || name == "总评") continue
                parts.add("$name: $score")
            }
            return parts.distinct().joinToString(" | ")
        } catch (e: Exception) { return "" }
    }

    fun parseGradesJson(json: String): List<GradeItemUi> {
        val result = mutableListOf<GradeItemUi>()
        try {
            var items: JSONArray? = null
            try {
                val obj = JSONObject(json)
                items = obj.optJSONArray("items")
            } catch (e: Exception) {
                items = JSONArray(json)
            }
            if (items == null) return result

            // 按 kcmc|jxb_id 分组，合并分项成绩
            val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = getOptString(item, "kcmc", "KCMC")
                if (name.isEmpty()) continue
                val jxbId = item.optString("jxb_id", item.optString("JXB_ID", ""))
                    .ifEmpty { item.optString("kch_id", item.optString("KCH_ID", "")) }
                val key = "$name|$jxbId"
                grouped.getOrPut(key) { mutableListOf() }.add(item)
            }

            for ((_, records) in grouped) {
                val first = records[0]
                val name = getOptString(first, "kcmc", "KCMC")

                // 收集分项明细
                val details = records.mapNotNull { r ->
                    val xmblmc = r.optString("xmblmc", "").trim()
                    val xmcj = r.optString("xmcj", "").trim()
                    if (xmblmc.isEmpty() || xmcj.isEmpty()) return@mapNotNull null
                    val xmbl = r.optString("xmbl", "").trim()
                    if (xmbl.isNotEmpty()) "$xmblmc(${xmbl}%): $xmcj"
                    else "$xmblmc: $xmcj"
                }.distinct()

                // 北化等学校：成绩列表接口单行直接返回 平时/期中/期末/实验/实践 分项字段
                // （参照官网新版 zf.eduplugin 的 pscj/qzcj/qmcj/sycj/sjcj 解析）
                val singleRowComponents = buildList {
                    val fieldMap = linkedMapOf(
                        "pscj" to "平时", "qzcj" to "期中", "qmcj" to "期末",
                        "sycj" to "实验", "sjcj" to "实践"
                    )
                    for ((field, label) in fieldMap) {
                        val v = getOptString(first, field, field.uppercase()).trim()
                        if (v.isEmpty() || v == "null" || v == "--") continue
                        // 尝试取占比字段（pscjbl / qmcjbl 等，部分学校接口提供）
                        val bl = getOptString(first, field + "bl", field.uppercase() + "BL").trim()
                        if (bl.isNotEmpty() && bl != "null") add("$label(${bl}%): $v")
                        else add("$label: $v")
                    }
                }

                val detailText = if (details.isNotEmpty()) details.joinToString(" | ")
                                 else singleRowComponents.joinToString(" | ")
                Log.e("GradesRoute", "GRADE_DETAIL_FIRST: " + detailText)

                val jxbId = first.optString("jxb_id", first.optString("JXB_ID", "")).trim()
                val jobId = first.optString("job_id", first.optString("JOB_ID", "")).trim()
                    .ifEmpty { jxbId }

                val maxcj = getOptString(first, "MAXCJ", "maxcj")
                val cxcj = getOptString(first, "CXCJ", "cxcj")
                val bkcj = getOptString(first, "BKCJ", "bkcj")
                val resolvedExamNature = getOptString(first, "ksxz", "KSXZ", "").ifBlank {
                    when {
                        maxcj.isNotEmpty() && cxcj.isNotEmpty() && cxcj == maxcj -> "重修"
                        maxcj.isNotEmpty() && bkcj.isNotEmpty() && bkcj == maxcj -> "补考"
                        maxcj.isNotEmpty() -> "正常考试"
                        else -> ""
                    }
                }

                result.add(
                    GradeItemUi(
                        courseName = name,
                        grade = maxcj.ifEmpty { getOptString(first, "cj", "CJ", "--") },
                        credits = getOptString(first, "xf", "XF", "0"),
                        gpa = getOptString(first, "jd", "JD"),
                        courseType = getOptString(first, "kcxzmc", "KCXZMC", ""),
                        year = first.optString("xnm", first.optString("XNM", "")),
                        term = first.optString("xqm", first.optString("XQM", "")),
                        college = first.optString("kkbmmc", first.optString("KKBMMC", "")),
                        courseCode = first.optString("kch", first.optString("KCH", "")),
                        teachingClass = first.optString("jxbmc", first.optString("JXBMC", "")),
                        jxbId = jxbId,
                        jobId = jobId,
                        detail = detailText,
                        examNature = resolvedExamNature
                    )
                )
            }
        } catch (e: Exception) { }
        return result
    }

    fun mergeDetails(baseGrades: List<GradeItemUi>, detailJson: String): List<GradeItemUi> {
        // 参照 Go 端 fetchViaJsonAPI 策略：
        // 1. 按 jxb_id 分组接口A的分项数据
        // 2. 通过 jxb_id / courseCode / courseName 匹配到接口B的基础成绩
        // 3. 智能推算缺失的期末成绩
        val detailGroupMap = parseDetailGroupMap(detailJson)
        if (detailGroupMap.isEmpty()) {
            Log.d("GradesRoute", "mergeDetails: detailGroupMap is empty, no detail data available")
            return baseGrades
        }

        Log.d("GradesRoute", "mergeDetails: ${detailGroupMap.size} detail groups, ${baseGrades.size} base grades")

        return baseGrades.map { grade ->
            // 已有带占比的分项（接口A/接口B 的 xmbl 行）则保留；无占比的单行分项允许被接口A覆盖
            if (grade.detail.isNotEmpty() && grade.detail.contains("%)")) return@map grade

            // 按优先级匹配: jxb_id > courseCode > courseName
            val matchedKey = detailGroupMap.keys.firstOrNull { key ->
                grade.jxbId.isNotEmpty() && key == grade.jxbId
            } ?: detailGroupMap.keys.firstOrNull { key ->
                // 尝试用 courseCode 匹配 (有些学校接口A的 jxb_id 与接口B不同)
                val entries = detailGroupMap[key] ?: return@firstOrNull false
                entries.isNotEmpty() && grade.courseCode.isNotEmpty() &&
                    entries[0].optString("kch", entries[0].optString("kch_id", "")).trim() == grade.courseCode
            } ?: detailGroupMap.keys.firstOrNull { key ->
                // 尝试用课程名匹配
                val entries = detailGroupMap[key] ?: return@firstOrNull false
                entries.isNotEmpty() && entries[0].optString("kcmc", "").trim() == grade.courseName
            } ?: detailGroupMap.keys.firstOrNull { key ->
                val entries = detailGroupMap[key] ?: return@firstOrNull false
                entries.isNotEmpty() && run {
                    val entryName = entries[0].optString("kcmc", "").trim()
                    grade.courseName.startsWith(entryName) || entryName.startsWith(grade.courseName)
                }
            }

            if (matchedKey != null) {
                val records = detailGroupMap[matchedKey] ?: return@map grade
                val detail = buildDetailString(records, grade.grade)
                Log.d("GradesRoute", "mergeDetails: matched '${grade.courseName}' -> detail='$detail'")
                if (detail.isNotEmpty()) grade.copy(detail = detail) else grade
            } else {
                Log.d("GradesRoute", "mergeDetails: no match for '${grade.courseName}' (jxbId=${grade.jxbId})")
                grade
            }
        }
    }

    /**
     * 参照 Go 端逻辑，从接口A的分项记录构建 detail 字符串
     * 格式: "平时(30%): 90 | 期末(70%): 97.1"
     * 包含期末成绩智能推算功能
     */
    private fun buildDetailString(records: List<JSONObject>, totalScoreStr: String): String {
        val totalScore = totalScoreStr.replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: 0.0
        val detailParts = mutableListOf<String>()
        var sumRatio = 0.0
        var sumWeightedScore = 0.0
        var hasQimo = false

        for (r in records) {
            val typeName = r.optString("xmblmc", "").trim()
            val ratioStr = r.optString("xmbl", "").trim()
            val scoreStr = r.optString("xmcj", "").trim()

            if (typeName.isEmpty() || scoreStr.isEmpty()) continue

            if (typeName.contains("期末")) hasQimo = true

            val ratio = ratioStr.toDoubleOrNull() ?: 0.0
            val score = scoreStr.toDoubleOrNull() ?: 0.0
            sumRatio += ratio
            sumWeightedScore += score * ratio / 100.0

            if (ratio > 0) {
                detailParts.add("$typeName(${ratio.toInt()}%): $scoreStr")
            } else {
                detailParts.add("$typeName: $scoreStr")
            }
        }

        // 智能推算期末成绩（参照 Go 端逻辑）
        if (!hasQimo && sumRatio < 100 && sumRatio > 0 && totalScore > 0) {
            val remainingRatio = 100 - sumRatio
            if (remainingRatio > 0) {
                val qimoScore = (totalScore - sumWeightedScore) / (remainingRatio / 100.0)
                val qimoFmt = if (qimoScore % 1.0 != 0.0) {
                    String.format("%.1f", qimoScore)
                } else {
                    qimoScore.toInt().toString()
                }
                detailParts.add("期末(${remainingRatio.toInt()}%): $qimoFmt")
            }
        }

        return detailParts.joinToString(" | ")
    }

    /**
     * 解析接口A的JSON数据，按 jxb_id 分组返回原始 JSONObject 列表
     * 与 Go 端 detailsGroup 逻辑一致
     */
    private fun parseDetailGroupMap(json: String): Map<String, List<JSONObject>> {
        val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
        try {
            var items: JSONArray? = null
            try {
                val obj = JSONObject(json)
                items = obj.optJSONArray("items")
            } catch (e: Exception) {
                try { items = JSONArray(json) } catch (_: Exception) {}
            }
            if (items == null) {
                Log.w("GradesRoute", "parseDetailGroupMap: no 'items' array found in detail JSON")
                return grouped
            }

            Log.d("GradesRoute", "parseDetailGroupMap: ${items.length()} raw items from API A")

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                // 接口A的 jxb_id 是关联分项和总评的 key
                val jxbId = item.optString("jxb_id", "").trim()
                    .ifEmpty { item.optString("JXB_ID", "").trim() }
                if (jxbId.isEmpty()) continue
                grouped.getOrPut(jxbId) { mutableListOf() }.add(item)
            }

            Log.d("GradesRoute", "parseDetailGroupMap: ${grouped.size} groups by jxb_id")
        } catch (e: Exception) {
            Log.e("GradesRoute", "parseDetailGroupMap error: ${e.message}")
        }
        return grouped
    }
    
    fun extractSummaryInfo(doc: Document, html: String): Triple<String, Double, Int> {
        var extractedGPA = ""
        var extractedCredits = 0.0
        var extractedCourseCount = 0

        var m = Pattern.compile("\\(GPA\\)[：:]\\s*<font[^>]*>([\\d.]+)</font>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (m.find()) extractedGPA = m.group(1)

        if (extractedGPA.isEmpty()) {
             m = Pattern.compile("(\\d+\\.\\d{1,2})").matcher(doc.select("font[style*=color]").text())
             if (m.find()) extractedGPA = m.group(1)
        }

        m = Pattern.compile("(?:已修|获得|总)\\s*学分[：:]\\s*([\\d.]+)").matcher(html)
        if (m.find()) extractedCredits = m.group(1).toDoubleOrNull() ?: 0.0

        m = Pattern.compile("(?:课程|门数)[：:]\\s*(\\d+)").matcher(html)
        if (m.find()) extractedCourseCount = m.group(1).toIntOrNull() ?: 0
        
        return Triple(extractedGPA, extractedCredits, extractedCourseCount)
    }
    
    fun extractXfyqjdIds(doc: Document, html: String): Set<String> {
        val ids = LinkedHashSet<String>()
        val pattern = Pattern.compile("xfyqjd_id=[\"']?([A-Fa-f0-9]{20,})[\"']?")
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            ids.add(matcher.group(1))
        }
        doc.select("input[name=xfyqjd_id], select[name=xfyqjd_id] option").forEach { 
            val v = it.attr("value")
            if (v.length > 20) ids.add(v)
        }
        return ids
    }

    fun extractCurriculumTreeNodes(html: String): List<CurriculumNodeUi> {
        val result = LinkedHashMap<String, CurriculumNodeUi>()
        val pattern = Pattern.compile(
            "fxfyqjd_id='([^']*)'[^>]*>[\\s\\S]{0,240}?xfyqjd_id='([A-Fa-f0-9]+)'\\s+jdkcsx='([^']*)'\\s+leaf='([^']*)'\\s+sfmjd='([^']*)'\\s*><p class='title1'\\s+id='p[A-Fa-f0-9]+'\\s+yxxf='([^']*)'\\s+yqzdxf='([^']*)'\\s+sftg='([^']*)'[^>]*>\"\\s*\\+\\s*\\r?\\n\\s*\"([^\"&]+)"
        )
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            val parentId = matcher.group(1)?.trim().orEmpty()
            val id = matcher.group(2)?.trim().orEmpty()
            if (id.isEmpty() || result.containsKey(id)) continue
            val sfmjd = matcher.group(5)?.trim().orEmpty()
            val yxxf = matcher.group(6)?.trim()?.toDoubleOrNull() ?: 0.0
            val yqzdxf = matcher.group(7)?.trim()?.toDoubleOrNull() ?: 0.0
            val sftg = matcher.group(8)?.trim() == "1"
            val name = matcher.group(9)?.trim().orEmpty()
            if (name.isEmpty()) continue
            result[id] = CurriculumNodeUi(
                id = id,
                parentId = parentId,
                name = name,
                earnedCredits = yxxf,
                requiredCredits = yqzdxf,
                passed = sftg,
                isLeaf = sfmjd == "1"
            )
        }
        return result.values.toList()
    }

    fun parseCurriculumCoursesJson(json: String): List<CurriculumCourseUi> {
        val list = mutableListOf<CurriculumCourseUi>()
        try {
            val arr = if (json.trim().startsWith("[")) {
                JSONArray(json)
            } else {
                JSONObject(json).optJSONArray("items") ?: return list
            }
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = getOptString(obj, "KCMC", "kcmc").trim()
                if (name.isEmpty()) continue
                val code = getOptString(obj, "KCH", "kch", "KCH_ID", "kch_id").trim()
                val key = "$code|$name"
                if (!seen.add(key)) continue
                val credits = getOptString(obj, "XF", "xf").ifEmpty { "0" }
                val xdzt = getOptString(obj, "XDZT", "xdzt").ifEmpty { "3" }
                val cj = getOptString(obj, "MAXCJ", "CJ", "cj")
                val jyXn = getOptString(obj, "JYXDXNMC", "jyxdxnmc", "XNMC", "xnmc")
                val jyXq = getOptString(obj, "JYXDXQMC", "jyxdxqmc", "XQMMC", "xqmmc")
                val kcxz = getOptString(obj, "KCXZMC", "kcxzmc", "KCLBMC", "kclbmc")
                list.add(
                    CurriculumCourseUi(
                        courseCode = code,
                        courseName = name,
                        credits = credits,
                        studyStatus = xdzt,
                        grade = cj,
                        maxGrade = getOptString(obj, "MAXCJ", "maxcj"),
                        suggestedYear = jyXn,
                        suggestedTerm = jyXq,
                        courseCategory = kcxz
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun fetchGradesDetailsRecursive(
        school: SchoolConfig, 
        ids: List<String>, index: Int, xh_id: String, cjlrxn: String, cjlrxq: String,
        accumulatedGrades: MutableList<GradeItemUi>,
        coursesByNodeId: MutableMap<String, List<CurriculumCourseUi>>,
        isCurrent: () -> Boolean = { true },
        onComplete: (List<GradeItemUi>, Map<String, List<CurriculumCourseUi>>) -> Unit
    ) {
        if (!isCurrent()) return
        if (index >= ids.size) {
            onComplete(accumulatedGrades, coursesByNodeId)
            return
        }

        val xfyqjd_id = ids[index]
        val postBody = "xfyqjd_id=$xfyqjd_id&xh_id=$xh_id&cjlrxn=$cjlrxn&cjlrxq=$cjlrxq&xscjcxkz=0&cjcxkzzt=2&cjztkz=0&cjzt="

        CourseApiClient.getInstance().fetchOverallGradesData(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchGradesDetailsRecursive(school, ids, index + 1, xh_id, cjlrxn, cjlrxq, accumulatedGrades, coursesByNodeId, isCurrent, onComplete)
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                val items = parseGradesJson(json)
                items.forEach { newItem ->
                    val existingIdx = accumulatedGrades.indexOfFirst { it.courseName == newItem.courseName }
                    if (existingIdx < 0) {
                        accumulatedGrades.add(newItem)
                    } else {
                        val better = com.buct.course.ui.screen.pickBetterGradeAttempt(accumulatedGrades[existingIdx], newItem)
                        if (better !== accumulatedGrades[existingIdx]) {
                            accumulatedGrades[existingIdx] = better
                        }
                    }
                }
                val nodeCourses = parseCurriculumCoursesJson(json)
                if (nodeCourses.isNotEmpty()) {
                    coursesByNodeId[xfyqjd_id] = nodeCourses
                }
                fetchGradesDetailsRecursive(school, ids, index + 1, xh_id, cjlrxn, cjlrxq, accumulatedGrades, coursesByNodeId, isCurrent, onComplete)
            }
        })
    }

    fun calculateStats(grades: List<GradeItemUi>, baseGPA: String, baseCredits: Double, baseCount: Int): OverallStatsUi {
        var totalCredits = 0.0
        var weightedGPA = 0.0
        var excellent = 0
        var good = 0
        var medium = 0
        var pass = 0
        var fail = 0
        var notGraded = 0

        // GPA 按北化官方规则：素质教育课程、两级制计分课程、体育课程不参与，且同课程重修/补考只取最高绩点
        val gpaGrades = com.buct.course.ui.screen.deduplicateBestAttempts(grades)
            .filter { com.buct.course.ui.screen.isGradedForGpa(it) }
        gpaGrades.forEach { g ->
            val c = g.credits.toDoubleOrNull() ?: 0.0
            val gps = com.buct.course.ui.screen.effectiveGpaPoint(g) ?: 0.0
            totalCredits += c
            weightedGPA += c * gps
        }
        grades.forEach { g ->
            when (classifyGradeBucket(g.grade)) {
                GradeBucket.Excellent -> excellent++
                GradeBucket.Good -> good++
                GradeBucket.Medium -> medium++
                GradeBucket.Pass -> pass++
                GradeBucket.Fail -> fail++
                GradeBucket.NotGraded -> notGraded++
            }
        }

        val earnedTotalCredits = com.buct.course.ui.screen.earnedCredits(grades)

        val displayGPA = if (baseGPA.isNotEmpty()) baseGPA else {
            if (totalCredits > 0) String.format("%.2f", weightedGPA / totalCredits) else "0.00"
        }
        val displayCredits = if (earnedTotalCredits > 0) String.format("%.1f", earnedTotalCredits) else String.format("%.1f", baseCredits)
        val displayCount = if (grades.isNotEmpty()) grades.size else baseCount

        return OverallStatsUi(displayGPA, displayCredits, displayCount, excellent, good, medium, pass, notGraded, fail)
    }

    private fun getOptString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key, null)
            if (!value.isNullOrEmpty() && value != "null") return value
        }
        return ""
    }

    // 解析考试时间查询通道的 JSON（gnmkdm=N358163）
    // design 接口固定返回"当前学期"数据、忽略查询学期参数，故按所选学期 xnm/xqm 过滤，防跨学期串数据
    fun parseExamTimeJson(json: String, xnm: String, termNum: String): List<ExamItemUi> {
        val list = mutableListOf<ExamItemUi>()
        try {
            val obj = JSONObject(json)
            // design 接口不同版本返回键不一样，逐个尝试；全都没有时记下键名便于排查
            val items = obj.optJSONArray("items")
                ?: obj.optJSONArray("dwList")
                ?: obj.optJSONArray("list")
                ?: obj.optJSONArray("rows")
                ?: obj.optJSONArray("data")
            if (items == null) {
                Log.d("GradesRoute", "EXAM_TIME_KEYS: ${obj.keys().asSequence().toList()}")
                return list
            }
            val wantXnmc = xnm + "-" + (xnm.toIntOrNull()?.plus(1) ?: -1)
            val wantXq = when (termNum) { "1" -> "1"; "2" -> "2"; else -> "3" }
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val itemXnmc = getOptString(item, "xnmc")
                val itemXq = getOptString(item, "xq")
                if (itemXnmc.isNotEmpty() && (itemXnmc != wantXnmc || itemXq != wantXq)) continue
                val courseName = getOptString(item, "kcmc")
                if (courseName.isNotEmpty()) {
                    list.add(
                        ExamItemUi(
                            courseName = courseName,
                            examTime = getOptString(item, "kssj"),
                            location = "",
                            seatNumber = "",
                            examName = "",
                            teacher = ""
                        )
                    )
                }
            }
        } catch (e: Exception) { }
        return list
    }

    // 合并两个考试通道：时间查询(有考试时间) + 信息查询(有地点/座位号/类型)
    // 同一门课可能有「期中 + 期末」两场考试，匹配键必须是 课程名+考试时间；
    // 只按课程名匹配会把期中的名称/地点安到期末头上，还会挤掉另一场
    fun mergeExams(timeExams: List<ExamItemUi>, infoExams: List<ExamItemUi>): List<ExamItemUi> {
        if (timeExams.isEmpty()) return infoExams.distinctBy { it.courseName to it.examTime }
        if (infoExams.isEmpty()) return timeExams.distinctBy { it.courseName to it.examTime }

        val infoByKey = infoExams.associateBy { it.courseName to it.examTime }
        val infoByName = infoExams.groupBy { it.courseName }
        val consumedInfo = mutableSetOf<Pair<String, String>>()
        val merged = mutableListOf<ExamItemUi>()

        for (t in timeExams) {
            val exact = infoByKey[t.courseName to t.examTime]
            if (exact != null) {
                consumedInfo.add(t.courseName to t.examTime)
                merged.add(
                    t.copy(
                        location = exact.location,
                        seatNumber = exact.seatNumber,
                        examName = exact.examName,
                        teacher = exact.teacher
                    )
                )
                continue
            }
            // 时间对不上：仅当两边各只有一场同名考试时才按名称合并，避免张冠李戴
            val nameGroup = infoByName[t.courseName].orEmpty()
                .filterNot { (it.courseName to it.examTime) in consumedInfo }
            if (nameGroup.size == 1 && timeExams.count { it.courseName == t.courseName } == 1) {
                val match = nameGroup.first()
                consumedInfo.add(match.courseName to match.examTime)
                merged.add(
                    t.copy(
                        location = match.location,
                        seatNumber = match.seatNumber,
                        examName = match.examName,
                        teacher = match.teacher
                    )
                )
            } else {
                merged.add(t)
            }
        }
        // 通道B 里没被合并掉的（含同一门课的多场考试）全部保留
        infoExams.filterNot { (it.courseName to it.examTime) in consumedInfo }.forEach { merged.add(it) }

        // 按时间排序（无时间排最后），并去掉完全重复的行
        return merged
            .distinctBy { it.courseName to it.examTime to it.location to it.examName }
            .sortedWith(compareBy { if (it.examTime.isBlank()) "9999" else it.examTime })
    }

    // 解析考试安排 JSON
    fun parseExamJson(json: String): List<ExamItemUi> {
        val list = mutableListOf<ExamItemUi>()
        try {
            val obj = JSONObject(json)
            val items = obj.optJSONArray("items") ?: return list
            
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val courseName = getOptString(item, "kcmc")
                if (courseName.isNotEmpty()) {
                    list.add(
                        ExamItemUi(
                            courseName = courseName,
                            examTime = getOptString(item, "kssj"),
                            location = getOptString(item, "cdmc"),
                            seatNumber = getOptString(item, "zwh"),
                            examName = getOptString(item, "ksmc"),
                            teacher = getOptString(item, "jsxx")
                        )
                    )
                }
            }
        } catch (e: Exception) { }
        return list
    }
}

// ============ 本地成绩/考试文件缓存（离线可用，配合在线同步开关） ============
private fun gradeToJson(g: GradeItemUi): org.json.JSONObject = org.json.JSONObject().apply {
    put("courseName", g.courseName); put("grade", g.grade); put("credits", g.credits); put("gpa", g.gpa)
    put("courseType", g.courseType); put("year", g.year); put("term", g.term); put("college", g.college)
    put("courseCode", g.courseCode); put("teachingClass", g.teachingClass); put("jxbId", g.jxbId); put("detail", g.detail)
    put("examNature", g.examNature)
}

private fun gradeFromJson(o: org.json.JSONObject) = GradeItemUi(
    courseName = o.optString("courseName"), grade = o.optString("grade"), credits = o.optString("credits"),
    gpa = o.optString("gpa"), courseType = o.optString("courseType"), year = o.optString("year"), term = o.optString("term"),
    college = o.optString("college"), courseCode = o.optString("courseCode"), teachingClass = o.optString("teachingClass"),
    jxbId = o.optString("jxbId"), detail = o.optString("detail"), examNature = o.optString("examNature")
)

private fun saveGradesLocal(context: android.content.Context, semester: String, list: List<GradeItemUi>) {
    val arr = org.json.JSONArray(); list.forEach { arr.put(gradeToJson(it)) }
    com.buct.course.manager.LocalStore.save(context, "grades_${UserManager.getInstance().currentAccountStorageKey}_$semester.json", arr.toString())
}

private fun loadGradesLocal(context: android.content.Context, semester: String): List<GradeItemUi>? {
    val raw = com.buct.course.manager.LocalStore.load(context, "grades_${UserManager.getInstance().currentAccountStorageKey}_$semester.json") ?: return null
    val arr = org.json.JSONArray(raw); return (0 until arr.length()).map { gradeFromJson(arr.getJSONObject(it)) }
}

private fun saveOverallLocal(context: android.content.Context, list: List<GradeItemUi>) {
    val arr = org.json.JSONArray(); list.forEach { arr.put(gradeToJson(it)) }
    com.buct.course.manager.LocalStore.save(context, "overall_${UserManager.getInstance().currentAccountStorageKey}.json", arr.toString())
}

private fun loadOverallLocal(context: android.content.Context): List<GradeItemUi>? {
    val raw = com.buct.course.manager.LocalStore.load(context, "overall_${UserManager.getInstance().currentAccountStorageKey}.json") ?: return null
    val arr = org.json.JSONArray(raw); return (0 until arr.length()).map { gradeFromJson(arr.getJSONObject(it)) }
}

private fun saveExamsLocal(context: android.content.Context, list: List<ExamItemUi>) {
    val arr = org.json.JSONArray()
    list.forEach { e ->
        arr.put(org.json.JSONObject().apply {
            put("courseName", e.courseName); put("examTime", e.examTime); put("location", e.location)
            put("seatNumber", e.seatNumber); put("examName", e.examName); put("teacher", e.teacher)
        })
    }
    com.buct.course.manager.LocalStore.save(context, "exams_${UserManager.getInstance().currentAccountStorageKey}.json", arr.toString())
}

private fun loadExamsLocal(context: android.content.Context): List<ExamItemUi>? {
    val raw = com.buct.course.manager.LocalStore.load(context, "exams_${UserManager.getInstance().currentAccountStorageKey}.json") ?: return null
    val arr = org.json.JSONArray(raw)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ExamItemUi(
            courseName = o.optString("courseName"), examTime = o.optString("examTime"), location = o.optString("location"),
            seatNumber = o.optString("seatNumber"), examName = o.optString("examName"), teacher = o.optString("teacher")
        )
    }
}

private fun saveCurriculumLocal(context: android.content.Context, nodes: List<CurriculumNodeUi>) {
    val arr = org.json.JSONArray()
    nodes.forEach { n ->
        val coursesArr = org.json.JSONArray()
        n.courses.forEach { c ->
            coursesArr.put(org.json.JSONObject().apply {
                put("courseCode", c.courseCode)
                put("courseName", c.courseName)
                put("credits", c.credits)
                put("studyStatus", c.studyStatus)
                put("grade", c.grade)
                put("maxGrade", c.maxGrade)
                put("suggestedYear", c.suggestedYear)
                put("suggestedTerm", c.suggestedTerm)
                put("courseCategory", c.courseCategory)
            })
        }
        arr.put(org.json.JSONObject().apply {
            put("id", n.id)
            put("parentId", n.parentId)
            put("name", n.name)
            put("earnedCredits", n.earnedCredits)
            put("requiredCredits", n.requiredCredits)
            put("passed", n.passed)
            put("isLeaf", n.isLeaf)
            put("courses", coursesArr)
        })
    }
    com.buct.course.manager.LocalStore.save(
        context,
        "curriculum_${UserManager.getInstance().currentAccountStorageKey}.json",
        arr.toString()
    )
}

private fun loadCurriculumLocal(context: android.content.Context): List<CurriculumNodeUi>? {
    val raw = com.buct.course.manager.LocalStore.load(
        context,
        "curriculum_${UserManager.getInstance().currentAccountStorageKey}.json"
    ) ?: return null
    return runCatching {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cArr = o.optJSONArray("courses") ?: org.json.JSONArray()
            val courses = (0 until cArr.length()).map { j ->
                val co = cArr.getJSONObject(j)
                CurriculumCourseUi(
                    courseCode = co.optString("courseCode"),
                    courseName = co.optString("courseName"),
                    credits = co.optString("credits"),
                    studyStatus = co.optString("studyStatus", "3"),
                    grade = co.optString("grade"),
                    maxGrade = co.optString("maxGrade"),
                    suggestedYear = co.optString("suggestedYear"),
                    suggestedTerm = co.optString("suggestedTerm"),
                    courseCategory = co.optString("courseCategory")
                )
            }
            CurriculumNodeUi(
                id = o.optString("id"),
                parentId = o.optString("parentId"),
                name = o.optString("name"),
                earnedCredits = o.optDouble("earnedCredits", 0.0),
                requiredCredits = o.optDouble("requiredCredits", 0.0),
                passed = o.optBoolean("passed", false),
                isLeaf = o.optBoolean("isLeaf", true),
                courses = courses
            )
        }
    }.getOrNull()
}