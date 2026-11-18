package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.session.KeystoreCipher
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
import java.io.File
import java.util.UUID

/**
 * 北化在线（THEOL）与本地待办同步服务。
 * 串行执行同步（并发度 = 1），通过 KeystoreCipher 加密持久化 MEOL 会话 Cookie，
 * 并在失败时严格保留旧快照。
 */
class TheolSyncService private constructor(
    private val appContext: Context,
    private val store: TheolStore,
    private val client: TheolClient,
    private val cipher: KeystoreCipher
) {
    private val syncMutex = Mutex()
    private val vaultFile: File get() = File(File(appContext.filesDir, "vault"), "sso_vault.enc")

    private val _theolState = MutableStateFlow(store.readTheol(TheolParser.PARSER_VERSION))
    val theolState: StateFlow<DomainSlice<TheolSnapshot>> = _theolState.asStateFlow()

    private val _todosState = MutableStateFlow(store.readLocalTodos())
    val todosState: StateFlow<List<LocalTodoItem>> = _todosState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        restoreEncryptedSession()
    }

    /** 尝试复用已存会话静默同步北化在线作业 */
    suspend fun refreshIfSessionValid(): Result<DomainSlice<TheolSnapshot>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                if (!client.ensureSessionActive()) {
                    val failed = store.commitFailure("北化在线尚未登录或会话已过期", TheolParser.PARSER_VERSION)
                    _theolState.value = failed
                    return@withContext Result.failure(IllegalStateException(failed.lastError))
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

    private fun persistEncryptedSession() {
        runCatching {
            val sidCookies = client.exportCookieHeader("sidportal.buct.edu.cn")
            val meolCookies = client.exportCookieHeader("course.buct.edu.cn")
            val payload = "$sidCookies\n$meolCookies"
            if (payload.isBlank()) return
            val dir = vaultFile.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            vaultFile.writeText(cipher.encrypt(payload), Charsets.UTF_8)
        }
    }

    private fun restoreEncryptedSession() {
        runCatching {
            if (!vaultFile.exists()) return
            val decrypted = cipher.decrypt(vaultFile.readText(Charsets.UTF_8)) ?: return
            val lines = decrypted.split('\n')
            lines.getOrNull(0)?.takeIf { it.isNotBlank() }?.let {
                client.importCookies("sidportal.buct.edu.cn", it)
            }
            lines.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                client.importCookies("course.buct.edu.cn", it)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: TheolSyncService? = null

        fun getInstance(context: Context): TheolSyncService {
            return instance ?: synchronized(this) {
                instance ?: TheolSyncService(
                    appContext = context.applicationContext,
                    store = TheolStore(context.applicationContext),
                    client = TheolClient(),
                    cipher = KeystoreCipher(KeystoreCipher.ALIAS_SSO)
                ).also { instance = it }
            }
        }
    }
}
