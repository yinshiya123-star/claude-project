package com.puppetlight.oneclick.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.data.ScriptRepository
import com.puppetlight.oneclick.script.ScriptParser
import org.json.JSONObject

/** 简单的 JSON 脚本编辑器：校验、格式化、保存（内置脚本会另存为副本）。 */
class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var repo: ScriptRepository
    private lateinit var et: EditText
    private lateinit var tvInfo: TextView
    private var id: String = ScriptRepository.BUILTIN_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_editor)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = ScriptRepository(this)
        et = findViewById(R.id.etJson)
        tvInfo = findViewById(R.id.tvInfo)
        id = intent.getStringExtra(EXTRA_ID) ?: ScriptRepository.BUILTIN_ID
        et.setText(repo.readText(id))
        validate(showDialog = false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "校验").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 1, "保存").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 3, 2, "格式化")
        menu.add(0, 4, 3, "另存为新脚本")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> validate(showDialog = true)
            2 -> save(asNew = id == ScriptRepository.BUILTIN_ID)
            3 -> runCatching { et.setText(JSONObject(et.text.toString()).toString(2)) }
                .onFailure { Toast.makeText(this, "JSON 格式错误：${it.message}", Toast.LENGTH_LONG).show() }
            4 -> save(asNew = true)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun validate(showDialog: Boolean): Boolean {
        return try {
            val s = ScriptParser.parse(et.text.toString())
            tvInfo.text = "「${s.name}」 v${s.version} · 展开后 ${s.steps.size} 步 · ${s.phases.size} 个阶段" +
                if (id == ScriptRepository.BUILTIN_ID) "\n内置脚本只读，保存时会另存为新脚本。" else ""
            if (showDialog) Toast.makeText(this, "校验通过：${s.steps.size} 步", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            tvInfo.text = "⚠ ${e.message}"
            if (showDialog) AlertDialog.Builder(this).setTitle("校验失败").setMessage(e.message).setPositiveButton("好", null).show()
            false
        }
    }

    private fun save(asNew: Boolean) {
        if (!validate(showDialog = true)) return
        id = repo.save(if (asNew) null else id, et.text.toString())
        Toast.makeText(this, "已保存：$id", Toast.LENGTH_SHORT).show()
        validate(showDialog = false)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
