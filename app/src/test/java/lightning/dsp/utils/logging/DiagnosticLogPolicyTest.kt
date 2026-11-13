package lightning.dsp.utils.logging

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogPolicyTest {
    @Test fun defaultOffRetainsOnlyWarningsAndErrors() {
        for (priority in 2..7) assertEquals(priority >= 5, DiagnosticLogPolicy.shouldLog(false, priority))
    }
    @Test fun runtimeToggleFiltersBothConsoleAndFileDelegatesWithoutRecreatingWriters() {
        var detailed = false
        val lines = mutableListOf<String>()
        val sink = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { lines.add(message) }
        }
        val tree = DiagnosticTree(sink) { detailed }
        tree.d("hidden")
        tree.w("warning")
        detailed = true
        tree.d("visible")
        detailed = false
        tree.d("hidden-again")
        tree.e("error")
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("warning"))
        assertTrue(lines[1].contains("visible"))
        assertTrue(lines[2].contains("error"))
    }

    @Test fun explicitOptInAllowsDetailedLogs() {
        for (priority in 2..7) assertTrue(DiagnosticLogPolicy.shouldLog(true, priority))
    }
}
