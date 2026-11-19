package com.buct.one.data.session

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 校园卡查询平台（ecard.buct.edu.cn）加密会话存储。
 * 仅保存服务端下发的 webportal 会话 Cookie、脱敏后或加密封装的学工号及当前 RSA 公钥；
 * 绝不保存校园卡查询密码明文。
 */
object CardSessionVault {
    private val cipher = KeystoreCipher(KeystoreCipher.ALIAS_ECARD)

    data class StoredCardSession(
        val cookie: String,
        val idserial: String,
        val rsaPublicKey: String
    )

    private fun vaultFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "vault"), "ecard_vault.enc")

    @Synchronized
    fun save(context: Context, cookie: String, idserial: String, rsaPublicKey: String) {
        val cleanCookie = cookie.trim()
        val cleanId = idserial.trim()
        if (cleanCookie.isBlank() || cleanId.isBlank()) return
        runCatching {
            val file = vaultFile(context)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val json = JSONObject()
                .put("cookie", cleanCookie)
                .put("idserial", cleanId)
                .put("rsaPublicKey", rsaPublicKey.trim())
                .toString()
            file.writeText(cipher.encrypt(json), Charsets.UTF_8)
        }
    }

    @Synchronized
    fun read(context: Context): StoredCardSession? {
        val file = vaultFile(context)
        if (!file.exists()) return null
        return runCatching {
            val decrypted = cipher.decrypt(file.readText(Charsets.UTF_8)) ?: return null
            val obj = JSONObject(decrypted)
            val cookie = obj.optString("cookie", "").trim()
            val idserial = obj.optString("idserial", "").trim()
            val rsaPublicKey = obj.optString("rsaPublicKey", "").trim()
            if (cookie.isBlank() || idserial.isBlank()) null
            else StoredCardSession(cookie = cookie, idserial = idserial, rsaPublicKey = rsaPublicKey)
        }.getOrNull()
    }

    @Synchronized
    fun hasSession(context: Context): Boolean = read(context) != null

    @Synchronized
    fun clear(context: Context) {
        runCatching { vaultFile(context).delete() }
    }
}
