package ovh.tenjo.kidphone.hub

/*
 * Time a parent gives with the PIN on his phone while away from the home Wi-Fi. At home every
 * switch goes through the hub; away from it the phone can't reach the hub, so it keeps that time
 * itself, and the app opens anywhere until it runs out. Back home, the phone tells the hub (see
 * Hub.sendAwayTime), so Kid Phone Parent shows the same, and from then on the hub decides: a parent
 * switching the app off or changing its time there counts on his phone too.
 *
 * Apps switched on at home still only open at home. These are plain functions, so the rules can be
 * tested without a phone.
 */

/**
 * The apps switched on at the hub, as last heard from it, and when the time is up for those
 * switched on for a while (package → wall clock on this phone; none: no time limit).
 */
internal data class Switches(val on: Set<String>, val ends: Map<String, Long>)

/** Time given with the PIN away from home: the app opens anywhere until [endsAt]. [sent]: the hub has it. */
internal data class AwayTime(val endsAt: Long, val sent: Boolean)

/**
 * The time given away from home, per app, and the apps switched off that way whose time the hub
 * already has ([off]), to be switched off there too once the phone is back home.
 */
internal data class Away(val times: Map<String, AwayTime> = emptyMap(), val off: Set<String> = emptySet()) {

    /** A parent away from home gives [pkg] until [endsAt]. */
    fun give(pkg: String, endsAt: Long) = Away(times + (pkg to AwayTime(endsAt, sent = false)), off - pkg)

    /**
     * A parent away from home switches [pkg] off. If the hub has its time, the hub is told too;
     * an app on with no time limit at home stays so (that switch was never part of this).
     */
    fun end(pkg: String, hub: Switches): Away {
        val time = times[pkg] ?: return this
        val hubHasIt = time.sent && pkg in hub.on && pkg in hub.ends
        return Away(times - pkg, if (hubHasIt) off + pkg else off)
    }

    /**
     * The hub's switches came in. For time the hub already has, the hub decides: off there
     * (a parent switched it off, or the time ran out) takes it off here; a new time there moves it.
     * With no time limit at home it keeps its own end away from home. Time not sent yet stays.
     */
    fun followHub(hub: Switches) = copy(
        times = times.mapNotNull { (pkg, time) ->
            when {
                !time.sent -> pkg to time
                pkg !in hub.on -> null
                else -> pkg to time.copy(endsAt = hub.ends[pkg] ?: time.endsAt)
            }
        }.toMap(),
    )

    /** Without the time that has run out. */
    fun withoutEnded(now: Long) = copy(times = times.filterValues { it.endsAt > now })
}

/**
 * What he can open now ([open]), and when each one's time is up ([ends]; none: no time limit).
 * [away] is the time given away from home that is still running (package → end), which is what the
 * parent menu shows and changes away from home. [nextChange] is when this next changes by itself.
 */
internal data class AppsNow(
    val open: Set<String>,
    val ends: Map<String, Long>,
    val away: Map<String, Long>,
    val nextChange: Long?,
)

/**
 * The apps he may open at [now]. [atHome]: on the home Wi-Fi, or only just left it; only then do
 * the hub's switches count. Time given away from home counts anywhere.
 */
internal fun appsNow(hub: Switches, away: Away, atHome: Boolean, now: Long): AppsNow {
    val home = if (atHome) {
        hub.on.filterTo(mutableSetOf()) { it !in away.off && (hub.ends[it] ?: Long.MAX_VALUE) > now }
    } else {
        emptySet()
    }
    val running = away.times.filterValues { it.endsAt > now }
    val ends = HashMap<String, Long>()
    home.forEach { pkg -> hub.ends[pkg]?.let { ends[pkg] = it } }
    // At home, once the hub has an app's time, the hub's time is the one (no limit there: none).
    running.forEach { (pkg, time) -> if (!(pkg in home && time.sent)) ends[pkg] = time.endsAt }
    val next = (home.mapNotNull { hub.ends[it] } + running.values.map { it.endsAt }).minOrNull()
    return AppsNow(home + running.keys, ends, running.mapValues { it.value.endsAt }, next)
}
