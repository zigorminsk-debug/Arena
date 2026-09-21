package ai.arena.mobile

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile

/**
 * Хранилище профилей в JSON-файле. Файл читается заново при каждом обращении,
 * поэтому данные одинаково видны из всех процессов приложения (у каждого
 * профиля свой процесс). Запись атомарная: tmp-файл + rename.
 */
object ProfileStore {

    val IDS = listOf("p1", "p2", "p3", "p4", "p5")

    val PALETTE = listOf(
        "#7C5CFF", "#22D3EE", "#F472B6", "#FBBF24",
        "#34D399", "#F87171", "#60A5FA", "#A78BFA",
    )

    private fun storeFile(ctx: Context) = File(ctx.filesDir, "profiles.json")

    private fun wipeFlag(ctx: Context, id: String) = File(ctx.filesDir, "wipe_$id")

    @Synchronized
    fun all(ctx: Context): MutableList<Profile> = withStoreLock(ctx) {
        allLocked(ctx)
    }

    @Synchronized
    fun get(ctx: Context, id: String): Profile = withStoreLock(ctx) {
        val list = allLocked(ctx)
        list.firstOrNull { it.id == id } ?: list.first()
    }

    @Synchronized
    fun update(ctx: Context, profile: Profile) = withStoreLock(ctx) {
        val list = allLocked(ctx)
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        writeLocked(ctx, list)
    }

    /** Запоминает последнюю страницу профиля, чтобы возвращаться «где остановился». */
    @Synchronized
    fun markUsed(ctx: Context, id: String, url: String?) = withStoreLock(ctx) {
        val list = allLocked(ctx)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@withStoreLock
        val profile = list[index]
        if (url != null && url == profile.lastUrl && System.currentTimeMillis() - profile.lastUsed < 5_000) {
            return@withStoreLock
        }
        list[index] = profile.copy(
            lastUsed = System.currentTimeMillis(),
            lastUrl = url ?: profile.lastUrl,
        )
        writeLocked(ctx, list)
    }

    @Synchronized
    fun lastUsed(ctx: Context): Profile = withStoreLock(ctx) {
        val list = allLocked(ctx)
        list.filter { it.lastUsed > 0L }.maxByOrNull { it.lastUsed } ?: list.first()
    }

    /** Читает профильный файл и при необходимости добавляет отсутствующие слоты. */
    private fun allLocked(ctx: Context): MutableList<Profile> {
        val file = storeFile(ctx)
        val list = mutableListOf<Profile>()
        if (file.exists()) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val profile = Profile.fromJson(array.getJSONObject(i))
                    if (profile.id.isNotEmpty()) list.add(profile)
                }
            } catch (t: Throwable) {
                list.clear()
            }
        }

        var changed = false
        IDS.forEachIndexed { index, id ->
            if (list.none { it.id == id }) {
                list.add(
                    Profile(
                        id = id,
                        name = ctx.getString(R.string.profile_default_name, index + 1),
                        color = PALETTE[index % PALETTE.size],
                        createdAt = System.currentTimeMillis(),
                    )
                )
                changed = true
            }
        }

        list.sortBy { profile -> IDS.indexOf(profile.id).let { if (it < 0) 99 else it } }
        if (changed) writeLocked(ctx, list)
        return list
    }

    /**
     * @Synchronized защищает только потоки одного процесса. Профили живут в
     * разных Android-процессах, поэтому для profiles.json нужен настоящий
     * межпроцессный lock — иначе markUsed одного профиля мог затереть настройки
     * другого, записанные почти одновременно.
     */
    private fun <T> withStoreLock(ctx: Context, block: () -> T): T {
        val lockFile = File(ctx.filesDir, "profiles.lock")
        return try {
            RandomAccessFile(lockFile, "rw").use { random ->
                val fileLock = random.channel.lock()
                try {
                    block()
                } finally {
                    fileLock.release()
                }
            }
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            // Повторный lock в одном JVM невозможен; публичные методы не
            // вкладываются, поэтому здесь безопасен локальный fallback.
            block()
        } catch (_: java.io.IOException) {
            // Не превращаем кратковременную ошибку файла в падение profile process.
            block()
        }
    }

    // ---------- очистка данных профиля ----------

    fun markForWipe(ctx: Context, id: String) {
        try {
            wipeFlag(ctx, id).writeText(System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun isMarkedForWipe(ctx: Context, id: String): Boolean = try {
        wipeFlag(ctx, id).exists()
    } catch (t: Throwable) {
        false
    }

    fun clearWipeFlag(ctx: Context, id: String) {
        try {
            wipeFlag(ctx, id).delete()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /**
     * Удаляет с диска все данные профиля. Вызывать только когда процесс профиля
     * не запущен (WebView держит каталог открытым).
     */
    @Synchronized
    fun wipeProfileData(ctx: Context, id: String) {
        try {
            val dataDir: File = ctx.dataDir ?: ctx.filesDir.parentFile
            val direct = File(dataDir, "app_webview_$id")
            deleteRecursively(direct)
            dataDir.listFiles()?.forEach { child ->
                if (child.isDirectory &&
                    child.name.startsWith("app_webview") &&
                    (child.name.endsWith("_$id") || child.name.endsWith("-$id"))
                ) {
                    deleteRecursively(child)
                }
            }
            deleteRecursively(File(ctx.cacheDir, "webview_$id"))
            // org.chromium.android_webview — общий каталог WebView, его
            // удаление при сбросе одного профиля ломает остальные профили.
            deleteRecursively(File(ctx.filesDir, "webview_$id"))
        } catch (t: Throwable) {
            // ignore
        }
        clearWipeFlag(ctx, id)
    }

    /** Выполняет отложенные очистки — вызывается в главном процессе при старте. */
    fun runPendingWipes(ctx: Context) {
        IDS.forEach { id ->
            if (isMarkedForWipe(ctx, id)) wipeProfileData(ctx, id)
        }
    }

    // ---------- размер данных и очистка кэша (вход сохраняется) ----------

    /** Каталоги, где WebView держит данные профиля. */
    fun profileDirs(ctx: Context, id: String): List<File> {
        val result = linkedSetOf<File>()
        val dataDir: File = ctx.dataDir ?: ctx.filesDir.parentFile
        result.add(File(dataDir, "app_webview_$id"))
        result.add(File(ctx.cacheDir, "webview_$id"))
        dataDir.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith("app_webview") &&
                (child.name.endsWith("_$id") || child.name.endsWith("-$id"))
            ) {
                result.add(child)
            }
        }
        return result.toList()
    }

    fun dataSize(ctx: Context, id: String): Long = profileDirs(ctx, id).sumOf { dirSize(it) }

    private fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += dirSize(it) }
        return total
    }

    private val CACHE_PATHS = listOf(
        "Cache", "Code Cache", "GPUCache", "blob_storage",
        "Default/Cache", "Default/Code Cache", "Default/GPUCache",
        "Default/blob_storage", "Default/Service Worker/CacheStorage",
    )

    fun markCacheClean(ctx: Context, id: String) {
        try {
            File(ctx.filesDir, "cache_$id").writeText(System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun isCacheCleanMarked(ctx: Context, id: String): Boolean = try {
        File(ctx.filesDir, "cache_$id").exists()
    } catch (t: Throwable) {
        false
    }

    private fun clearCacheFlag(ctx: Context, id: String) {
        try {
            File(ctx.filesDir, "cache_$id").delete()
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun runPendingCacheCleans(ctx: Context) {
        IDS.forEach { id ->
            if (isCacheCleanMarked(ctx, id)) wipeProfileCache(ctx, id)
        }
    }

    /** Удаляет только кэш: cookies, localStorage и входы остаются. */
    @Synchronized
    fun wipeProfileCache(ctx: Context, id: String) {
        try {
            profileDirs(ctx, id).forEach { dir ->
                CACHE_PATHS.forEach { relative ->
                    deleteRecursively(File(dir, relative))
                }
            }
        } catch (t: Throwable) {
            // ignore
        }
        clearCacheFlag(ctx, id)
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        try {
            if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
            file.delete()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun writeLocked(ctx: Context, list: List<Profile>) {
        try {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            val file = storeFile(ctx)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            // ignore
        }
    }
}
