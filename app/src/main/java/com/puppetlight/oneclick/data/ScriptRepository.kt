package com.puppetlight.oneclick.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.puppetlight.oneclick.script.Script
import com.puppetlight.oneclick.script.ScriptParser
import org.json.JSONObject
import java.io.File

/**
 * 步骤脚本存储：
 * - 内置默认脚本：assets/scripts/default_flow.json
 * - 用户脚本（编辑/导入/录制生成）：files/scripts/\*.json
 */
class ScriptRepository(private val context: Context) {

    data class Entry(val id: String, val title: String, val builtin: Boolean, val modified: Long)

    private val dir: File get() = File(context.filesDir, "scripts").apply { mkdirs() }

    fun list(): List<Entry> {
        val user = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val title = runCatching { JSONObject(f.readText()).optString("name") }.getOrNull()
                    ?.ifEmpty { null } ?: f.nameWithoutExtension
                Entry(f.name, "$title  (${f.name})", false, f.lastModified())
            }
        return listOf(Entry(BUILTIN_ID, "内置默认流程", true, 0)) + user
    }

    fun readText(id: String): String =
        if (id == BUILTIN_ID) context.assets.open(BUILTIN_ASSET).bufferedReader().use { it.readText() }
        else File(dir, id).readText()

    fun load(id: String): Script = ScriptParser.parse(readText(id))

    fun loadActive(): Script {
        val settings = Settings(context)
        val id = settings.activeScriptId
        return try {
            load(id)
        } catch (e: Exception) {
            if (id == BUILTIN_ID) throw e
            settings.activeScriptId = BUILTIN_ID
            load(BUILTIN_ID)
        }
    }

    /** 保存（会先校验）。返回文件名 id。 */
    fun save(id: String?, text: String): String {
        ScriptParser.parse(text)
        val name = if (id == null || id == BUILTIN_ID) "script_${System.currentTimeMillis()}.json" else id
        File(dir, name).writeText(text)
        return name
    }

    fun delete(id: String) {
        if (id != BUILTIN_ID) File(dir, id).delete()
        val s = Settings(context)
        if (s.activeScriptId == id) s.activeScriptId = BUILTIN_ID
    }

    fun importFrom(uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("无法读取文件")
        return save(null, text)
    }

    fun shareIntent(id: String): Intent {
        val file = if (id == BUILTIN_ID) {
            File(context.cacheDir, "default_flow.json").apply { writeText(readText(BUILTIN_ID)) }
        } else File(dir, id)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        const val BUILTIN_ID = "builtin:default"
        const val BUILTIN_ASSET = "scripts/default_flow.json"
    }
}
