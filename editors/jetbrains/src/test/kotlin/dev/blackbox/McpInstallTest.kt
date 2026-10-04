package dev.blackbox

import dev.blackbox.launcher.McpInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Same cases as editors/vscode/src/test/unit/launcher.test.ts.
class McpInstallTest {
    @Test
    fun `compares versions numerically, pre-releases first`() {
        assertTrue(McpInstall.compareVersions("0.10.0", "0.9.9") > 0)
        assertEquals(0, McpInstall.compareVersions("1.2", "1.2.0"))
        assertTrue(McpInstall.compareVersions("1.0.0-beta", "1.0.0") < 0)
        assertTrue(McpInstall.compareVersions("1.0.0", "1.0.0-beta") > 0)
    }

    @Test
    fun `replaces the launcher only with a newer one, or when its install is gone`() {
        val stamp = McpInstall.launcherStamp("0.5.1", "/ext/server.js") + "\n// code"
        val alive = { _: String -> true }
        assertTrue(McpInstall.shouldReplaceLauncher(null, "0.5.0", alive))
        assertTrue(McpInstall.shouldReplaceLauncher("// no stamp", "0.5.0", alive))
        assertFalse(McpInstall.shouldReplaceLauncher(stamp, "0.5.1", alive))
        assertFalse(McpInstall.shouldReplaceLauncher(stamp, "0.5.0", alive))
        assertTrue(McpInstall.shouldReplaceLauncher(stamp, "0.6.0", alive))
        assertTrue(McpInstall.shouldReplaceLauncher(stamp, "0.5.0") { false })
    }

    @Test
    fun `reads the stamp the VS Code extension writes`() {
        assertEquals("0.5.1" to "/a b/server.js", McpInstall.readStamp("// blackbox-mcp launcher v0.5.1 from /a b/server.js\nrest"))
    }
}
