package com.buct.one.data.session

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 校园卡查询平台（ecard.buct.edu.cn）加密凭据与会话存储。
 * 使用独立 Keystore alias（ALIAS_ECARD）加密保存会话 Cookie、学工号、RSA 公钥及用户授权记住的查询密码；
 * 仅存本机 vault/ecard_vault.enc（已排除云备份与设备迁移）。
 */
object CardSessionVault {
    private val cipher = KeystoreCipher(KeystoreCipher.ALIAS_ECARD)

    data class StoredCardSession(
        val cookie: String,
        val idserial: String,
        val rsaPublicKey: String,
        val rememberedPassword: String = ""
    ) {
        val hasActiveCookie: Boolean get() = cookie.isNotBlank() && idserial.isNotBlank()
        val hasSavedPassword: Boolean get() = idserial.isNotBlank() && rememberedPassword.isNotEmpty()
    }

    private fun vaultFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "vault"), "ecard_vault.enc")

    @Synchronized
    fun save(
        context: Context,
        cookie: String,
        idserial: String,
        rsaPublicKey: String,
        rememberedPassword: String? = null,
        preserveExistingPassword: Boolean = true
    ) {
        val cleanCookie = cookie.trim()
        val cleanId = idserial.trim()
        if (cleanId.isBlank()) return

        val finalPassword = when {
            rememberedPassword != null -> rememberedPassword
            preserveExistingPassword -> read(context)?.takeIf { it.idserial == cleanId }?.rememberedPassword.orEmpty()
            else -> ""
        }
        if (cleanCookie.isBlank() && finalPassword.isEmpty()) {
            clear(context)
            return
        }
        runCatching {
            val file = vaultFile(context)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val json = JSONObject()
                .put("cookie", cleanCookie)
                .put("idserial", cleanId)
                .put("rsaPublicKey", rsaPublicKey.trim())
                .apply {
                    if (finalPassword.isNotEmpty()) {
                        put("pwd", finalPassword)
                    }
                }
                .toString()
            file.writeText(cipher.encrypt(json), Charsets.UTF_8)
        }
    }

    @Synchronized
    fun read(context: Context): StoredCardSession? {
        val file = vaultFile(context)
        if (!file.exists()) return null
        return runCatching {
            val decrypted = cipher.decrypt(file.readText(Charsets.UTF_8))
            if (decrypted == null) {
                clear(context)
                return null
            }
            val obj = JSONObject(decrypted)
            val cookie = obj.optString("cookie", "").trim()
            val idserial = obj.optString("idserial", "").trim()
            val rsaPublicKey = obj.optString("rsaPublicKey", "").trim()
            val pwd = obj.optString("pwd", "")
            if (idserial.isBlank() || (cookie.isBlank() && pwd.isEmpty())) {
                null
            } else {
                StoredCardSession(
                    cookie = cookie,
                    idserial = idserial,
                    rsaPublicKey = rsaPublicKey,
                    rememberedPassword = pwd
                )
            }
        }.getOrElse {
            clear(context)
            null
        }
    }

    @Synchronized
    fun hasSession(context: Context): Boolean = read(context)?.hasActiveCookie == true

    @Synchronized
    fun hasSavedPassword(context: Context): Boolean = read(context)?.hasSavedPassword == true

    @Synchronized
    fun canAutoSync(context: Context): Boolean =
        read(context)?.let { it.hasActiveCookie || it.hasSavedPassword } == true

    @Synchronized
    fun clearSessionOnly(context: Context) {
        val existing = read(context)
        if (existing != null && existing.hasSavedPassword) {
            save(
                context = context,
                cookie = "",
                idserial = existing.idserial,
                rsaPublicKey = "",
                rememberedPassword = existing.rememberedPassword,
                preserveExistingPassword = false
            )
        } else {
            clear(context)
        }
    }

    @Synchronized
    fun clearSavedPassword(context: Context) {
        val existing = read(context)
        if (existing != null && existing.hasActiveCookie) {
            save(
                context = context,
                cookie = existing.cookie,
                idserial = existing.idserial,
                rsaPublicKey = existing.rsaPublicKey,
                rememberedPassword = "",
                preserveExistingPassword = false
            )
        } else {
            clear(context)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { vaultFile(context).delete() }
    }
}
