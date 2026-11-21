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

    private fun hasAuthenticatedSidToken(sidCookies: String): Boolean =
        sidCookies.contains("SOURCEID_TGC=", ignoreCase = true)

    @Synchronized
    fun saveSidPortalCookies(context: Context, sidCookies: String) {
        val trimmed = sidCookies.trim()
        if (trimmed.isBlank() || !hasAuthenticatedSidToken(trimmed)) return
        val (_, existingMeol, existingVpn) = readVaultJson(context)
        writeCookies(context, trimmed, existingMeol, existingVpn)
    }

    @Synchronized
    fun saveAll(context: Context, sidCookies: String, meolCookies: String) {
        val (prevSid, prevMeol, prevVpn) = readVaultJson(context)
        val trimmedSid = sidCookies.trim()
        val nextSid = if (trimmedSid.isNotBlank() && hasAuthenticatedSidToken(trimmedSid)) {
            trimmedSid
        } else {
            prevSid
        }
        val nextMeol = meolCookies.trim().ifBlank { prevMeol }
        if (nextSid.isBlank() && nextMeol.isBlank() && prevVpn.isBlank()) return
        writeCookies(context, nextSid, nextMeol, prevVpn)
    }

    @Synchronized
    fun readVpnTicket(context: Context): String = readVaultJson(context).third

    @Synchronized
    fun saveVpnTicket(context: Context, ticket: String) {
        val trimmed = ticket.trim()
        if (trimmed.isBlank() || trimmed.equals("deleteMe", ignoreCase = true)) return
        val (prevSid, prevMeol, prevVpn) = readVaultJson(context)
        if (trimmed == prevVpn) return
        writeCookies(context, prevSid, prevMeol, trimmed)
    }

    @Synchronized
    fun readCookies(context: Context): Pair<String, String> {
        val (sid, meol, _) = readVaultJson(context)
        return sid to meol
    }

    private fun readVaultJson(context: Context): Triple<String, String, String> {
        val file = vaultFile(context)
        if (!file.exists()) return Triple("", "", "")
        return runCatching {
            val decrypted = cipher.decrypt(file.readText(Charsets.UTF_8)) ?: return Triple("", "", "")
            if (decrypted.startsWith("{")) {
                val obj = JSONObject(decrypted)
                Triple(
                    obj.optString("sid", ""),
                    obj.optString("meol", ""),
                    obj.optString("vpn_ticket", "")
                )
            } else {
                val lines = decrypted.split('\n')
                Triple(lines.getOrNull(0).orEmpty(), lines.getOrNull(1).orEmpty(), "")
            }
        }.getOrDefault(Triple("", "", ""))
    }

    @Synchronized
    fun hasAnyCookie(context: Context): Boolean {
        val (sid, meol, _) = readVaultJson(context)
        return sid.isNotBlank() || meol.isNotBlank()
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { vaultFile(context).delete() }
        com.buct.course.login.BuctWebVpn.clearSession()
    }

    private fun writeCookies(
        context: Context,
        sidCookies: String,
        meolCookies: String,
        vpnTicket: String
    ) {
        runCatching {
            val file = vaultFile(context)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val json = JSONObject()
                .put("sid", sidCookies)
                .put("meol", meolCookies)
                .apply {
                    if (vpnTicket.isNotBlank()) put("vpn_ticket", vpnTicket)
                }
                .toString()
            file.writeText(cipher.encrypt(json), Charsets.UTF_8)
        }
    }
}
