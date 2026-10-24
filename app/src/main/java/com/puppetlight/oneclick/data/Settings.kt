package com.puppetlight.oneclick.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** 用户设置（高级设置 + 已选择的素材）。 */
class Settings(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var backgroundUri: Uri?
        get() = sp.getString("bg", null)?.let(Uri::parse)
        set(v) = sp.edit { putString("bg", v?.toString()) }

    var greenUri: Uri?
        get() = sp.getString("green", null)?.let(Uri::parse)
        set(v) = sp.edit { putString("green", v?.toString()) }

    var greenIsVideo: Boolean
        get() = sp.getBoolean("greenIsVideo", false)
        set(v) = sp.edit { putBoolean("greenIsVideo", v) }

    /** 光源颜色，#RRGGBB */
    var lightColor: String
        get() = sp.getString("lightColor", DEFAULT_LIGHT_COLOR)!!
        set(v) = sp.edit { putString("lightColor", v) }

    /** 关键帧时间点（秒） */
    var keyframeTime: Float
        get() = sp.getFloat("keyframeTime", DEFAULT_KEYFRAME_TIME)
        set(v) = sp.edit { putFloat("keyframeTime", v) }

    /** 摇晃强度 0~1 */
    var shakeStrength: Float
        get() = sp.getFloat("shake", DEFAULT_SHAKE)
        set(v) = sp.edit { putFloat("shake", v) }

    var saturationEnabled: Boolean
        get() = sp.getBoolean("saturation", true)
        set(v) = sp.edit { putBoolean("saturation", v) }

    /** 手动指定的包名（为空则自动检测） */
    var nvmaxPackageOverride: String
        get() = sp.getString("nvPkg", "")!!
        set(v) = sp.edit { putString("nvPkg", v.trim()) }

    var motionNinjaPackageOverride: String
        get() = sp.getString("mnPkg", "")!!
        set(v) = sp.edit { putString("mnPkg", v.trim()) }

    /** 当前使用的脚本 id（"builtin:default" 或 用户脚本文件名） */
    var activeScriptId: String
        get() = sp.getString("activeScript", ScriptRepository.BUILTIN_ID)!!
        set(v) = sp.edit { putString("activeScript", v) }

    /** 控制方式："a11y" 无障碍，"shizuku" Shizuku(adb) */
    var controlMode: String
        get() = sp.getString("controlMode", MODE_A11Y)!!
        set(v) = sp.edit { putString("controlMode", v) }

    val useShizuku: Boolean get() = controlMode == MODE_SHIZUKU

    var guideShown: Boolean
        get() = sp.getBoolean("guideShown", false)
        set(v) = sp.edit { putBoolean("guideShown", v) }

    fun resetAdvanced() = sp.edit {
        remove("lightColor"); remove("keyframeTime"); remove("shake"); remove("saturation")
        remove("nvPkg"); remove("mnPkg")
    }

    companion object {
        const val MODE_A11Y = "a11y"
        const val MODE_SHIZUKU = "shizuku"
        const val DEFAULT_LIGHT_COLOR = "#FFA040"
        const val DEFAULT_KEYFRAME_TIME = 1.5f
        const val DEFAULT_SHAKE = 0.3f
    }
}
