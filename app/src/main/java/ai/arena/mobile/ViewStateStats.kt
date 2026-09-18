package ai.arena.mobile

/**
 * Счётчики текущего сеанса экрана профиля: сколько раз экран создавался,
 * сколько было изменений конфигурации (поворотов), сколько раз загружалась
 * страница и случалось ли восстановление черновика после поворота.
 *
 * Нужны для диагностики на реальном устройстве: по ним видно, пересоздаётся ли
 * экран профиля при повороте (тогда виноват не сайт, а приложение) и работает
 * ли защита черновика.
 */
object ViewStateStats {

    private val startedAt = System.currentTimeMillis()

    @Volatile
    var activityCreates: Int = 0
        private set

    @Volatile
    var configChanges: Int = 0
        private set

    @Volatile
    var lastConfigChange: String? = null
        private set

    @Volatile
    var lastConfigChangeAt: Long = 0L
        private set

    @Volatile
    var pageLoads: Int = 0
        private set

    @Volatile
    var draftRestores: Int = 0
        private set

    @Volatile
    var lastDraftRestoreAt: Long = 0L
        private set

    fun onActivityCreate() {
        activityCreates++
    }

    fun onConfigChange(description: String) {
        configChanges++
        lastConfigChange = description
        lastConfigChangeAt = System.currentTimeMillis()
    }

    fun onPageLoad() {
        pageLoads++
    }

    fun onDraftRestored() {
        draftRestores++
        lastDraftRestoreAt = System.currentTimeMillis()
    }

    val uptimeMs: Long get() = System.currentTimeMillis() - startedAt

    /** Человеческое описание конфигурации: что именно изменилось. */
    fun describe(config: android.content.res.Configuration): String {
        val orientation = when (config.orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "альбомная"
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> "портретная"
            else -> "неизвестная"
        }
        return "ориентация $orientation, ширина ${config.screenWidthDp}dp, высота ${config.screenHeightDp}dp"
    }
}
