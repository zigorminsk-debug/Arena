package ai.arena.mobile

/**
 * Разделы Arena, которые показываются в меню профиля.
 * Набор настраивается отдельно для каждого профиля (Profile.sections).
 */
object ProfileSections {

    const val CHAT = "chat"
    const val AGENT = "agent"
    const val LEADERBOARD = "leaderboard"
    const val HISTORY = "history"
    const val REPOS = "repos"
    const val HELP = "help"

    val ALL = listOf(CHAT, AGENT, LEADERBOARD, HISTORY, REPOS, HELP)

    /** Разделы, которые нельзя отключить: без них меню теряет смысл. */
    val ALWAYS = listOf(CHAT)

    val DEFAULT = ALL

    fun labelRes(id: String): Int = when (id) {
        CHAT -> R.string.menu_arena_chat
        AGENT -> R.string.menu_agent_github
        LEADERBOARD -> R.string.menu_leaderboard
        HISTORY -> R.string.menu_arena_history
        REPOS -> R.string.menu_github_repos
        else -> R.string.link_help
    }

    /** Приводит список в корректный вид: порядок как в ALL, всегда есть CHAT. */
    fun normalize(ids: List<String>): List<String> {
        val filtered = ALL.filter { ids.contains(it) }
        return if (filtered.isEmpty()) DEFAULT else filtered
    }

    fun url(id: String, profile: Profile): String = when (id) {
        CHAT -> Links.HOME
        AGENT -> Links.AGENT
        LEADERBOARD -> Links.LEADERBOARD
        HISTORY -> Links.HISTORY
        REPOS -> Links.githubRepos(profile.github)
        else -> Links.HELP
    }
}
