package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.starter.TvServiceStarter
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku

class ShizukuApplication : Application() {

    companion object {

        init {
            logd("ShizukuApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }

        lateinit var application: ShizukuApplication
            private set

        lateinit var appContext: Context
            private set

    }

    private fun init(context: Context) {
        ShizukuSettings.initialize(context)
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())

        if(ShizukuSettings.getWatchdog()) WatchdogService.start(context)

        autoStartTvServiceIfNeeded(context)
    }

    /**
     * On devices that expose the TvService diagnostic channel, start the server as soon as the
     * app process starts instead of requiring the user to open the menu.
     */
    private fun autoStartTvServiceIfNeeded(context: Context) {
        Thread {
            if (!TvServiceStarter.isAvailable()) {
                return@Thread
            }

            // Give a possibly still running server time to deliver its binder to this new process.
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                return@Thread
            }

            if (Shizuku.pingBinder() || ShizukuStateMachine.isRunning()) {
                return@Thread
            }

            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)

            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    TvServiceStarter.start(context)
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                    Log.w(AppConstants.TAG, "Auto start with TvService failed (attempt $attempt)", e)
                    try {
                        Thread.sleep(2000)
                    } catch (ignored: InterruptedException) {
                        return@Thread
                    }
                }
            }

            Log.e(AppConstants.TAG, "Auto start with TvService failed", lastError)
            ShizukuStateMachine.update()
        }.start()
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        appContext = applicationContext
        init(this)
    }

}
