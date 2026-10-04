// The Blackbox window protocol, shared with the VS Code extension
// (editors/vscode/src/ipc/protocol.ts) and blackbox.nvim. Each editor window
// listens on its own socket in a private per-user folder and describes
// itself in a registry file next to it; the MCP server reads the registry to
// find windows.
//
//   <dir>/<id>.sock   the socket (Unix domain socket)
//   <dir>/<id>.json   the registry entry
//
// On Windows, Node can't connect to Java's AF_UNIX sockets (libuv only does
// named pipes there), so windows listen on TCP loopback instead: the entry's
// socket is "tcp://127.0.0.1:<port>/<token>" and every request carries the
// token (see parseTcpSocket in protocol.ts).

package dev.blackbox.ipc

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

object Protocol {
    // Bumped when the registry entry or internal tools change shape.
    const val VERSION = 2

    // Internal tool the MCP server uses to describe windows.
    const val STATUS_TOOL = "window_status"

    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    // Same location rule as ipcDir() in protocol.ts: a fixed per-user folder,
    // because editors and MCP servers often see different TMPDIRs.
    fun ipcDir(env: Map<String, String> = System.getenv()): Path {
        env["BLACKBOX_IPC_DIR"]?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        if (isWindows) {
            return Path.of(env["TEMP"] ?: System.getProperty("java.io.tmpdir"), "blackbox")
        }
        return Path.of("/tmp/blackbox-${uid()}")
    }

    fun socketPath(dir: Path, id: String): Path = dir.resolve("$id.sock")

    fun registryPath(dir: Path, id: String): Path = dir.resolve("$id.json")

    fun uid(): Int = com.sun.security.auth.module.UnixSystem().uid.toInt()

    // Error message unless dir is a real directory owned by this user and
    // closed to everyone else (mirrors assertPrivateDir); null when it is fine.
    fun privateDirProblem(dir: Path): String? {
        if (isWindows) return null // %TEMP% is already per-user.
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return "$dir is not a directory"
        val owner = Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int
        if (owner != uid()) return "$dir is owned by another user"
        val mode = Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
        if (mode and "077".toInt(8) != 0) {
            return "$dir is accessible to other users (mode ${Integer.toOctalString(mode and "777".toInt(8))})"
        }
        return null
    }

    // Creates the folder, or tightens one this user already owns; throws if
    // another user could still reach it (mirrors ensurePrivateDir).
    fun ensurePrivateDir(dir: Path) {
        if (isWindows) {
            Files.createDirectories(dir)
            return
        }
        val private = PosixFilePermissions.fromString("rwx------")
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(private)) }
        }
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) &&
            Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int == uid()
        ) {
            Files.setPosixFilePermissions(dir, private)
        }
        privateDirProblem(dir)?.let { throw IllegalStateException(it) }
    }

    // Writes then renames, so readers never see a half-written file.
    fun writeAtomic(path: Path, content: String) {
        val tmp = path.resolveSibling("${path.fileName}.${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, content)
        if (!isWindows) Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }
}

// The editor a window belongs to.
data class AppInfo(val name: String, val scheme: String, val version: String)

// Field names and types match RegistryEntry in protocol.ts.
data class RegistryEntry(
    val id: String,
    val pid: Long,
    var socket: String,
    var folders: List<String>,
    val startedAt: Long,
    var focusedAt: Long,
    val app: AppInfo,
    val appPid: Long,
    val extensionVersion: String,
    val protocol: Int = Protocol.VERSION,
)

data class IpcRequest(val id: String?, val tool: String?, val args: com.google.gson.JsonObject?, val token: String?)

data class IpcResponse(val id: String?, val result: String? = null, val error: String? = null)
