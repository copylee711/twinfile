package app.twinfile.pc

import app.twinfile.pc.AdbQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbQrTest {
    @Test
    fun parsesThePcQr() {
        val p = AdbQr.parse("WIFI:T:ADB;S:sb-0ac342ea-0a6414f6-k7m2pq;P:abc_DEF-123;;")!!
        assertEquals(listOf("10.195.66.234", "10.100.20.246"), p.hosts)
        assertEquals("abc_DEF-123", p.token)
        assertEquals("syncboard://pair?v=1&h=10.195.66.234,10.100.20.246&p=53317&t=abc_DEF-123", AdbQr.toPairUri(p))
    }

    @Test
    fun ignoresOtherQrs() {
        assertNull(AdbQr.parse("WIFI:T:ADB;S:studio-abc;P:123456;;"))
        assertNull(AdbQr.parse("WIFI:T:WPA;S:sb-0ac342ea-x;P:pw;;"))
        assertEquals(emptyList<String>(), AdbQr.hostsOf("adb-1234-xyz"))
    }
}
