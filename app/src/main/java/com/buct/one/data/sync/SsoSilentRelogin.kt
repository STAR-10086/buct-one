package com.buct.one.data.sync

import android.content.Context
import android.os.SystemClock
import com.buct.course.manager.SyncSettings
import com.buct.course.manager.UserManager
import com.buct.one.data.session.SidPortalSessionVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 统一身份认证（sidportal）跨模块单飞静默重登协调器。
 *
 * 当任一接入统一身份认证的模块（北化在线 / 实践教学 / 第二课堂 / 体测平台）遇到会话过期或无有效 TGT 时，
 * 若本地开启了「在线同步」且已安全保存学号密码，则加锁尝试重登一次并更新 [SidPortalSessionVault]，
 * 并发到达的其它模块可直接复用已刷新的根凭证，避免弹出“请重新登录”打断用户。
 *
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
internal object SsoSilentRelogin {

    private val reloginMutex = Mutex()
    private const val FAILURE_COOLDOWN_MS = 30_000L

    @Volatile
    private var lastFailureAtMs: Long = 0L

    fun resetCooldown() {
        lastFailureAtMs = 0L
    }

    fun isAuthFailure(error: Throwable?): Boolean = isAuthFailureMessage(error?.message)

    fun isAuthFailureMessage(message: String?): Boolean {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return false
        return listOf("登录", "过期", "失效", "会话", "未建立", "认证", "票据", "401", "403")
            .any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    fun hasSavedCredentials(context: Context): Boolean {
        val app = context.applicationContext
        if (!SyncSettings.enabled(app)) return false
        val user = UserManager.getInstance().apply { init(app) }
        if (user.isDemoMode) return false
        val username = user.username.ifBlank { user.studentId.orEmpty() }
        return username.isNotBlank() && !user.accountPassword.isNullOrEmpty()
    }

    suspend fun attemptRelogin(
        context: Context,
        sidSnapshotBefore: String,
        bypassCooldown: Boolean = false,
        performLogin: suspend (username: String, passwordChars: CharArray) -> Boolean
    ): Boolean = reloginMutex.withLock {
        val app = context.applicationContext
        val latestSid = SidPortalSessionVault.readCookies(app).first
        if (latestSid.isNotBlank() && latestSid != sidSnapshotBefore) {
            lastFailureAtMs = 0L
            return@withLock true
        }
        val now = SystemClock.elapsedRealtime()
        if (!bypassCooldown && lastFailureAtMs > 0L && now - lastFailureAtMs < FAILURE_COOLDOWN_MS) {
            return@withLock false
        }
        if (!SyncSettings.enabled(app)) return@withLock false
        val user = UserManager.getInstance().apply { init(app) }
        if (user.isDemoMode) return@withLock false
        val username = user.username.ifBlank { user.studentId.orEmpty() }
        val password = user.accountPassword
        if (username.isBlank() || password.isNullOrEmpty()) return@withLock false

        val passwordChars = password.toCharArray()
        val ok = try {
            runCatching { performLogin(username, passwordChars) }.getOrDefault(false)
        } finally {
            passwordChars.fill('\u0000')
        }
        if (ok) {
            lastFailureAtMs = 0L
        } else {
            lastFailureAtMs = SystemClock.elapsedRealtime()
        }
        ok
    }
}
