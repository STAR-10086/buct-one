package com.buct.one.data.session

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 统一身份认证（sidportal.buct.edu.cn）与北化在线（course.buct.edu.cn）会话 Cookie 的加密存储。
 * 教务登录成功后写入 sidportal 根凭证，北化在线同步时直接复用，实现一次登录打通全校系统。
 */
object SidPortalSessionVault {
    private val cipher = KeystoreCipher(KeystoreCipher.ALIAS_SSO)

    private fun vaultFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "vault"), "sso_vault.enc")

    @Synchronized
    fun saveSidPortalCookies(context: Context, sidCookies: String) {
        if (sidCookies.isBlank()) return
        val (_, existingMeol) = readCookies(context)
        writeCookies(context, sidCookies.trim(), existingMeol)
    }

    @Synchronized
    fun saveAll(context: Context, sidCookies: String, meolCookies: String) {
        val (prevSid, prevMeol) = readCookies(context)
        val nextSid = sidCookies.trim().ifBlank { prevSid }
        val nextMeol = meolCookies.trim().ifBlank { prevMeol }
        if (nextSid.isBlank() && nextMeol.isBlank()) return
        writeCookies(context, nextSid, nextMeol)
    }

    @Synchronized
    fun readCookies(context: Context): Pair<String, String> {
        val file = vaultFile(context)
        if (!file.exists()) return "" to ""
        return runCatching {
            val decrypted = cipher.decrypt(file.readText(Charsets.UTF_8)) ?: return "" to ""
            if (decrypted.startsWith("{")) {
                val obj = JSONObject(decrypted)
                obj.optString("sid", "") to obj.optString("meol", "")
            } else {
                val lines = decrypted.split('\n')
                lines.getOrNull(0).orEmpty() to lines.getOrNull(1).orEmpty()
            }
        }.getOrDefault("" to "")
    }

    @Synchronized
    fun hasAnyCookie(context: Context): Boolean {
        val (sid, meol) = readCookies(context)
        return sid.isNotBlank() || meol.isNotBlank()
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { vaultFile(context).delete() }
    }

    private fun writeCookies(context: Context, sidCookies: String, meolCookies: String) {
        runCatching {
            val file = vaultFile(context)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val json = JSONObject()
                .put("sid", sidCookies)
                .put("meol", meolCookies)
                .toString()
            file.writeText(cipher.encrypt(json), Charsets.UTF_8)
        }
    }
}
