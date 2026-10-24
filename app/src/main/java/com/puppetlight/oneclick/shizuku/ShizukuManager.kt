package com.puppetlight.oneclick.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import com.puppetlight.oneclick.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Shizuku 状态检测、授权、绑定 [ShellService]。 */
object ShizukuManager {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 7001

    enum class Status(val text: String) {
        NOT_INSTALLED("未安装 Shizuku"),
        NOT_RUNNING("Shizuku 未启动（需用无线调试激活）"),
        TOO_OLD("Shizuku 版本过旧，请更新"),
        NO_PERMISSION("未授权"),
        READY("已就绪"),
    }

    @Volatile
    private var service: IShellService? = null
    private var pending: CompletableDeferred<IShellService?>? = null

    fun status(context: Context): Status {
        val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return if (installed) Status.NOT_RUNNING else Status.NOT_INSTALLED
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return Status.TOO_OLD
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Status.READY else Status.NO_PERMISSION
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** 打开 Shizuku App；未安装时打开下载页。 */
    fun openShizuku(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/zh-hans/download/"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
            service = s
            pending?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** 取得 shell 服务（必要时启动），失败返回 null。 */
    suspend fun shell(): IShellService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val d = CompletableDeferred<IShellService?>()
        pending = d
        Shizuku.bindUserService(args, connection)
        return withTimeoutOrNull(15_000) { d.await() }
    }
}
