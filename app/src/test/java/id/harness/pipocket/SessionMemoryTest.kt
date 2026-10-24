package id.harness.pipocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切换机器别把会话弄丢」的纯数据部分：记住每台机器上次看的会话。
 * 真正打开会话在 AppState（只拉列表 + 恢 UI，不发任何停止指令）。
 */
class SessionMemoryTest {

    @Test
    fun rememberAndRecallRoundTrip() {
        var map = emptyMap<String, String>()
        map = SessionMemory.remember(map, "http://192.0.2.10:8789", "/home/u/.pi/agent/sessions/a.jsonl")
        map = SessionMemory.remember(map, "http://127.0.0.1:8789", "/data/data/com.termux/files/home/x.jsonl")

        assertEquals("/home/u/.pi/agent/sessions/a.jsonl", SessionMemory.recall(map, "http://192.0.2.10:8789"))
        assertEquals("/data/data/com.termux/files/home/x.jsonl", SessionMemory.recall(map, "http://127.0.0.1:8789"))
        assertNull(SessionMemory.recall(map, "http://10.0.0.9:8789"))
    }

    @Test
    fun blankInputsAreIgnored() {
        val map = SessionMemory.remember(emptyMap(), "  ", "/a.jsonl")
        assertTrue(map.isEmpty())
        val map2 = SessionMemory.remember(emptyMap(), "http://x:8789", "   ")
        assertTrue(map2.isEmpty())
    }

    @Test
    fun newerEntryWinsAndOldOneIsKept() {
        var map = SessionMemory.remember(emptyMap(), "http://x:8789", "/old.jsonl")
        map = SessionMemory.remember(map, "http://x:8789", "/new.jsonl")
        assertEquals("/new.jsonl", SessionMemory.recall(map, "http://x:8789"))
        assertEquals(1, map.size)
    }

    @Test
    fun limitKeepsTheMapSmall() {
        var map = emptyMap<String, String>()
        for (i in 0 until SessionMemory.LIMIT + 5) {
            map = SessionMemory.remember(map, "http://host$i:8789", "/s$i.jsonl")
        }
        assertEquals(SessionMemory.LIMIT, map.size)
        // 最近记的那台一定在
        assertEquals("/s${SessionMemory.LIMIT + 4}.jsonl", SessionMemory.recall(map, "http://host${SessionMemory.LIMIT + 4}:8789"))
    }

    @Test
    fun encodeDecodeSurvivesPrefs() {
        val map = SessionMemory.remember(emptyMap(), "http://host-a:8789", "/home/u/s.jsonl")
        val raw = SessionMemory.encode(map)
        assertEquals(map, SessionMemory.decode(raw))
        assertTrue(SessionMemory.decode(null).isEmpty())
        assertTrue(SessionMemory.decode("not json").isEmpty())
        assertTrue(SessionMemory.decode("{}").isEmpty())
    }
}
