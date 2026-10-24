package com.puppetlight.oneclick.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.data.AppDetector
import com.puppetlight.oneclick.data.Settings

/** 高级设置：光源颜色、关键帧时间点、摇晃强度、饱和度渐变开关、包名。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var etColor: TextInputEditText
    private lateinit var colorPreview: View
    private lateinit var sliderKeyframe: Slider
    private lateinit var sliderShake: Slider
    private lateinit var swSaturation: MaterialSwitch
    private lateinit var etNvPkg: TextInputEditText
    private lateinit var etMnPkg: TextInputEditText

    private val presets = listOf(
        "暖橙" to "#FFA040", "金黄" to "#FFD24A", "火红" to "#FF4D4D", "粉紫" to "#FF5CC8",
        "幽紫" to "#9B5CFF", "冷蓝" to "#4A90FF", "青蓝" to "#3CE0D0", "月白" to "#F2F6FF",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        settings = Settings(this)
        etColor = findViewById(R.id.etColor)
        colorPreview = findViewById(R.id.colorPreview)
        sliderKeyframe = findViewById(R.id.sliderKeyframe)
        sliderShake = findViewById(R.id.sliderShake)
        swSaturation = findViewById(R.id.swSaturation)
        etNvPkg = findViewById(R.id.etNvPkg)
        etMnPkg = findViewById(R.id.etMnPkg)

        val presetRow = findViewById<LinearLayout>(R.id.colorPresets)
        val size = (40 * resources.displayMetrics.density).toInt()
        for ((name, hex) in presets) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 0, 8, 0)
                setOnClickListener { etColor.setText(hex) }
            }
            item.addView(View(this).apply { background = circle(hex) }, LinearLayout.LayoutParams(size, size))
            item.addView(TextView(this).apply { text = name; textSize = 11f; textAlignment = View.TEXT_ALIGNMENT_CENTER },
                LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT))
            presetRow.addView(item)
        }

        etColor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = normalizeColor(s.toString())
                if (v != null) {
                    settings.lightColor = v
                    colorPreview.background = circle(v)
                    etColor.error = null
                } else {
                    etColor.error = "格式：#RRGGBB"
                }
            }
        })
        sliderKeyframe.addOnChangeListener { _, v, _ ->
            settings.keyframeTime = v
            findViewById<TextView>(R.id.tvKeyframe).text = "关键帧时间点：${"%.1f".format(v)} 秒"
        }
        sliderShake.addOnChangeListener { _, v, _ ->
            settings.shakeStrength = v
            findViewById<TextView>(R.id.tvShake).text = "摇晃强度：${"%.2f".format(v)}"
        }
        swSaturation.setOnCheckedChangeListener { _, c -> settings.saturationEnabled = c }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            settings.resetAdvanced()
            load()
        }
        load()
    }

    private fun load() {
        etColor.setText(settings.lightColor)
        colorPreview.background = circle(settings.lightColor)
        sliderKeyframe.value = settings.keyframeTime.coerceIn(0.5f, 5f).let { Math.round(it * 10) / 10f }
        sliderShake.value = settings.shakeStrength.coerceIn(0f, 1f).let { Math.round(it * 20) / 20f }
        findViewById<TextView>(R.id.tvKeyframe).text = "关键帧时间点：${"%.1f".format(sliderKeyframe.value)} 秒"
        findViewById<TextView>(R.id.tvShake).text = "摇晃强度：${"%.2f".format(sliderShake.value)}"
        swSaturation.isChecked = settings.saturationEnabled
        etNvPkg.setText(settings.nvmaxPackageOverride)
        etMnPkg.setText(settings.motionNinjaPackageOverride)
        renderDetected()
    }

    private fun renderDetected() {
        val nv = AppDetector.findPackage(this, AppDetector.NV_MAX) ?: "未检测到"
        val mn = AppDetector.findPackage(this, AppDetector.MOTION_NINJA) ?: "未检测到"
        findViewById<TextView>(R.id.tvDetected).text = "当前使用：NV MAX = $nv\nMotion Ninja = $mn"
    }

    override fun onPause() {
        super.onPause()
        settings.nvmaxPackageOverride = etNvPkg.text?.toString().orEmpty()
        settings.motionNinjaPackageOverride = etMnPkg.text?.toString().orEmpty()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun circle(hex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY))
        setStroke(2, 0x55000000)
    }

    companion object {
        fun normalizeColor(input: String): String? {
            val s = input.trim().removePrefix("#").uppercase()
            if (!Regex("^[0-9A-F]{6}$").matches(s)) return null
            return "#$s"
        }
    }
}
