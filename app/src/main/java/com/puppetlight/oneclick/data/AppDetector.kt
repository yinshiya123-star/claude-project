package com.puppetlight.oneclick.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * 检测 NV MAX / Motion Ninja 是否已安装。
 * 不同渠道包名可能不同，所以按「应用名称」匹配所有桌面图标应用；也可以在高级设置里手动指定包名。
 */
object AppDetector {

    data class TargetApp(val key: String, val displayName: String, val labelKeywords: List<String>, val storeQuery: String)

    val NV_MAX = TargetApp("nvmax", "NV MAX", listOf("nv max", "nvmax", "node video", "nodevideo"), "NV MAX")
    val MOTION_NINJA = TargetApp("motionninja", "Motion Ninja", listOf("motion ninja", "motionninja"), "Motion Ninja")

    fun findPackage(context: Context, app: TargetApp): String? {
        val settings = Settings(context)
        val override = if (app == NV_MAX) settings.nvmaxPackageOverride else settings.motionNinjaPackageOverride
        if (override.isNotEmpty() && isInstalled(context, override)) return override

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        // 优先完全包含关键字的；去掉空格后再比较一次
        for (ri in list) {
            val label = ri.loadLabel(pm).toString().lowercase()
            val compact = label.replace(" ", "")
            if (app.labelKeywords.any { label.contains(it) || compact.contains(it.replace(" ", "")) }) {
                return ri.activityInfo.packageName
            }
        }
        for (ri in list) {
            val pkg = ri.activityInfo.packageName.lowercase()
            if (app.labelKeywords.any { pkg.contains(it.replace(" ", "")) }) return ri.activityInfo.packageName
        }
        return null
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun openStore(context: Context, app: TargetApp) {
        val q = Uri.encode(app.storeQuery)
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$q"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
