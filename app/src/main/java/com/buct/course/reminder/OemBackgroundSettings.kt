package com.buct.course.reminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class OemBrand(
    val displayName: String,
    val autoStartGuide: String,
    val batteryGuide: String
) {
    XIAOMI(
        displayName = "小米 / HyperOS",
        autoStartGuide = "请在权限管理中开启「自启动」",
        batteryGuide = "省电策略选「无限制」"
    ),
    VIVO(
        displayName = "vivo / OriginOS",
        autoStartGuide = "请在权限管理中开启「自启动」",
        batteryGuide = "后台耗电设为「允许后台高耗电」"
    ),
    OPPO(
        displayName = "OPPO / 一加 / ColorOS",
        autoStartGuide = "请在应用权限中开启「允许自启动」",
        batteryGuide = "耗电管理设为「允许完全后台行为」"
    ),
    HONOR(
        displayName = "荣耀 / MagicOS",
        autoStartGuide = "应用启动管理关闭自动管理并开启「自启动」",
        batteryGuide = "应用启动管理开启「允许后台活动」"
    ),
    HUAWEI(
        displayName = "华为 / HarmonyOS",
        autoStartGuide = "应用启动管理关闭自动管理并开启「自启动」",
        batteryGuide = "应用启动管理开启「允许后台活动」"
    ),
    SAMSUNG(
        displayName = "三星 / One UI",
        autoStartGuide = "请确保未将本应用加入深度休眠列表",
        batteryGuide = "电池设为「不受限制」"
    ),
    MEIZU(
        displayName = "魅族 / Flyme",
        autoStartGuide = "请在手机管家中开启「后台管理 → 允许后台运行」",
        batteryGuide = "后台管理设为「保持后台运行」"
    ),
    OTHER(
        displayName = "通用系统",
        autoStartGuide = "请在应用信息中允许后台自启动",
        batteryGuide = "电池优化设为「不受限制」"
    )
}

/**
 * 识别设备品牌并跳转到对应厂商的「自启动」与「后台耗电 / 电池优化」设置页。
 * 找不到厂商页面时自动降级到系统电池优化或应用详情页。
 */
object OemBackgroundSettings {

    fun detectBrand(): OemBrand {
        val id = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            "xiaomi" in id || "redmi" in id || "poco" in id -> OemBrand.XIAOMI
            "vivo" in id || "iqoo" in id -> OemBrand.VIVO
            "oppo" in id || "oneplus" in id || "realme" in id -> OemBrand.OPPO
            "honor" in id -> OemBrand.HONOR
            "huawei" in id -> OemBrand.HUAWEI
            "samsung" in id -> OemBrand.SAMSUNG
            "meizu" in id -> OemBrand.MEIZU
            else -> OemBrand.OTHER
        }
    }

    /** 尝试打开厂商自启动管理页；若降级到应用详情页返回 false 以便提示引导文案。 */
    fun openAutoStart(context: Context): Boolean {
        val pkg = context.packageName
        val brand = detectBrand()
        val intents = when (brand) {
            OemBrand.XIAOMI -> listOf(
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg)
            )
            OemBrand.VIVO -> listOf(
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
                    .putExtra("packagename", pkg),
                componentIntent("com.iqoo.secure", "com.iqoo.secure.MainActivity")
            )
            OemBrand.OPPO -> listOf(
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
            OemBrand.HONOR -> listOf(
                componentIntent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
            OemBrand.HUAWEI -> listOf(
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            OemBrand.MEIZU -> listOf(
                componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            )
            OemBrand.SAMSUNG, OemBrand.OTHER -> emptyList()
        }
        if (tryStartAny(context, intents)) return true
        openAppDetails(context)
        return false
    }

    /** 尝试打开厂商后台耗电 / 电池优化设置页；若降级到应用详情页返回 false 以便提示引导文案。 */
    fun openBatteryOptimization(context: Context): Boolean {
        val pkg = context.packageName
        val brand = detectBrand()
        val oemIntents = when (brand) {
            OemBrand.XIAOMI -> listOf(
                componentIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg)
                    .putExtra("package_label", "BUCT One"),
                componentIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")
            )
            OemBrand.VIVO -> listOf(
                componentIntent("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                componentIntent("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
            )
            OemBrand.OPPO -> listOf(
                componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
                componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
            )
            OemBrand.HONOR -> listOf(
                componentIntent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
            OemBrand.HUAWEI -> listOf(
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            OemBrand.SAMSUNG -> listOf(
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                componentIntent("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
            OemBrand.MEIZU -> listOf(
                componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            )
            OemBrand.OTHER -> emptyList()
        }
        if (tryStartAny(context, oemIntents)) return true

        val aospIntents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$pkg")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                add(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
        if (tryStartAny(context, aospIntents)) return true

        openAppDetails(context)
        return false
    }

    private fun componentIntent(pkg: String, cls: String): Intent = Intent().apply {
        component = ComponentName(pkg, cls)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun tryStartAny(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
