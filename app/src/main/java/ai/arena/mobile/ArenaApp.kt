package ai.arena.mobile

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process

class ArenaApp : Application() {

    override fun onCreate() {
        super.onCreate()

        SettingsStore.applyStoredTheme(this)

        // Каталоги WebView удаляются только из главного процесса, когда
        // процессы профилей гарантированно не запущены.
        if (isMainProcess()) {
            ProfileStore.runPendingWipes(this)
            ProfileStore.runPendingCacheCleans(this)
            AppShortcuts.syncIfChanged(this)
        }
    }

    private fun isMainProcess(): Boolean {
        val name = currentProcessName(this)
        return name == null || name == packageName
    }

    companion object {
        fun currentProcessName(context: Context): String? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
        } catch (t: Throwable) {
            null
        }
    }
}

abstract class ProfileShutdownReceiver : android.content.BroadcastReceiver() {
    abstract val profileId: String

    override fun onReceive(context: Context?, intent: Intent?) {
        // Профиль просят освободить свои данные — корректно завершаем процесс.
        Handler(Looper.getMainLooper()).post {
            try {
                Process.killProcess(Process.myPid())
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}

class ShutdownReceiver1 : ProfileShutdownReceiver() {
    override val profileId = "p1"
}

class ShutdownReceiver2 : ProfileShutdownReceiver() {
    override val profileId = "p2"
}

class ShutdownReceiver3 : ProfileShutdownReceiver() {
    override val profileId = "p3"
}

class ShutdownReceiver4 : ProfileShutdownReceiver() {
    override val profileId = "p4"
}

class ShutdownReceiver5 : ProfileShutdownReceiver() {
    override val profileId = "p5"
}

/** Стирание данных профиля: сначала гасим процесс, потом удаляем каталог. */
object ProfileResetter {

    private const val ACTION_PREFIX = "ai.arena.mobile.action.SHUTDOWN_"

    @Suppress("DEPRECATION")
    fun reset(activity: android.app.Activity, profileId: String) {
        ProfileStore.markForWipe(activity, profileId)
        val targetProcess = activity.packageName + ":" + profileId
        val currentProcess = ArenaApp.currentProcessName(activity)
        val running = isProcessRunning(activity, targetProcess)

        if (running && currentProcess != targetProcess) {
            shutdownProcess(activity, profileId)
            Handler(Looper.getMainLooper()).postDelayed({
                ProfileStore.wipeProfileData(activity.applicationContext, profileId)
            }, 500L)
        } else if (currentProcess == targetProcess) {
            // Мы внутри самого профиля: каталог удалится при следующем старте,
            // cookie и storage уже очищены вызывающей стороной.
            ProfileStore.markForWipe(activity, profileId)
        } else {
            ProfileStore.wipeProfileData(activity.applicationContext, profileId)
        }
    }

    fun resetAll(activity: android.app.Activity) {
        ProfileStore.IDS.forEach { reset(activity, it) }
    }

    /** Очистка кэша профиля без выхода из аккаунта. */
    fun clearCache(activity: android.app.Activity, profileId: String) {
        ProfileStore.markCacheClean(activity, profileId)
        val targetProcess = activity.packageName + ":" + profileId
        val currentProcess = ArenaApp.currentProcessName(activity)

        if (currentProcess == targetProcess) return // сделает сам профиль, каталог почистим при старте
        if (isProcessRunning(activity, targetProcess)) {
            shutdownProcess(activity, profileId)
            Handler(Looper.getMainLooper()).postDelayed({
                ProfileStore.wipeProfileCache(activity.applicationContext, profileId)
            }, 500L)
        } else {
            ProfileStore.wipeProfileCache(activity.applicationContext, profileId)
        }
    }

    /** Довести до конца очистки кэша, отложенные ранее. */
    fun finalizePendingCacheCleans(activity: android.app.Activity) {
        val currentProcess = ArenaApp.currentProcessName(activity)
        ProfileStore.IDS.forEach { id ->
            if (!ProfileStore.isCacheCleanMarked(activity, id)) return@forEach
            val targetProcess = activity.packageName + ":" + id
            if (currentProcess == targetProcess) return@forEach
            if (isProcessRunning(activity, targetProcess)) {
                shutdownProcess(activity, id)
                Handler(Looper.getMainLooper()).postDelayed({
                    ProfileStore.wipeProfileCache(activity.applicationContext, id)
                }, 500L)
            } else {
                ProfileStore.wipeProfileCache(activity.applicationContext, id)
            }
        }
    }

    /** Довести до конца очистки, отложенные ранее (вызывается при возврате к списку профилей). */
    fun finalizePendingWipes(activity: android.app.Activity) {
        val currentProcess = ArenaApp.currentProcessName(activity)
        ProfileStore.IDS.forEach { id ->
            if (!ProfileStore.isMarkedForWipe(activity, id)) return@forEach
            val targetProcess = activity.packageName + ":" + id
            if (currentProcess == targetProcess) return@forEach
            if (isProcessRunning(activity, targetProcess)) {
                shutdownProcess(activity, id)
                Handler(Looper.getMainLooper()).postDelayed({
                    ProfileStore.wipeProfileData(activity.applicationContext, id)
                }, 500L)
            } else {
                ProfileStore.wipeProfileData(activity.applicationContext, id)
            }
        }
    }

    private fun isProcessRunning(activity: android.app.Activity, processName: String): Boolean = try {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.any { it.processName == processName } == true
    } catch (t: Throwable) {
        false
    }

    fun shutdownProcess(activity: android.app.Activity, profileId: String) {
        val receiver = when (profileId) {
            "p1" -> ShutdownReceiver1::class.java
            "p2" -> ShutdownReceiver2::class.java
            "p3" -> ShutdownReceiver3::class.java
            "p4" -> ShutdownReceiver4::class.java
            else -> ShutdownReceiver5::class.java
        }
        try {
            val intent = Intent(activity, receiver).setAction(ACTION_PREFIX + profileId)
            activity.sendBroadcast(intent)
        } catch (t: Throwable) {
            // ignore
        }
    }
}
