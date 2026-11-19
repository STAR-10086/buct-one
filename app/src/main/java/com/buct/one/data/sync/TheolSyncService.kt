package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.BlacklistedCourse
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.TheolDownloadedFile
import com.buct.one.data.model.TheolFilterPrefs
import com.buct.one.data.model.TheolHomeworkDetail
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.model.TheolTestDetail
import com.buct.one.data.model.TheolTestItem
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.theol.TheolClient
import com.buct.one.data.source.theol.TheolParser
import com.buct.one.data.store.TheolStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 北化在线（THEOL）与本地待办同步服务。
 * 串行执行同步（并发度 = 1），自动复用教务统一身份认证（sidportal）根凭证实现免二次登录，
 * 并在失败时严格保留旧快照。
 */
class TheolSyncService private constructor(
    private val appContext: Context,
    private val store: TheolStore,
    private val client: TheolClient
) {
    private val syncMutex = Mutex()
    private val detailCache = ConcurrentHashMap<String, TheolHomeworkDetail>()
    private val testDetailCache = ConcurrentHashMap<String, TheolTestDetail>()
    @Volatile
    private var lastRestoredPair: Pair<String, String>? = null

    private val _theolState = MutableStateFlow(store.readTheol(TheolParser.PARSER_VERSION))
    val theolState: StateFlow<DomainSlice<TheolSnapshot>> = _theolState.asStateFlow()

    private val _todosState = MutableStateFlow(store.readLocalTodos())
    val todosState: StateFlow<List<LocalTodoItem>> = _todosState.asStateFlow()

    private val _filterPrefs = MutableStateFlow(store.readFilterPrefs())
    val filterPrefs: StateFlow<TheolFilterPrefs> = _filterPrefs.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        restoreEncryptedSession()
        detailCache.putAll(store.readHomeworkDetails())
        testDetailCache.putAll(store.readTestDetails())
    }

    fun hasSavedSsoSession(): Boolean = SidPortalSessionVault.hasAnyCookie(appContext)

    /** 尝试复用已存统一身份认证（sidportal）或 MEOL 会话静默同步北化在线作业 */
    suspend fun refreshIfSessionValid(): Result<DomainSlice<TheolSnapshot>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                // 每次同步前检查加密库是否有来自教务登录页的最新凭证
                restoreEncryptedSession()
                val probedReminder = client.probeReminderHtml()
                if (probedReminder == null && !client.ensureSessionActive(forceSso = true)) {
                    val failed = store.commitFailure("北化在线会话未建立，请重新登录教务系统或点击右上角登录", TheolParser.PARSER_VERSION)
                    _theolState.value = failed
                    return@withContext Result.failure(IllegalStateException(failed.lastError))
                }
                persistEncryptedSession()
                val snapshot = client.fetchPendingSnapshot(
                    nowMillis = System.currentTimeMillis(),
                    prefetchedReminderHtml = probedReminder
                )
                val complete = snapshot.failedCourseCount == 0
                val reason = if (complete) null else "${snapshot.failedCourseCount} 门课程抓取失败"
                val committed = store.commitSuccess(
                    snapshot = snapshot,
                    parserVersion = TheolParser.PARSER_VERSION,
                    nowMillis = System.currentTimeMillis(),
                    complete = complete,
                    incompleteReason = reason
                )
                _theolState.value = committed
                Result.success(committed)
            } catch (e: Exception) {
                val failed = store.commitFailure(e.message ?: "同步失败", TheolParser.PARSER_VERSION)
                _theolState.value = failed
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    /** 原生登录北化在线并立刻同步一次（密码使用 CharArray，登录完即清零） */
    suspend fun loginAndSync(username: String, passwordChars: CharArray): Result<DomainSlice<TheolSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val loginResult = client.loginWithCredentials(username, passwordChars)
                    if (loginResult.isFailure) {
                        val msg = loginResult.exceptionOrNull()?.message ?: "北化在线登录失败"
                        val failed = store.commitFailure(msg, TheolParser.PARSER_VERSION)
                        _theolState.value = failed
                        return@withContext Result.failure(loginResult.exceptionOrNull() ?: IllegalStateException(msg))
                    }
                    persistEncryptedSession()
                    val snapshot = client.fetchPendingSnapshot(System.currentTimeMillis())
                    val complete = snapshot.failedCourseCount == 0
                    val reason = if (complete) null else "${snapshot.failedCourseCount} 门课程抓取失败"
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = TheolParser.PARSER_VERSION,
                        nowMillis = System.currentTimeMillis(),
                        complete = complete,
                        incompleteReason = reason
                    )
                    _theolState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(e.message ?: "同步失败", TheolParser.PARSER_VERSION)
                    _theolState.value = failed
                    Result.failure(e)
                } finally {
                    _syncing.value = false
                }
            }
        }

    /** 原生拉取单条作业详情（自动激活课程上下文、持久化刷新后的 Cookie 并做内存+磁盘缓存） */
    suspend fun fetchHomeworkDetail(
        item: TheolHomeworkItem,
        forceRefresh: Boolean = false
    ): Result<TheolHomeworkDetail> = fetchDetailCached(
        key = item.hwtid,
        cache = detailCache,
        forceRefresh = forceRefresh,
        persistCache = { store.writeHomeworkDetails(it) }
    ) {
        client.fetchHomeworkDetail(item, System.currentTimeMillis())
    }

    /** 原生拉取单条在线测验详情（自动激活课程上下文、持久化刷新后的 Cookie 并做内存+磁盘缓存） */
    suspend fun fetchTestDetail(
        item: TheolTestItem,
        forceRefresh: Boolean = false
    ): Result<TheolTestDetail> = fetchDetailCached(
        key = item.testId,
        cache = testDetailCache,
        forceRefresh = forceRefresh,
        persistCache = { store.writeTestDetails(it) }
    ) {
        client.fetchTestDetail(item, System.currentTimeMillis())
    }

    private suspend fun <T : Any> fetchDetailCached(
        key: String,
        cache: ConcurrentHashMap<String, T>,
        forceRefresh: Boolean,
        persistCache: (Map<String, T>) -> Unit,
        fetcher: () -> T
    ): Result<T> {
        if (!forceRefresh) {
            cache[key]?.let { return Result.success(it) }
        }
        return withContext(Dispatchers.IO) {
            syncMutex.withLock {
                if (!forceRefresh) {
                    cache[key]?.let { return@withLock Result.success(it) }
                }
                try {
                    restoreEncryptedSession()
                    if (!client.isSessionRecentlyVerified() && !client.ensureSessionActive()) {
                        return@withLock Result.failure(
                            IllegalStateException("北化在线会话未建立，请重新登录教务系统或点击右上角登录")
                        )
                    }
                    val detail = fetcher()
                    persistEncryptedSession()
                    cache[key] = detail
                    persistCache(cache)
                    Result.success(detail)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * 为即将打开的 WebView 准备干净的会话并按需激活指定课程上下文，
     * 返回新鲜的 (sidportalCookie, meolCookie)。
     */
    suspend fun prepareCourseWebSession(
        courseLid: String,
        targetIsCourseHome: Boolean = false
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                restoreEncryptedSession()
                if (!client.isSessionRecentlyVerified() && !client.ensureSessionActive()) {
                    return@withLock Result.failure(
                        IllegalStateException("北化在线会话未建立，请先同步或重新登录")
                    )
                }
                val cleanLid = courseLid.trim()
                if (!targetIsCourseHome && cleanLid.isNotBlank() && !client.isCourseContextFresh(cleanLid)) {
                    if (!client.enterCourseContext(cleanLid)) {
                        if (!client.ensureSessionActive(forceSso = true) ||
                            !client.enterCourseContext(cleanLid, force = true)
                        ) {
                            return@withLock Result.failure(
                                IllegalStateException("无法进入课程上下文，请重新登录后重试")
                            )
                        }
                    }
                }
                persistEncryptedSession()
                Result.success(
                    client.exportCookieHeader("sidportal.buct.edu.cn") to
                        client.exportCookieHeader("course.buct.edu.cn")
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 使用已认证的北化在线会话原生下载课程作业或网页附件到应用私有缓存目录。
     */
    suspend fun downloadAttachment(
        url: String,
        suggestedName: String,
        courseLid: String
    ): Result<TheolDownloadedFile> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                restoreEncryptedSession()
                if (!client.isSessionRecentlyVerified() && !client.ensureSessionActive()) {
                    return@withContext Result.failure(
                        IllegalStateException("请先登录教务统一身份认证（sidportal）")
                    )
                }
                val file = client.downloadAttachment(
                    url = url,
                    suggestedName = suggestedName,
                    courseLid = courseLid,
                    cacheRootDir = appContext.cacheDir
                )
                persistEncryptedSession()
                Result.success(file)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun addLocalTodo(
        title: String,
        courseName: String,
        deadlineMillis: Long?,
        priority: Int = 1
    ) {
        val item = LocalTodoItem(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            courseName = courseName.trim(),
            deadlineMillis = deadlineMillis,
            priority = priority.coerceIn(0, 2),
            createdAt = System.currentTimeMillis()
        )
        val updated = listOf(item) + _todosState.value
        store.writeLocalTodos(updated)
        _todosState.value = updated
    }

    fun toggleTodoCompleted(id: String) {
        val updated = _todosState.value.map {
            if (it.id == id) it.copy(completed = !it.completed) else it
        }
        store.writeLocalTodos(updated)
        _todosState.value = updated
    }

    fun softDeleteTodo(id: String) {
        val updated = _todosState.value.map {
            if (it.id == id) it.copy(deleted = true) else it
        }
        store.writeLocalTodos(updated)
        _todosState.value = updated
    }

    fun restoreTodo(id: String) {
        val updated = _todosState.value.map {
            if (it.id == id) it.copy(deleted = false) else it
        }
        store.writeLocalTodos(updated)
        _todosState.value = updated
    }

    @Synchronized
    private fun updateFilterPrefs(transform: (TheolFilterPrefs) -> TheolFilterPrefs) {
        val updated = transform(_filterPrefs.value)
        store.writeFilterPrefs(updated)
        _filterPrefs.value = updated
    }

    fun hideHomework(hwtid: String) {
        if (hwtid.isBlank()) return
        updateFilterPrefs { it.copy(hiddenHomeworkIds = it.hiddenHomeworkIds + hwtid) }
    }

    fun restoreHomework(hwtid: String) {
        updateFilterPrefs { it.copy(hiddenHomeworkIds = it.hiddenHomeworkIds - hwtid) }
    }

    fun restoreAllHiddenHomeworks() {
        updateFilterPrefs { it.copy(hiddenHomeworkIds = emptySet()) }
    }

    fun hideTest(lid: String, testId: String) {
        val key = "$lid:$testId"
        updateFilterPrefs { it.copy(hiddenTestKeys = it.hiddenTestKeys + key) }
    }

    fun restoreAllHiddenTests() {
        updateFilterPrefs { it.copy(hiddenTestKeys = emptySet()) }
    }

    fun blacklistCourse(lid: String, courseName: String) {
        val trimmedName = courseName.trim()
        val trimmedLid = lid.trim()
        val key = trimmedLid.ifBlank { "name:$trimmedName" }
        if (key == "name:") return
        updateFilterPrefs { prefs ->
            val exists = prefs.blacklistedCourses.any {
                it.key == key ||
                    (trimmedLid.isNotBlank() && it.lid == trimmedLid) ||
                    (trimmedName.isNotBlank() && it.courseName == trimmedName)
            }
            if (exists) prefs
            else prefs.copy(
                blacklistedCourses = prefs.blacklistedCourses + BlacklistedCourse(
                    key = key,
                    lid = trimmedLid,
                    courseName = trimmedName
                )
            )
        }
    }

    fun unblacklistCourse(key: String) {
        updateFilterPrefs { prefs ->
            prefs.copy(blacklistedCourses = prefs.blacklistedCourses.filterNot { it.key == key })
        }
    }

    private fun persistEncryptedSession() {
        val sidCookies = client.exportCookieHeader("sidportal.buct.edu.cn")
        val meolCookies = client.exportCookieHeader("course.buct.edu.cn")
        val currentPair = sidCookies to meolCookies
        if (lastRestoredPair != currentPair) {
            SidPortalSessionVault.saveAll(appContext, sidCookies, meolCookies)
            lastRestoredPair = currentPair
        }
    }

    private fun restoreEncryptedSession() {
        val currentPair = SidPortalSessionVault.readCookies(appContext)
        if (lastRestoredPair == currentPair) return
        val (sidCookies, meolCookies) = currentPair
        if (sidCookies.isNotBlank()) {
            client.importCookies("sidportal.buct.edu.cn", sidCookies)
        }
        if (meolCookies.isNotBlank()) {
            client.importCookies("course.buct.edu.cn", meolCookies)
        }
        client.invalidateSessionCache()
        lastRestoredPair = currentPair
    }

    companion object {
        const val WEB_USER_AGENT: String = TheolClient.USER_AGENT

        @Volatile
        private var instance: TheolSyncService? = null

        fun getInstance(context: Context): TheolSyncService {
            return instance ?: synchronized(this) {
                instance ?: TheolSyncService(
                    appContext = context.applicationContext,
                    store = TheolStore(context.applicationContext),
                    client = TheolClient()
                ).also { instance = it }
            }
        }
    }
}
