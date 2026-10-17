package moe.shizuku.manager.starter

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import moe.shizuku.manager.utils.Logger.LOGGER

/**
 * Device-specific starter for Xiaomi/Amlogic TVs.
 *
 * The firmware ships a diagnostic component (uid=0, SELinux domain misysdiagnose) that executes an
 * arbitrary script passed to mitv.internal.ITvService transact 4400 without checking the caller.
 *
 * This starter writes a script that launches the normal Shizuku server with app_process and passes
 * its path to that service. Before doing anything it verifies that the service exists and that a
 * harmless probe script really runs as root.
 */
object TvServiceStarter {

    private const val SERVICE_NAME = "TvService"
    private const val SERVICE_INTERFACE = "mitv.internal.ITvService"
    private const val TRANSACTION_CODE = "4400"

    private const val SCRIPT_NAME = "shizuku_tv_start.sh"
    private const val LOG_NAME = "shizuku_tv_start.log"
    private const val PROBE_SCRIPT_NAME = "shizuku_tv_probe.sh"
    private const val PROBE_OUTPUT_NAME = "shizuku_tv_probe.out"
    private const val PROBE_TIMEOUT_MS = 3000L

    private const val SERVER_NAME = "shizuku_server"
    private const val SERVER_CLASS = "rikka.shizuku.server.ShizukuService"

    private var starting = false
    private var lastStartAt = 0L

    @Volatile
    private var availableCache = false

    fun isAvailableCached(): Boolean = availableCache

    private fun scriptFile(context: Context) = File(context.filesDir, SCRIPT_NAME)

    private fun logFile(context: Context) = File(context.filesDir, LOG_NAME)

    /**
     * @return output of `service call` (usually "Result: Parcel(00000000 '....')")
     */
    @Synchronized
    fun start(context: Context): String {
        // The app can be started by more than one path at the same time (application start,
        // BOOT_COMPLETED), never run the exploit concurrently.
        val now = SystemClock.elapsedRealtime()
        if (starting || now - lastStartAt < 5000) {
            return "Start already in progress"
        }

        starting = true
        try {
            val problem = verify(context)
            if (problem != null) throw IllegalStateException(problem)

            val script = scriptFile(context)
            val log = logFile(context)

            // Let the root process append to a file the app already owns, otherwise its umask can
            // make the log unreadable for the app.
            log.delete()
            log.createNewFile()
            log.setReadable(true, false)
            log.setWritable(true, false)

            script.writeText(buildScript(context, log))
            script.setReadable(true, false)

            LOGGER.i("starting Shizuku through $SERVICE_NAME/$TRANSACTION_CODE")
            val result = callService(script)
            lastStartAt = SystemClock.elapsedRealtime()
            return result
        } finally {
            starting = false
        }
    }

    fun isAvailable(): Boolean {
        return try {
            val services = exec("list")
            services.contains("$SERVICE_NAME:") && services.contains(SERVICE_INTERFACE)
        } catch (e: Throwable) {
            LOGGER.w(e, "list services")
            false
        }.also { availableCache = it }
    }

    fun runScript(script: File): String = callService(script)

    /**
     * @return null when the service exists and executes a probe as uid 0, an error message otherwise
     */
    private fun verify(context: Context): String? {
        if (!isAvailable()) {
            return "$SERVICE_NAME ($SERVICE_INTERFACE) not found"
        }

        val output = File(context.filesDir, PROBE_OUTPUT_NAME)
        val script = File(context.filesDir, PROBE_SCRIPT_NAME)

        output.delete()
        output.createNewFile()
        output.setReadable(true, false)
        output.setWritable(true, false)

        script.writeText(
            """
                #!/system/bin/sh
                id -u > "${output.absolutePath}"
                cat /proc/self/attr/current >> "${output.absolutePath}"
            """.trimIndent() + "\n"
        )
        script.setReadable(true, false)

        val callResult = try {
            callService(script)
        } catch (e: Throwable) {
            script.delete()
            output.delete()
            return "$SERVICE_NAME call failed: ${e.message}"
        }

        var text = ""
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (text.isEmpty() && System.currentTimeMillis() < deadline) {
            text = try {
                output.readText().trim()
            } catch (e: Throwable) {
                ""
            }
            if (text.isEmpty()) Thread.sleep(50)
        }

        script.delete()
        output.delete()

        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "0") {
            val detail = if (text.isEmpty()) callResult else text.replace('\n', ' ')
            return "$SERVICE_NAME probe did not run as root: $detail"
        }

        LOGGER.i("TvService probe: uid=0 context=${lines.getOrNull(1)}")
        return null
    }

    private fun callService(script: File): String {
        return exec(
            "call", SERVICE_NAME, TRANSACTION_CODE,
            "s16", "s", "s16", script.absolutePath
        )
    }

    private fun exec(vararg args: String): String {
        val process = ProcessBuilder("/system/bin/service", *args)
            .redirectErrorStream(true)
            .start()
        return process.inputStream.bufferedReader().use { it.readText().trim() }
    }

    private fun buildScript(context: Context, log: File): String {
        val apk = context.applicationInfo.sourceDir
        // A system app has no extracted nativeLibraryDir that the classloader namespace accepts,
        // so librish is installed into the system lib directory during the system app conversion.
        val systemLib = if (Process.is64Bit()) "/system/lib64" else "/system/lib"
        val libraryPath = if (File(systemLib, "librish.so").exists()) {
            systemLib
        } else {
            context.applicationInfo.nativeLibraryDir
        }

        // Note: app_process must be given both CLASSPATH (ShizukuService derives the manager
        // package name from it) and -Djava.class.path (the actual class path).
        return """
            #!/system/bin/sh
            LOG="${log.absolutePath}"
            APK="$apk"

            exec >>"${'$'}LOG" 2>&1
            echo "=== ${'$'}(date) uid=${'$'}(id -u) context=${'$'}(cat /proc/self/attr/current) ==="

            pkill -9 -f $SERVER_NAME
            sleep 1

            export CLASSPATH="${'$'}APK"
            setsid /system/bin/app_process \
              -Djava.class.path="${'$'}APK" \
              -Dshizuku.library.path="$libraryPath" \
              /system/bin --nice-name=$SERVER_NAME \
              $SERVER_CLASS </dev/null &

            exit 0
        """.trimIndent() + "\n"
    }
}
