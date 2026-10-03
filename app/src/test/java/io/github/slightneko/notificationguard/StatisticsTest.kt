package io.github.slightneko.notificationguard

import io.github.slightneko.notificationguard.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StatisticsTest {
    @Test fun aggregatesChannelsWithoutMergingUsers() {
        val rows = listOf(
            StatRow(0,"test.app","ads","App","Ads",10,8,2),
            StatRow(0,"test.app","chat","App","Chat",3,0,1),
            StatRow(10,"test.app","ads","App","Ads",2,2,0),
        )
        val result = aggregateApps(rows)
        assertEquals(2,result.size)
        assertEquals(13L,result[0].attempts)
        assertEquals(8L,result[0].blocked)
        assertEquals(3L,result[0].updates)
        assertEquals(10,result[1].user)
    }
    @Test fun channelKeysIncludeAppUserAndChannel() {
        assertNotEquals(ChannelKey(0,"a","ads"),ChannelKey(10,"a","ads"))
        assertNotEquals(ChannelKey(0,"a","ads"),ChannelKey(0,"b","ads"))
        assertNotEquals(ChannelKey(0,"a","ads"),ChannelKey(0,"a","chat"))
        assertEquals(ChannelKey(0,"a",""),ChannelKey(0,"a",null))
    }
    @Test fun emptyStatisticsAreEmpty() { assertTrue(aggregateApps(emptyList()).isEmpty()) }
    @Test fun api102AndScopesArePinned() {
        val prop = File("src/main/resources/META-INF/xposed/module.prop").readText()
        assertTrue(prop.contains("minApiVersion=102")); assertTrue(prop.contains("targetApiVersion=102"))
        assertEquals(setOf("android","com.android.systemui"),File("src/main/resources/META-INF/xposed/scope.list").readLines().filter { it.isNotBlank() }.toSet())
    }
}
