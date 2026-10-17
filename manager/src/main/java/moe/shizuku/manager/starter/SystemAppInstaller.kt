package moe.shizuku.manager.starter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import java.io.File
import java.util.zip.ZipFile
import moe.shizuku.manager.utils.Logger.LOGGER

/**
 * Copies the manager APK and librish.so into the system partition and removes the data install.
 * The system copy becomes active after a reboot and is no longer subject to the TV's
 * third-party package policy, so BOOT_COMPLETED is delivered normally.
 */
object SystemAppInstaller {

    private const val SCRIPT_NAME = "install_system_app.sh"

    fun isSystemApp(context: Context): Boolean {
        return context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    /**
     * True when a data install is layered on top of the system app (e.g. after `adb install -r`).
     */
    fun isUpdatedSystemApp(context: Context): Boolean {
        return context.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    /**
     * @return null on success, an error message otherwise
     */
    fun install(context: Context): String? {
        val lib = extractRish(context) ?: return "librish.so not found in the APK"

        val script = File(context.filesDir, SCRIPT_NAME)
        script.writeText(buildScript(context, lib))
        script.setReadable(true, false)

        LOGGER.i("installing Shizuku as a system app")
        return try {
            val result = TvServiceStarter.runScript(script)
            if (result.contains("Parcel(00000000")) null else result
        } catch (e: Exception) {
            LOGGER.w(e, "install system app")
            e.message
        }
    }

    private fun extractRish(context: Context): File? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val out = File(context.filesDir, "librish.so")
        if (out.length() > 0) {
            return out
        }

        return try {
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val entry = zip.getEntry("lib/$abi/librish.so") ?: return null
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out
        } catch (e: Throwable) {
            LOGGER.w(e, "extract librish")
            null
        }
    }

    private fun buildScript(context: Context, lib: File): String {
        val apk = context.applicationInfo.sourceDir
        val packageName = context.packageName
        val systemLib = if (Process.is64Bit()) "/system/lib64" else "/system/lib"

        // The server derives the manager package from the APK's parent directory name, and that
        // name must contain a dash after the package name (like /data/app/<pkg>-<suffix>).
        return """
            #!/system/bin/sh
            LOG=/data/local/tmp/shizuku_sysinstall.log
            exec >>"${'$'}LOG" 2>&1
            export PATH=/system/bin:/system/xbin:${'$'}PATH
            echo "=== ${'$'}(date) install $packageName as system app ==="

            mount -o rw,remount / 2>&1
            mount -o rw,remount /system 2>&1

            DIR=/system/priv-app/$packageName-1
            mkdir -p "${'$'}DIR"
            cp "$apk" "${'$'}DIR/base.apk" || exit 1
            chmod 644 "${'$'}DIR/base.apk"
            chown root:root "${'$'}DIR/base.apk"
            chcon u:object_r:system_file:s0 "${'$'}DIR/base.apk" 2>&1

            mkdir -p $systemLib
            cp "${lib.absolutePath}" "$systemLib/librish.so" || exit 1
            chmod 644 "$systemLib/librish.so"
            chown root:root "$systemLib/librish.so"
            chcon u:object_r:system_file:s0 "$systemLib/librish.so" 2>&1

            sync
            pm uninstall -k $packageName || exit 1
            sync
            sleep 5
            reboot || setprop sys.powerctl reboot
            echo "done rc=${'$'}?"
        """.trimIndent() + "\n"
    }
}
