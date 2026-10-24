package com.puppetlight.oneclick.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.data.AppDetector
import com.puppetlight.oneclick.data.MediaHelper
import com.puppetlight.oneclick.data.ScriptRepository
import com.puppetlight.oneclick.data.Settings as AppSettings
import com.puppetlight.oneclick.engine.RunState
import com.puppetlight.oneclick.service.KeepAliveService
import com.puppetlight.oneclick.service.ProjectionService
import com.puppetlight.oneclick.service.PuppetAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var imgBg: ImageView
    private lateinit var imgGreen: ImageView
    private lateinit var tvBg: TextView
    private lateinit var tvGreen: TextView
    private lateinit var tvSummary: TextView
    private lateinit var statusList: LinearLayout

    private val pickBg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri)
        settings.backgroundUri = uri
        renderMaterials()
    }

    private val pickGreen = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri)
        settings.greenUri = uri
        settings.greenIsVideo = MediaHelper.isVideo(this, uri)
        renderMaterials()
    }

    private val requestPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMediaPermission()) startFlow()
        else AlertDialog.Builder(this)
            .setTitle("需要相册权限")
            .setMessage("需要读取相册，才能把素材放进相册、并找到 NV MAX 导出的视频交给 Motion Ninja。请在系统设置中允许「照片和视频」权限。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val requestProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            ProjectionService.start(this, r.resultCode, r.data!!)
        } else {
            Toast.makeText(this, "未授权截图，将跳过绿幕残留检测", Toast.LENGTH_SHORT).show()
        }
        launchRun()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)
        imgBg = findViewById(R.id.imgBg)
        imgGreen = findViewById(R.id.imgGreen)
        tvBg = findViewById(R.id.tvBg)
        tvGreen = findViewById(R.id.tvGreen)
        tvSummary = findViewById(R.id.tvSummary)
        statusList = findViewById(R.id.statusList)

        findViewById<android.view.View>(R.id.cardBg).setOnClickListener { pickBg.launch(arrayOf("image/*")) }
        findViewById<android.view.View>(R.id.cardGreen).setOnClickListener { pickGreen.launch(arrayOf("image/*", "video/*")) }
        findViewById<android.view.View>(R.id.btnStart).setOnClickListener { startFlow() }
        findViewById<android.view.View>(R.id.btnAdvanced).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnScripts).setOnClickListener {
            startActivity(Intent(this, ScriptManagerActivity::class.java))
        }
        renderMaterials()
        if (!settings.guideShown) showFirstGuide()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        checkInterrupted()
        tvSummary.text = "光源 ${settings.lightColor} · 关键帧 ${settings.keyframeTime}s · 摇晃 ${"%.2f".format(settings.shakeStrength)}" +
            (if (settings.saturationEnabled) " · 饱和度渐变" else "")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "使用引导")
        menu.add(0, 2, 0, "上次运行日志")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> showFirstGuide()
            2 -> showLog(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun persist(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // ------------------------------------------------------------ 渲染

    private fun renderMaterials() {
        settings.backgroundUri?.let { uri ->
            tvBg.text = "已选择 ✓（点击更换）"
            loadThumb(uri, imgBg, false)
        }
        settings.greenUri?.let { uri ->
            tvGreen.text = (if (settings.greenIsVideo) "视频" else "图片") + " 已选择 ✓（点击更换）"
            loadThumb(uri, imgGreen, settings.greenIsVideo)
        }
    }

    private fun loadThumb(uri: Uri, into: ImageView, video: Boolean) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (video) {
                        MediaMetadataRetriever().run {
                            setDataSource(this@MainActivity, uri)
                            val f = getFrameAtTime(0)
                            release()
                            f
                        }
                    } else decodeSampled(uri, 600)
                }.getOrNull()
            }
            if (bmp != null) into.setImageBitmap(bmp)
            else Toast.makeText(this@MainActivity, "无法读取所选文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeSampled(uri: Uri, maxSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / sample > maxSize * 2 || opts.outHeight / sample > maxSize * 2) sample *= 2
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun renderStatus() {
        statusList.removeAllViews()
        val a11y = PuppetAccessibilityService.isEnabled(this)
        addStatusRow("无障碍服务", a11y, if (a11y) "已开启" else "未开启（必需）", "去开启") { openAccessibilitySettings() }
        val overlay = Settings.canDrawOverlays(this)
        addStatusRow("悬浮窗权限", overlay, if (overlay) "已开启" else "未开启（推荐）", "去开启") { openOverlaySettings() }
        for (app in listOf(AppDetector.NV_MAX, AppDetector.MOTION_NINJA)) {
            val pkg = AppDetector.findPackage(this, app)
            addStatusRow(app.displayName, pkg != null, pkg ?: "未安装", "去下载") { AppDetector.openStore(this, app) }
        }
    }

    private fun addStatusRow(name: String, ok: Boolean, detail: String, action: String, onAction: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }
        val color = ContextCompat.getColor(this, if (ok) R.color.ok else R.color.bad)
        row.addView(TextView(this).apply {
            text = if (ok) "✓" else "✗"
            setTextColor(color)
            textSize = 18f
            setPadding(0, 0, 24, 0)
        })
        row.addView(TextView(this).apply {
            text = "$name\n$detail"
            textSize = 13f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok) {
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = action
                setOnClickListener { onAction() }
            })
        }
        statusList.addView(row)
    }

    // ------------------------------------------------------------ 引导

    private fun showFirstGuide() {
        AlertDialog.Builder(this)
            .setTitle("欢迎使用一键木偶打光")
            .setMessage(
                "首次使用需要开启两项权限：\n\n" +
                    "1. 无障碍服务（必需）\n在「无障碍 → 已下载的应用 / 已安装的服务」中找到「一键木偶打光 · 自动操作」并开启。它用来替你点击、滑动 NV MAX 和 Motion Ninja。\n\n" +
                    "2. 悬浮窗权限（推荐）\n运行时显示进度悬浮窗，可随时暂停/停止。\n\n" +
                    "另外请确认已安装 NV MAX 和 Motion Ninja。" +
                    (if (isXiaomi()) "\n\n小米/红米用户：请在 应用信息 → 其他权限 中允许「后台弹出界面」，" +
                        "并把省电策略设为「无限制」，否则制作时可能无法自动打开 NV MAX。" else "")
            )
            .setPositiveButton("去开启无障碍") { _, _ ->
                settings.guideShown = true
                openAccessibilitySettings()
            }
            .setNeutralButton("开启悬浮窗") { _, _ ->
                settings.guideShown = true
                openOverlaySettings()
            }
            .setNegativeButton("稍后") { _, _ -> settings.guideShown = true }
            .show()
    }

    /** 上次制作没有正常结束 → 进程被系统清理了，告诉用户原因和解决办法。 */
    private fun checkInterrupted() {
        if (PuppetAccessibilityService.instance?.isRunning == true) return
        if (!RunState.consumeInterrupted(this)) return
        val last = RunState.loadLastLog(this).lines().lastOrNull { it.contains("▶") }
            ?.substringAfter("▶")?.trim() ?: "开始阶段"
        AlertDialog.Builder(this)
            .setTitle("上次制作被系统中断了")
            .setMessage(
                "停在：$last\n\n" +
                    "这通常是手机的省电/后台清理把 App 关掉了，悬浮窗也会跟着消失。请在「一键木偶打光」的 应用信息 里：\n\n" +
                    "1. 省电策略 → 无限制\n" +
                    "2. 自启动 → 打开\n" +
                    "3. 其他权限 → 后台弹出界面、显示悬浮窗 → 允许\n" +
                    "4. 在最近任务里把本 App 加锁，不要上滑清理\n\n" +
                    "然后把无障碍开关关掉再重新打开，再试一次。"
            )
            .setPositiveButton("去应用信息") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNeutralButton("看日志") { _, _ -> showLog(this) }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun isXiaomi() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "找到「一键木偶打光 · 自动操作」并开启", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    // ------------------------------------------------------------ 一键制作

    private fun mediaPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasMediaPermission() = mediaPermissions().all { granted(it) }

    private fun startFlow() {
        if (settings.backgroundUri == null || settings.greenUri == null) {
            Toast.makeText(this, "请先选择背景图和绿幕素材", Toast.LENGTH_SHORT).show()
            return
        }
        val service = PuppetAccessibilityService.instance
        if (!PuppetAccessibilityService.isEnabled(this) || service == null) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("自动操作 NV MAX 和 Motion Ninja 需要无障碍服务。请在设置中开启「一键木偶打光 · 自动操作」。\n\n若已开启仍提示此信息，请关闭后重新开启一次。")
                .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (service.isRunning) {
            Toast.makeText(this, "正在制作中，请查看悬浮窗", Toast.LENGTH_SHORT).show()
            return
        }
        val missing = listOf(AppDetector.NV_MAX, AppDetector.MOTION_NINJA).filter { AppDetector.findPackage(this, it) == null }
        if (missing.isNotEmpty()) {
            val app = missing.first()
            AlertDialog.Builder(this)
                .setTitle("未安装 ${missing.joinToString("、") { it.displayName }}")
                .setMessage("一键制作需要用到 NV MAX 和 Motion Ninja，请先安装。\n\n如果已经安装但没检测到，可以在「高级设置」里手动填写包名。")
                .setPositiveButton("去应用商店下载 ${app.displayName}") { _, _ -> AppDetector.openStore(this, app) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val needed = mediaPermissions().filterNot { granted(it) }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.any { it in mediaPermissions() }) {
            requestPerms.launch(needed.toTypedArray())
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 10 及以下：截图检测需要录屏授权
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            requestProjection.launch(mpm.createScreenCaptureIntent())
            return
        }
        launchRun()
    }

    private fun launchRun() {
        val service = PuppetAccessibilityService.instance ?: return
        val script = try {
            ScriptRepository(this).loadActive()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("脚本有误").setMessage(e.message).setPositiveButton("好", null).show()
            return
        }
        val vars = buildVars()
        if (service.startRun(script, vars)) {
            KeepAliveService.start(this)
            RunState.log("参数：${vars.filterKeys { !it.endsWith("Uri") }}")
            Toast.makeText(this, "开始自动制作，请勿触碰屏幕", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildVars(): Map<String, String> {
        val color = settings.lightColor.uppercase()
        return mapOf(
            "nvmaxPackage" to (AppDetector.findPackage(this, AppDetector.NV_MAX) ?: ""),
            "motionNinjaPackage" to (AppDetector.findPackage(this, AppDetector.MOTION_NINJA) ?: ""),
            "backgroundUri" to settings.backgroundUri.toString(),
            "greenUri" to settings.greenUri.toString(),
            "greenIsVideo" to settings.greenIsVideo.toString(),
            "lightColor" to color,
            "lightColorHex" to color.removePrefix("#"),
            "keyframeTime" to settings.keyframeTime.toString(),
            "shakeStrength" to settings.shakeStrength.toString(),
            "saturationEnabled" to settings.saturationEnabled.toString(),
        )
    }

    companion object {
        fun showLog(activity: AppCompatActivity) {
            val text = RunState.loadLastLog(activity).ifEmpty { "暂无日志" }
            val tv = TextView(activity).apply {
                this.text = text
                setTextIsSelectable(true)
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(32, 16, 32, 16)
            }
            AlertDialog.Builder(activity)
                .setTitle("运行日志")
                .setView(android.widget.ScrollView(activity).apply { addView(tv) })
                .setPositiveButton("关闭", null)
                .setNeutralButton("分享") { _, _ ->
                    activity.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享日志")
                    )
                }
                .show()
        }
    }
}
