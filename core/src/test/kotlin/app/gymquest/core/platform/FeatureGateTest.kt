package app.gymquest.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureGateTest {
    @Test
    fun theWholeTrainingLoopWorksOffline() {
        for (f in listOf(Feature.CAMERA_COUNTING, Feature.VOICE_COACH, Feature.BOSS_FIGHTS, Feature.GPS_TRACKING, Feature.MAP_DOWNLOADED,
            Feature.FOOD_LOG, Feature.WATER_SKINCARE, Feature.DIARY, Feature.LOCAL_MUSIC, Feature.MISSIONS, Feature.CHARACTER, Feature.SLEEP_BATTERY)) {
            val a = FeatureGate.check(f, online = false)
            assertTrue(a.enabled && !a.limited, f.name)
            assertNull(a.message)
        }
    }

    @Test
    fun featuresThatNeedTheNetworkSwitchOffAndSayWhy() {
        for (f in listOf(Feature.MARKET, Feature.TRADES, Feature.CHAT_IMAGES, Feature.GUILD_WAR_LIVE, Feature.STREAMING_MUSIC)) {
            val off = FeatureGate.check(f, online = false)
            assertTrue(!off.enabled, f.name)
            assertNotNull(off.message)
            assertTrue(FeatureGate.check(f, online = true).enabled)
        }
    }

    @Test
    fun limitedFeaturesStayOnButExplainTheLimit() {
        val chat = FeatureGate.check(Feature.CHAT_TEXT, online = false)
        assertTrue(chat.enabled && chat.limited)
        assertTrue(chat.message!!.contains("conexión"))
        val online = FeatureGate.check(Feature.CHAT_TEXT, online = true)
        assertTrue(online.enabled && !online.limited && online.message == null)
    }

    @Test
    fun everyOptionalFeatureHasAnExplanation() {
        for (f in Feature.entries) if (f.need == NetworkNeed.OPTIONAL) assertNotNull(f.offlineNote, f.name)
    }

    @Test
    fun summariesListWhatIsLostOffline() {
        val all = FeatureGate.all(online = false)
        assertEquals(Feature.entries.size, all.size)
        assertEquals(FeatureGate.disabledOffline().toSet(), all.filter { !it.enabled }.map { it.feature }.toSet())
        assertTrue(FeatureGate.limitedOffline().isNotEmpty())
        assertTrue(FeatureGate.all(online = true).all { it.enabled })
        assertTrue(Feature.entries.count { it.need == NetworkNeed.NONE } > Feature.entries.count { it.need == NetworkNeed.REQUIRED })
    }
}
