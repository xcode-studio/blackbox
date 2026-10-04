// One project window's side of the Blackbox bridge (mirrors IPCServer in
// editors/vscode/src/ipc/server.ts): a socket the MCP server connects to,
// plus a registry entry describing the window. Requests are newline-
// delimited JSON: {id, tool, args} -> {id, result} | {id, error}.

package dev.blackbox.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

typealias ToolHandler = suspend (args: JsonObject) -> String

// Thrown by handlers for errors the AI should see as-is.
class ToolError(message: String) : Exception(message)

class BridgeServer(
    private val scope: CoroutineScope,
    private val id: String,
    folders: List<String>,
    app: AppInfo,
    extensionVersion: String,
    private val handlers: Map<String, ToolHandler>,
    private val dir: Path = Protocol.ipcDir(),
    private val useTcp: Boolean = Protocol.isWindows,
    private val healthCheckMs: Long = HEALTH_CHECK_MS,
) {
    private val pid = ProcessHandle.current().pid()
    private val now = System.currentTimeMillis()
    private val token = if (useTcp) Protocol.randomHex(32) else null

    @Volatile
    var entry = RegistryEntry(
        id = id,
        pid = pid,
        socket = if (useTcp) "" else Protocol.socketPath(dir, id).toString(),
        folders = folders,
        startedAt = now,
        focusedAt = now,
        app = app,
        // An IDE is one process for all its project windows.
        appPid = pid,
        extensionVersion = extensionVersion,
    )
        private set

    private var channel: ServerSocketChannel? = null
    private var socketKey: Any? = null // file identity of our socket, to notice it being replaced
    private val connections = ConcurrentHashMap.newKeySet<SocketChannel>()
    private var acceptJob: Job? = null
    private var healthJob: Job? = null
    private val registryLock = Mutex()

    @Volatile
    private var disposed = false

    fun start() {
        Protocol.ensurePrivateDir(dir)
        listen()
        writeRegistry()
        healthJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(healthCheckMs)
                checkHealth()
            }
        }
    }

    private fun listen() {
        val server: ServerSocketChannel
        if (useTcp) {
            server = ServerSocketChannel.open(StandardProtocolFamily.INET)
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val port = (server.localAddress as InetSocketAddress).port
            entry = entry.copy(socket = "tcp://127.0.0.1:$port/$token")
        } else {
            val path = Path.of(entry.socket)
            Files.deleteIfExists(path) // Leftover of a previous IDE with this id.
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server.bind(UnixDomainSocketAddress.of(path))
            socketKey = fileKey(path)
        }
        channel = server
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break // Closed.
                }
                connections += client
                launch { serve(client) }
            }
        }
    }

    // Reads and writes the channel directly: the java.nio.channels.Channels
    // stream adapters would make a write wait for a blocked read.
    private suspend fun serve(client: SocketChannel) {
        val writeLock = Mutex()
        val buffer = ByteBuffer.allocate(64 * 1024)
        val pending = java.io.ByteArrayOutputStream()
        try {
            while (client.read(buffer.clear()) >= 0) {
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val b = buffer.get()
                    if (b == NEWLINE) {
                        val line = pending.toString(Charsets.UTF_8)
                        pending.reset()
                        scope.launch { handle(line, client, writeLock) }
                    } else {
                        pending.write(b.toInt())
                    }
                }
            }
        } catch (_: Exception) {
            // Client went away.
        } finally {
            connections -= client
        }
    }

    private fun sameToken(given: String?, expected: String) =
        given != null && java.security.MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())

    private suspend fun handle(line: String, client: SocketChannel, writeLock: Mutex) {
        val request = try {
            Protocol.gson.fromJson(line, IpcRequest::class.java) ?: return
        } catch (_: JsonParseException) {
            return
        }
        val response = when {
            token != null && !sameToken(request.token, token) -> IpcResponse(request.id, error = "invalid token")
            else -> {
                val handler = handlers[request.tool]
                if (handler == null) {
                    IpcResponse(request.id, error = "unknown tool: ${request.tool}")
                } else {
                    try {
                        IpcResponse(request.id, result = handler(request.args ?: JsonObject()))
                    } catch (e: ToolError) {
                        IpcResponse(request.id, error = e.message)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("blackbox: ${request.tool} failed", e)
                        IpcResponse(request.id, error = e.message ?: e.toString())
                    }
                }
            }
        }
        writeLock.withLock {
            try {
                val data = ByteBuffer.wrap((Protocol.gson.toJson(response) + "\n").toByteArray(Charsets.UTF_8))
                while (data.hasRemaining()) client.write(data)
            } catch (_: Exception) {
                // The client hung up (e.g. timed out).
            }
        }
    }

    private fun writeRegistry() {
        if (disposed) return
        try {
            Protocol.writeAtomic(Protocol.registryPath(dir, id), Protocol.gson.toJson(entry))
        } catch (e: Exception) {
            LOG.warn("blackbox: could not write the registry entry", e)
        }
    }

    suspend fun setFolders(folders: List<String>) = registryLock.withLock {
        if (folders == entry.folders) return@withLock
        entry = entry.copy(folders = folders)
        writeRegistry()
    }

    suspend fun markFocused() = registryLock.withLock {
        entry = entry.copy(focusedAt = System.currentTimeMillis())
        writeRegistry()
    }

    private fun fileKey(path: Path): Any? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() }.getOrNull()

    private fun ownsSocket(): Boolean = useTcp || (socketKey != null && fileKey(Path.of(entry.socket)) == socketKey)

    // Recreates the socket or registry file if something removed them.
    private suspend fun checkHealth() = registryLock.withLock {
        if (disposed) return@withLock
        if (!Files.exists(Protocol.registryPath(dir, id))) writeRegistry()
        if (ownsSocket()) return@withLock
        closeChannel()
        try {
            listen()
        } catch (e: Exception) {
            LOG.warn("blackbox: could not restart the bridge", e)
        }
    }

    // Stops listening and drops open connections, so the address is free at once.
    private fun closeChannel() {
        acceptJob?.cancel()
        runCatching { channel?.close() }
        channel = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        healthJob?.cancel()
        val owned = ownsSocket()
        closeChannel()
        if (!useTcp && owned) runCatching { Files.deleteIfExists(Path.of(entry.socket)) }
        runCatching { Files.deleteIfExists(Protocol.registryPath(dir, id)) }
    }

    companion object {
        const val HEALTH_CHECK_MS = 5000L
        private const val NEWLINE = '\n'.code.toByte()
        private val LOG = logger<BridgeServer>()
    }
}
