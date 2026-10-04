// Installs the MCP server that ships inside the plugin, the way the VS Code
// extension does (editors/vscode/src/launcher/blackboxMcp.ts), so MCP
// clients use the same entry point whichever editors are installed:
//
//   { "command": "node", "args": ["<home>/.blackbox/blackbox-mcp.js"] }
//
// The server is copied out of the plugin to ~/.blackbox/jetbrains/<version>/,
// recorded in ~/.blackbox/installs/, and the launcher replaced only by a
// newer version (or when the install that wrote it is gone).

package dev.blackbox.launcher

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.diagnostic.logger
import dev.blackbox.ipc.Protocol
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

object McpInstall {
    const val LAUNCHER_NAME = "blackbox-mcp.js"
    private const val RESOURCES = "/blackbox-mcp"
    private val LOG = logger<McpInstall>()

    fun home(env: Map<String, String> = System.getenv()): Path =
        env["BLACKBOX_HOME"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".blackbox")

    fun launcher(home: Path = home()): Path = home.resolve(LAUNCHER_NAME)

    // Version of the bundled server (the VS Code extension's), or null when
    // the plugin was built without it.
    fun bundledVersion(): String? = resource("version.txt")?.toString(Charsets.UTF_8)?.trim()

    private fun resource(name: String): ByteArray? = McpInstall::class.java.getResourceAsStream("$RESOURCES/$name")?.use { it.readBytes() }

    @Synchronized
    fun install(pluginVersion: String, home: Path = home()) {
        val version = bundledVersion() ?: return LOG.warn("blackbox: this build has no bundled MCP server")
        try {
            val dir = home.resolve("jetbrains").resolve("$version-$pluginVersion")
            val server = dir.resolve("mcp").resolve("server.js")
            if (!Files.exists(server)) {
                Files.createDirectories(server.parent)
                writeAtomic(dir.resolve("tools.json"), resource("tools.json")!!)
                writeAtomic(server, resource("mcp/server.js")!!)
            }
            record(home, version, server.toString(), ApplicationNamesInfo.getInstance().fullProductName)
        } catch (e: Exception) {
            LOG.warn("blackbox: could not install the MCP launcher", e)
        }
    }

    // Port of recordInstall(): one record file per install, records of
    // installs that are gone removed, the launcher replaced only by a newer one.
    fun record(home: Path, version: String, server: String, app: String) {
        val installs = home.resolve("installs")
        Files.createDirectories(installs)
        if (!Protocol.isWindows) Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))

        val key = if (Protocol.isWindows) server.lowercase() else server
        val id = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val record = mapOf("version" to version, "server" to server, "app" to app, "recordedAt" to System.currentTimeMillis())
        writeAtomic(installs.resolve("$id.json"), (Protocol.gson.toJson(record) + "\n").toByteArray())
        Files.list(installs).use { files ->
            files.filter { it.toString().endsWith(".json") }.forEach { file ->
                val recorded = runCatching { Protocol.gson.fromJson(Files.readString(file), Map::class.java)["server"] as? String }.getOrNull()
                if (recorded != null && !Files.exists(Path.of(recorded))) runCatching { Files.delete(file) }
            }
        }

        val launcher = launcher(home)
        val current = if (Files.exists(launcher)) Files.readString(launcher) else null
        if (shouldReplaceLauncher(current, version)) {
            writeAtomic(launcher, "${launcherStamp(version, server)}\n${resource("launcher.js")!!.toString(Charsets.UTF_8)}".toByteArray())
        }
    }

    private val STAMP = Regex("""^// blackbox-mcp launcher v(\S+) from (.+)$""")

    fun launcherStamp(version: String, server: String) = "// blackbox-mcp launcher v$version from $server"

    fun readStamp(content: String?): Pair<String, String>? =
        content?.lineSequence()?.firstOrNull()?.let { STAMP.matchEntire(it) }?.let { it.groupValues[1] to it.groupValues[2] }

    // Replace the installed launcher only with a newer one, or when the
    // install that wrote it is gone. Never downgrade a live one.
    fun shouldReplaceLauncher(current: String?, version: String, exists: (String) -> Boolean = { Files.exists(Path.of(it)) }): Boolean {
        val (stampVersion, stampServer) = readStamp(current) ?: return true
        return compareVersions(version, stampVersion) > 0 || !exists(stampServer)
    }

    // Compares "1.2.3" style versions numerically; a pre-release suffix
    // ("1.2.3-beta") sorts before the plain version.
    fun compareVersions(a: String, b: String): Int {
        fun parse(v: String): Pair<List<Int>, String?> {
            val core = v.substringBefore('-')
            val pre = if ('-' in v) v.substringAfter('-') else null
            return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (x, xPre) = parse(a)
        val (y, yPre) = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return when {
            xPre == yPre -> 0
            xPre == null -> 1
            yPre == null -> -1
            else -> xPre.compareTo(yPre).coerceIn(-1, 1)
        }
    }

    private fun writeAtomic(file: Path, content: ByteArray) {
        val tmp = file.resolveSibling("${file.fileName}.${ProcessHandle.current().pid()}.${System.nanoTime()}.tmp")
        Files.write(tmp, content)
        if (!Protocol.isWindows) Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    // Absolute path of the user's node, for MCP configs: apps started from the
    // Dock often lack nvm & co. on their PATH. Falls back to plain "node".
    fun findNode(): String = try {
        val command = if (Protocol.isWindows) listOf("where", "node")
        else listOf(System.getenv("SHELL") ?: "/bin/sh", "-lc", "command -v node")
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.outputStream.close()
        val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        process.inputStream.bufferedReader().readLines().map { it.trim() }.firstOrNull { Path.of(it).isAbsolute } ?: "node"
    } catch (_: Exception) {
        "node"
    }
}
