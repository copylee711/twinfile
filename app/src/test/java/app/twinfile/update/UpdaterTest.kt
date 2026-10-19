package app.twinfile.update

import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updater.compare("0.10.0", "0.9.9") > 0)
        assertTrue(Updater.compare("0.2.0", "0.2") == 0)
        assertTrue(Updater.compare("1.0.0-beta", "1.0.0") < 0)
        assertTrue(Updater.compare("0.1.0", "0.2.0") < 0)
    }
}
