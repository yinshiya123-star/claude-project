package com.puppetlight.oneclick.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.data.AppDetector
import com.puppetlight.oneclick.data.ScriptRepository
import com.puppetlight.oneclick.data.Settings
import com.puppetlight.oneclick.engine.RunController
import com.puppetlight.oneclick.engine.ShizukuDevice
import com.puppetlight.oneclick.record.RecordController
import com.puppetlight.oneclick.service.PuppetAccessibilityService
import com.puppetlight.oneclick.shizuku.ShizukuManager

/** 脚本管理：选择当前脚本、编辑、导入、分享、删除，以及进入录制模式。 */
class ScriptManagerActivity : AppCompatActivity() {

    private lateinit var repo: ScriptRepository
    private lateinit var settings: Settings
    private lateinit var list: ListView
    private var entries: List<ScriptRepository.Entry> = emptyList()

    private val importJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val id = repo.importFrom(uri)
            Toast.makeText(this, "已导入：$id", Toast.LENGTH_SHORT).show()
            refresh()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("导入失败").setMessage(e.message).setPositiveButton("好", null).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_manager)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = ScriptRepository(this)
        settings = Settings(this)
        list = findViewById(R.id.list)
        list.setOnItemClickListener { _, _, pos, _ -> showActions(entries[pos]) }
        findViewById<View>(R.id.btnImport).setOnClickListener {
            importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        findViewById<View>(R.id.btnRecord).setOnClickListener { startRecording() }
        findViewById<View>(R.id.btnLog).setOnClickListener { MainActivity.showLog(this) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = repo.list()
        val active = settings.activeScriptId
        val titles = entries.map { (if (it.id == active) "✅ 当前使用 · " else "") + it.title }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
    }

    private fun showActions(e: ScriptRepository.Entry) {
        val actions = mutableListOf("设为当前脚本", "查看 / 编辑", "分享导出")
        if (!e.builtin) actions += "删除"
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "设为当前脚本" -> {
                        try {
                            repo.load(e.id)
                            settings.activeScriptId = e.id
                            refresh()
                        } catch (ex: Exception) {
                            AlertDialog.Builder(this).setTitle("脚本有误").setMessage(ex.message).setPositiveButton("好", null).show()
                        }
                    }
                    "查看 / 编辑" -> startActivity(
                        Intent(this, ScriptEditorActivity::class.java).putExtra(ScriptEditorActivity.EXTRA_ID, e.id)
                    )
                    "分享导出" -> startActivity(Intent.createChooser(repo.shareIntent(e.id), "导出脚本"))
                    "删除" -> AlertDialog.Builder(this)
                        .setMessage("确定删除「${e.title}」？")
                        .setPositiveButton("删除") { _, _ -> repo.delete(e.id); refresh() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    private fun startRecording() {
        if (RunController.isRunning || RecordController.isRecording) {
            Toast.makeText(this, "正在制作或录制中", Toast.LENGTH_SHORT).show()
            return
        }
        val shizuku = settings.useShizuku
        if (shizuku) {
            if (ShizukuManager.status(this) != ShizukuManager.Status.READY) {
                Toast.makeText(this, "Shizuku 未就绪，请回主界面按提示设置", Toast.LENGTH_LONG).show()
                return
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                return
            }
        } else if (PuppetAccessibilityService.instance == null || !PuppetAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val nv = AppDetector.findPackage(this, AppDetector.NV_MAX)
        val mn = AppDetector.findPackage(this, AppDetector.MOTION_NINJA)
        val pkgVars = buildMap {
            nv?.let { put(it, "nvmaxPackage") }
            mn?.let { put(it, "motionNinjaPackage") }
        }
        val how = if (shizuku) {
            "• 你的每一次点击、长按、拖动都会自动记录（坐标 + 被点按钮的文字/描述/id）\n" +
                "• 操作稍微慢一点（每步间隔 1~2 秒），才能识别到按钮文字\n" +
                "• 输入文字无法录制，需要之后在脚本里加 SET_TEXT 步骤\n"
        } else {
            "• 普通按钮的点击、长按、输入会自动记录（控件文字 / 描述 / viewId + 坐标）\n" +
                "• 预览画面、时间轴等没有控件信息的区域：先点面板上的「📍坐标点击」或「↔ 拖动」，再在屏幕上操作一次\n"
        }
        AlertDialog.Builder(this)
            .setTitle("录制模式（${if (shizuku) "Shizuku" else "无障碍"}）")
            .setMessage(
                "开始后会显示录制面板：\n\n" + how +
                    "• 操作完成后点「保存脚本」，到这里把它设为当前脚本即可\n\n要从哪个 App 开始录制？"
            )
            .setPositiveButton("NV MAX") { _, _ -> beginRecord(pkgVars, nv) }
            .setNeutralButton("Motion Ninja") { _, _ -> beginRecord(pkgVars, mn) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun beginRecord(pkgVars: Map<String, String>, launchPkg: String?) {
        if (settings.useShizuku) {
            lifecycleScope.launch {
                val shell = ShizukuManager.shell()
                if (shell == null) {
                    Toast.makeText(this@ScriptManagerActivity, "连接 Shizuku 失败", Toast.LENGTH_LONG).show()
                    return@launch
                }
                RecordController.startShizuku(this@ScriptManagerActivity, ShizukuDevice(applicationContext, shell), pkgVars)
                openApp(launchPkg)
            }
        } else {
            val service = PuppetAccessibilityService.instance ?: return
            RecordController.startA11y(service, pkgVars)
            openApp(launchPkg)
        }
    }

    private fun openApp(pkg: String?) {
        val intent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else moveTaskToBack(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
