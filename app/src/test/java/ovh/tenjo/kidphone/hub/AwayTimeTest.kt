package ovh.tenjo.kidphone.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwayTimeTest {
    private val now = 1_000_000_000L
    private val hour = 60 * 60_000L

    /** Hub: "tool" on with no time limit, "game" on for half an hour. */
    private val hub = Switches(setOf("tool", "game"), mapOf("game" to now + hour / 2))

    @Test fun awayFromHomeOnlyTimeGivenThereOpens() {
        val away = Away().give("cards", now + hour)
        val apps = appsNow(hub, away, atHome = false, now)
        assertEquals(setOf("cards"), apps.open)
        assertEquals(mapOf("cards" to now + hour), apps.ends)
        assertEquals(mapOf("cards" to now + hour), apps.away)
    }

    @Test fun awayFromHomeWithoutTimeNothingOpens() {
        val apps = appsNow(hub, Away(), atHome = false, now)
        assertTrue(apps.open.isEmpty())
        assertNull(apps.nextChange)
    }

    @Test fun atHomeBothCount() {
        val apps = appsNow(hub, Away().give("cards", now + hour), atHome = true, now)
        assertEquals(setOf("tool", "game", "cards"), apps.open)
        assertEquals(mapOf("game" to now + hour / 2, "cards" to now + hour), apps.ends)
        assertEquals(now + hour / 2, apps.nextChange)
    }

    @Test fun timeThatRanOutIsGone() {
        val away = Away().give("cards", now + hour)
        val later = now + hour
        assertTrue(appsNow(hub, away, atHome = false, later).open.isEmpty())
        assertTrue(away.withoutEnded(later).times.isEmpty())
        assertEquals(away, away.withoutEnded(later - 1))
    }

    @Test fun timeNotSentYetIgnoresTheHub() {
        val away = Away().give("cards", now + hour)
        assertEquals(away, away.followHub(hub)) // the hub doesn't have "cards" on: it hasn't heard yet
    }

    @Test fun onceSentTheHubDecides() {
        val sent = Away(mapOf("game" to AwayTime(now + hour, sent = true)))
        // A parent switched it off in Kid Phone Parent, or its time ran out there.
        assertTrue(sent.followHub(Switches(setOf("tool"), emptyMap())).times.isEmpty())
        // A parent changed the time there.
        assertEquals(AwayTime(now + hour / 2, sent = true), sent.followHub(hub).times["game"])
        // With no time limit at home, it keeps its own end away from home.
        val noLimit = Switches(setOf("game"), emptyMap())
        assertEquals(AwayTime(now + hour, sent = true), sent.followHub(noLimit).times["game"])
    }

    @Test fun sentTimeWithNoLimitAtHomeHasNoEndAtHomeButEndsAway() {
        val noLimit = Switches(setOf("game"), emptyMap())
        val away = Away(mapOf("game" to AwayTime(now + hour, sent = true)))
        assertEquals(emptyMap<String, Long>(), appsNow(noLimit, away, atHome = true, now).ends)
        assertEquals(mapOf("game" to now + hour), appsNow(noLimit, away, atHome = false, now).ends)
    }

    @Test fun switchingOffTellsTheHubOnlyWhenItHasTheTime() {
        // Not sent yet: the hub never heard of it.
        assertEquals(Away(), Away().give("cards", now + hour).end("cards", hub))
        // Sent, and the hub has a time for it: switch it off there too.
        val sent = Away(mapOf("game" to AwayTime(now + hour / 2, sent = true)))
        assertEquals(Away(off = setOf("game")), sent.end("game", hub))
        // On with no time limit at home: that stays as it is.
        val tool = Away(mapOf("tool" to AwayTime(now + hour, sent = true)))
        assertEquals(Away(), tool.end("tool", hub))
        // Nothing to switch off.
        assertEquals(Away(), Away().end("cards", hub))
    }

    @Test fun switchedOffAwayStaysClosedAtHomeUntilTheHubKnows() {
        val away = Away(off = setOf("game"))
        assertEquals(setOf("tool"), appsNow(hub, away, atHome = true, now).open)
    }

    @Test fun givingAgainStartsOverAndCancelsASwitchOff() {
        val away = Away(off = setOf("game")).give("game", now + hour)
        assertEquals(Away(mapOf("game" to AwayTime(now + hour, sent = false))), away)
    }
}
