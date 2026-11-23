package com.buct.course.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.buct.course.EcardActivity
import com.buct.course.ElectricityActivity
import com.buct.course.R
import com.buct.one.data.model.electricityTargetShortLabel
import com.buct.one.data.model.formatElectricityPrimaryValue
import com.buct.one.data.model.formatFenToYuan
import com.buct.one.data.store.CardStore
import com.buct.one.data.store.ElectricityStore

/**
 * 桌面「校园卡 + 宿舍电费」迷你信息卡小组件（最小 2×1）：
 * 左侧展示校园卡余额（点击直达校园卡页），右侧展示最低余额宿舍电费（点击直达电费页）。
 */
class BalanceWidget : AppWidgetProvider() {

    companion object {
        private const val HEADER_MIN_HEIGHT_DP = 86

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val size = WidgetSizePolicy.resolve(
                context,
                appWidgetManager.getAppWidgetOptions(appWidgetId),
                appWidgetId
            )
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, size, appWidgetId))
        }

        private fun buildViews(
            context: Context,
            size: WidgetSize,
            appWidgetId: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_balance)
            views.setInt(R.id.balance_widget_root, "setBackgroundResource", WidgetAppearance.backgroundRes(context))

            val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
            val showTitle = !hideTitle && size.heightDp >= HEADER_MIN_HEIGHT_DP
            views.setViewVisibility(R.id.balance_widget_title, if (showTitle) View.VISIBLE else View.GONE)
            if (showTitle) {
                views.setTextViewText(
                    R.id.balance_widget_title,
                    WidgetConfigStore.resolveTitle(context, appWidgetId, WidgetKind.BALANCE)
                )
                views.setTextColor(R.id.balance_widget_title, WidgetAppearance.textColor(context))
                val configIntent = Intent(
                    context,
                    com.buct.course.ui.widget.WidgetConfigActivity::class.java
                ).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(
                        com.buct.course.ui.widget.WidgetConfigActivity.EXTRA_WIDGET_KIND,
                        WidgetKind.BALANCE.name
                    )
                    data = Uri.parse("buctwidget://config/balance/$appWidgetId")
                }
                views.setOnClickPendingIntent(
                    R.id.balance_widget_title,
                    PendingIntent.getActivity(
                        context,
                        appWidgetId + 5000,
                        configIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            val cardSnap = runCatching { CardStore(context.applicationContext).read(0).data }.getOrNull()
            val cardValue = if (cardSnap != null) {
                "¥${formatFenToYuan(cardSnap.account.balanceFen)}"
            } else {
                "未同步"
            }

            val eleSnap = runCatching { ElectricityStore(context.applicationContext).read(0).data }.getOrNull()
            val lowestReading = eleSnap?.readings?.minByOrNull { it.balanceValue ?: Double.MAX_VALUE }
            val eleLabel = lowestReading?.let { electricityTargetShortLabel(it.target) } ?: "宿舍电费"
            val eleValue = when {
                lowestReading != null -> formatElectricityPrimaryValue(lowestReading)
                eleSnap != null && eleSnap.targets.isEmpty() -> "未绑定"
                else -> "未同步"
            }

            val primaryColor = WidgetAppearance.textColor(context)
            val secondaryColor = WidgetAppearance.secondaryTextColor(context)

            views.setTextViewText(R.id.balance_card_label, "校园卡")
            views.setTextViewText(R.id.balance_card_value, cardValue)
            views.setTextColor(R.id.balance_card_label, secondaryColor)
            views.setTextColor(R.id.balance_card_value, if (cardSnap != null) primaryColor else secondaryColor)

            views.setTextViewText(R.id.balance_ele_label, eleLabel)
            views.setTextViewText(R.id.balance_ele_value, eleValue)
            views.setTextColor(R.id.balance_ele_label, secondaryColor)
            views.setTextColor(R.id.balance_ele_value, if (lowestReading != null) primaryColor else secondaryColor)

            views.setOnClickPendingIntent(
                R.id.balance_card_col,
                PendingIntent.getActivity(
                    context,
                    appWidgetId + 5300,
                    Intent(context, EcardActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setOnClickPendingIntent(
                R.id.balance_ele_col,
                PendingIntent.getActivity(
                    context,
                    appWidgetId + 5600,
                    Intent(context, ElectricityActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            WidgetTextSizes.apply(views, WidgetTextSizes.BALANCE_ROOT, WidgetTextSizes.scale(context))
            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetProviders.ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, WidgetProviders.balanceIds(context, manager))
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            WidgetConfigStore.clear(context, appWidgetId)
        }
    }
}
