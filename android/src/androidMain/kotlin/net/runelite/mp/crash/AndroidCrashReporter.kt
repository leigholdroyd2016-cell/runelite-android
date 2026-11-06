package net.runelite.mp.crash

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin facade in front of Firebase Crashlytics. Crashlytics auto-installs a default
 * uncaught-exception handler on its first `getInstance()` call (via a ContentProvider
 * declared by its manifest merge), and the SDK handles persistence + retry on next
 * launch on its own. We keep this facade around to:
 *
 *  - Stamp device/app context as Crashlytics custom keys at install time so every
 *    report carries it without per-callsite plumbing.
 *  - Funnel the launcher's own logLine() output into Crashlytics' breadcrumb log so
 *    the report includes the trail of bootstrap/network/plugin events that aren't
 *    visible to Logcat-tailing Crashlytics by default.
 *  - Provide a [forceCrash] smoke-test entry point for verifying the pipeline.
 *
 * Public API is intentionally unchanged from the disk-persisted v0 so callers
 * (RuneLiteLauncher, MainActivity) need no edits when we swap backends again later.
 */
object AndroidCrashReporter {

    private const val TAG = "CrashReporter"

    @Volatile private var installed = false

    /**
     * Initialize Crashlytics and stamp static context. Idempotent. Safe to call from
     * MainActivity.onCreate — Crashlytics tolerates being touched from the UI thread.
     *
     * Crashlytics keys off the `google_app_id` resource that the google-services Gradle
     * plugin generates from google-services.json; if that resource is missing the SDK
     * silently no-ops, so this method is also safe to call on a build without Firebase
     * configured (e.g. desktop CI mirroring).
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun install(context: Context) {
        if (installed) return
        installed = true
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            // Fork: don't send this modified build's crashes to the upstream project's Crashlytics.
            crashlytics.isCrashlyticsCollectionEnabled = false

            // Static context. Crashlytics already records the Android SDK and the app
            // versionCode/Name from the package manifest, so we only add things the
            // SDK can't see on its own.
            crashlytics.setCustomKey("device.manufacturer", Build.MANUFACTURER ?: "?")
            crashlytics.setCustomKey("device.model", Build.MODEL ?: "?")
            crashlytics.setCustomKey("device.product", Build.PRODUCT ?: "?")
            crashlytics.setCustomKey("device.brand", Build.BRAND ?: "?")
            crashlytics.setCustomKey("device.abi", Build.SUPPORTED_ABIS.joinToString(","))
            crashlytics.setCustomKey("android.release", Build.VERSION.RELEASE ?: "?")
            val pi = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: Throwable) { null }
            if (pi != null) {
                crashlytics.setCustomKey("app.versionName", pi.versionName ?: "?")
                crashlytics.setCustomKey("app.versionCode", pi.longVersionCode)
            }
            crashlytics.log("crash reporter installed")
        } catch (t: Throwable) {
            // Defensive: never let a misconfigured Firebase setup take down the
            // activity. Logging here is enough — we'll catch it on next launch when
            // Firebase config gets fixed.
            Log.e(TAG, "Crashlytics init failed", t)
        }
    }

    /**
     * Add a line to Crashlytics' rolling log. These are attached to the next crash
     * report Crashlytics emits, capped at ~64 KB / ~64 lines by the SDK. Cheap; safe to
     * call from any thread and any hot path.
     */
    fun breadcrumb(line: String) {
        if (!installed) return
        try {
            FirebaseCrashlytics.getInstance().log(line)
        } catch (_: Throwable) {
            // Crashlytics rarely throws here, but if it does we can't usefully do
            // anything — the breadcrumb is best-effort.
        }
    }

    /**
     * Record a non-fatal Throwable. Use for exceptions that we caught and recovered
     * from but want visibility into. Crashlytics groups these under "non-fatal" on the
     * dashboard, distinct from process-killing crashes.
     */
    fun nonFatal(throwable: Throwable) {
        if (!installed) return
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        } catch (_: Throwable) {
        }
    }

    /**
     * Trigger a deliberate crash on a worker thread for verifying the pipeline.
     *
     * Belt-and-suspenders: also call recordException() up front. RuneLite.main() at
     * RuneLite.java:208 overrides Thread.setDefaultUncaughtExceptionHandler with its
     * own logging-only lambda, which shadows the one Crashlytics installs on init.
     * Until that handler chains, an uncaught exception on a worker thread is logged
     * as a stdout line and the process keeps running — Crashlytics never sees it.
     * The explicit recordException keeps the smoke test working regardless. The
     * thrown exception is still useful: it surfaces on logcat for the developer to
     * see, and gets caught by the worker's exception path the same way a real bug
     * would.
     */
    fun forceCrash(message: String = "AndroidCrashReporter forceCrash() smoke test") {
        Thread({
            val ex = RuntimeException(message)
            nonFatal(ex)
            throw ex
        }, "CrashSmokeTest").start()
    }
}
