// Helpers shared by the tools (mirrors the helper section of
// editors/vscode/src/tools/impl/debug.ts and blackbox.nvim's common.lua).

package dev.blackbox.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import dev.blackbox.debug.DebugEvents
import dev.blackbox.debug.WaitResult
import dev.blackbox.debug.frames
import dev.blackbox.debug.stacks
import dev.blackbox.debug.title
import dev.blackbox.ipc.Protocol
import dev.blackbox.ipc.ToolError
import java.nio.file.Files
import java.nio.file.Path

const val STEP_WAIT_MS = 10_000L
const val PAUSE_WAIT_MS = 5_000L
const val DEFAULT_STOP_WAIT_MS = 30_000L
const val STOP_FRAMES = 5

fun fail(message: String): Nothing = throw ToolError(message)

// Arguments, as the MCP server forwards them (already checked against the schema).
fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
fun JsonObject.long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
fun JsonObject.bool(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
fun JsonObject.objects(name: String): List<JsonObject>? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }
fun JsonObject.strings(name: String): List<String>? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonPrimitive }?.map { it.asString }

// JSON results, built the way the VS Code extension returns them.
fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
    for ((name, value) in fields) if (value != null) add(name, toJson(value))
}

fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject().apply { value.forEach { (k, v) -> if (v != null) add(k.toString(), toJson(v)) } }
    is Iterable<*> -> JsonArray().apply { value.forEach { add(toJson(it)) } }
    else -> JsonPrimitive(value.toString())
}

fun json(value: Any?): String = Protocol.gson.toJson(toJson(value))

fun findFile(path: String): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)

// Same file, seeing through symlinks (e.g. WordPress wp/ -> wordpress-develop/src/).
fun sameFile(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    if (a == b) return true
    fun real(p: String) = runCatching { Path.of(p).toRealPath().toString() }.getOrDefault(Path.of(p).normalize().toString())
    return real(a) == real(b)
}

fun fileExists(path: String) = Files.isRegularFile(Path.of(path))

class Debug(val project: Project) {
    val events = DebugEvents.of(project)
    val handles = events.handles

    fun findSession(): XDebugSession? = events.findSession()

    fun activeSession(): XDebugSession = findSession() ?: fail("no active debug session")

    // The debugger's threads while paused (debuggers only list them then).
    suspend fun threads(session: XDebugSession): List<Pair<Int, XExecutionStack>> {
        val context = session.suspendContext ?: return emptyList()
        val stacks = context.stacks()
        return handles.threadIds(session, stacks).zip(stacks)
    }

    // The thread a command acts on: the one asked for, the one the debugger
    // has active (where it paused, or picked in the Frames view), then the
    // first thread.
    suspend fun resolveThread(session: XDebugSession, threadId: Int?): Pair<Int, XExecutionStack> {
        val context = session.suspendContext ?: fail("the program is running — is the debugger stopped at a breakpoint?")
        if (threadId != null) {
            return threads(session).firstOrNull { it.first == threadId }
                ?: fail("no thread $threadId — call debug_list_threads for the current ones")
        }
        val active = context.activeExecutionStack
        if (active != null) return handles.threadIds(session, listOf(active)).first() to active
        return threads(session).firstOrNull() ?: fail("no threads — is the debugger stopped at a breakpoint?")
    }

    class Frame(val id: Int, val name: String, val file: String, val line: Int, val frame: XStackFrame) {
        fun view() = obj("id" to id, "name" to name, "file" to file, "line" to line)
    }

    fun frameOf(session: XDebugSession, frame: XStackFrame): Frame {
        val position = frame.sourcePosition
        return Frame(
            handles.frameId(session, frame),
            frame.title(),
            position?.file?.path ?: "(unknown)",
            (position?.line ?: -1) + 1,
            frame,
        )
    }

    suspend fun stackFrames(session: XDebugSession, stack: XExecutionStack, levels: Int): List<Frame> =
        stack.frames(levels).map { frameOf(session, it) }

    // The frame to inspect: the one asked for, else the top of the chosen thread.
    suspend fun frameFor(session: XDebugSession, args: JsonObject): XStackFrame {
        args.int("frameId")?.let { id ->
            val (owner, frame) = handles.frame(id) ?: fail("no stack frame $id — frames change when the program moves; call debug_get_stack_trace again")
            if (owner !== session) fail("stack frame $id belongs to another debug session")
            return frame
        }
        val (_, stack) = resolveThread(session, args.int("threadId"))
        return stack.topFrame ?: stack.frames(1).firstOrNull()
            ?: fail("no stack frames — is the debugger stopped at a breakpoint?")
    }

    suspend fun describeStop(session: XDebugSession, stop: dev.blackbox.debug.Stop): JsonObject {
        val frames = runCatching {
            val (_, stack) = resolveThread(session, stop.threadId)
            stackFrames(session, stack, STOP_FRAMES)
        }.getOrDefault(emptyList())
        val top = frames.firstOrNull()
        return obj(
            "state" to "stopped",
            "session" to session.sessionName,
            "reason" to stop.reason,
            "description" to stop.description,
            "threadId" to stop.threadId,
            "file" to top?.file,
            "line" to top?.line,
            "function" to top?.name,
            "frames" to frames.map { it.view() },
        )
    }

    suspend fun describeWait(result: WaitResult): JsonObject = when (result) {
        is WaitResult.Stopped -> describeStop(result.session, result.stop)
        WaitResult.Timeout -> obj("state" to "running", "hint" to "Not paused yet. Call debug_wait_for_stop to keep waiting.")
        WaitResult.Terminated -> obj("state" to "terminated")
    }
}
