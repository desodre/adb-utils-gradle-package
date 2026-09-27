package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.model.LogcatFormat
import io.github.desodre.adbutils.model.LogcatPriority
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LogcatParserTest {
    @Test fun `epoch parser tolerates banners and flushes final partial line`() {
        val parser = LogcatParser(LogcatFormat.EPOCH, 1024)

        val first = parser.accept("--------- beginning of main\r\n1720000000.5 12 ".encodeToByteArray())
        val second = parser.accept("34 W Demo: warning\nlast".encodeToByteArray())
        val final = parser.finish()

        assertEquals("--------- beginning of main", first.single().rawLine)
        assertEquals(Instant.ofEpochSecond(1_720_000_000, 500_000_000), second.single().timestamp)
        assertEquals(LogcatPriority.WARN, second.single().priority)
        assertEquals("Demo", second.single().tag)
        assertEquals("warning", second.single().message)
        assertEquals("last", final.single().message)
    }
}
