package dev.blackbox

import com.google.gson.JsonParser
import dev.blackbox.ipc.AppInfo
import dev.blackbox.ipc.BridgeServer
import dev.blackbox.ipc.Protocol
import dev.blackbox.ipc.ToolError
import dev.blackbox.ipc.ToolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class BridgeServerTest {
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<BridgeServer>()

    private val handlers: Map<String, ToolHandler> = mapOf(
        "echo" to { args -> args.get("text")?.asString ?: "" },
        "slow" to { _ -> delay(300); "late" },
        "refuse" to { _ -> throw ToolError("no active debug session") },
    )

    @Before
    fun setUp() {
        // Short: macOS limits socket paths to 104 bytes.
        dir = Files.createTempDirectory(Path.of(if (Protocol.isWindows) System.getProperty("java.io.tmpdir") else "/tmp"), "bbx-")
    }

    @After
    fun tearDown() {
        servers.forEach { it.dispose() }
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun start(useTcp: Boolean = false): BridgeServer =
        BridgeServer(scope, "4242-ab", listOf("/work"), AppInfo("IntelliJ IDEA", "jetbrains", "2024.3"), "0.1.0", handlers, dir, useTcp)
            .also { it.start(); servers += it }

    private fun connect(socket: String): SocketChannel =
        if (socket.startsWith("tcp://")) {
            val port = socket.removePrefix("tcp://127.0.0.1:").substringBefore('/').toInt()
            SocketChannel.open(InetSocketAddress("127.0.0.1", port))
        } else {
            SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(socket)) }
        }

    // Sends the lines on one connection and reads as many response lines.
    private fun exchange(socket: String, vararg lines: String): List<String> = connect(socket).use { channel ->
        channel.write(ByteBuffer.wrap(lines.joinToString("") { "$it\n" }.toByteArray()))
        val buffer = ByteBuffer.allocate(64 * 1024)
        val text = StringBuilder()
        while (text.count { it == '\n' } < lines.size && channel.read(buffer) >= 0) {
            buffer.flip()
            text.append(Charsets.UTF_8.decode(buffer))
            buffer.clear()
        }
        text.lines().filter { it.isNotEmpty() }
    }

    private fun field(response: String, name: String) =
        JsonParser.parseString(response).asJsonObject.get(name)?.takeIf { !it.isJsonNull }?.asString

    @Test
    fun `writes a registry entry the MCP server can read`() {
        val server = start()
        val entry = JsonParser.parseString(Files.readString(dir.resolve("4242-ab.json"))).asJsonObject
        assertEquals("4242-ab", entry["id"].asString)
        assertEquals(ProcessHandle.current().pid(), entry["pid"].asLong)
        assertEquals(entry["pid"].asLong, entry["appPid"].asLong)
        assertEquals(server.entry.socket, entry["socket"].asString)
        assertEquals(listOf("/work"), entry["folders"].asJsonArray.map { it.asString })
        assertEquals("IntelliJ IDEA", entry["app"].asJsonObject["name"].asString)
        assertEquals("jetbrains", entry["app"].asJsonObject["scheme"].asString)
        assertEquals(Protocol.VERSION, entry["protocol"].asInt)
        assertTrue(entry.has("startedAt") && entry.has("focusedAt") && entry.has("extensionVersion"))
    }

    @Test
    fun `answers requests, several per connection, in any order`() {
        val server = start()
        val responses = exchange(
            server.entry.socket,
            """{"id":"1","tool":"slow","args":{}}""",
            """{"id":"2","tool":"echo","args":{"text":"hi"}}""",
        )
        assertEquals(listOf("2", "1"), responses.map { field(it, "id") })
        assertEquals("hi", field(responses[0], "result"))
        assertEquals("late", field(responses[1], "result"))
    }

    @Test
    fun `reports unknown tools and tool errors`() {
        val server = start()
        val (unknown, refused) = exchange(
            server.entry.socket,
            """{"id":"1","tool":"nope","args":{}}""",
            """{"id":"2","tool":"refuse","args":{}}""",
        ).sortedBy { field(it, "id") }
        assertEquals("unknown tool: nope", field(unknown, "error"))
        assertEquals("no active debug session", field(refused, "error"))
        assertNull(field(refused, "result"))
    }

    @Test
    fun `over TCP, answers only requests with the token`() {
        val server = start(useTcp = true)
        val socket = server.entry.socket
        assertTrue(socket, Regex("""tcp://127\.0\.0\.1:\d+/[0-9a-f]{64}""").matches(socket))
        val token = socket.substringAfterLast('/')
        val (wrong, right) = exchange(
            socket,
            """{"id":"1","tool":"echo","args":{"text":"x"},"token":"bad"}""",
            """{"id":"2","tool":"echo","args":{"text":"x"},"token":"$token"}""",
        ).sortedBy { field(it, "id") }
        assertEquals("invalid token", field(wrong, "error"))
        assertEquals("x", field(right, "result"))
    }

    @Test
    fun `cleans up its socket and registry entry`() {
        val server = start()
        server.dispose()
        assertFalse(Files.exists(dir.resolve("4242-ab.json")))
        assertFalse(Files.exists(Path.of(server.entry.socket)))
    }

    @Test
    fun `refuses a folder other users can reach`() {
        assumeFalse(Protocol.isWindows)
        val open = Files.createDirectory(dir.resolve("open"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertTrue(Protocol.privateDirProblem(open)!!.contains("accessible to other users"))
        Protocol.ensurePrivateDir(open) // Tightens a folder this user owns.
        assertNull(Protocol.privateDirProblem(open))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(open)))
    }
}
