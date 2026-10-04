// Threads, stack, variables, evaluation, watches, source context and output.

package dev.blackbox.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueContainer
import dev.blackbox.debug.changeTo
import dev.blackbox.debug.children
import dev.blackbox.debug.evaluated
import dev.blackbox.debug.shown
import java.nio.file.Files
import java.nio.file.Path

class Inspect(private val debug: Debug) {
    private val handles get() = debug.handles
    private val watches = mutableListOf<String>() // kept across steps and sessions

    suspend fun listThreads(): String {
        val session = debug.activeSession()
        val paused = debug.events.isPaused(session)
        val threads = debug.threads(session).map { (id, stack) ->
            obj("id" to id, "name" to stack.displayName, "stopped" to (if (paused) true else null))
        }
        return json(obj(
            "session" to session.sessionName,
            "threads" to threads,
            "note" to (if (!paused) "The program is running; this debugger lists threads only while paused." else null),
        ))
    }

    suspend fun stackTrace(args: JsonObject): String {
        val session = debug.activeSession()
        val (_, stack) = debug.resolveThread(session, args.int("threadId"))
        val levels = minOf(args.int("levels") ?: 20, 200)
        return json(debug.stackFrames(session, stack, levels).map { it.view() })
    }

    private suspend fun view(session: XDebugSession, name: String, value: XValue, expandable: Boolean): JsonObject {
        val shown = try {
            value.shown()
        } catch (e: Exception) {
            return obj("name" to name, "value" to "<error: ${e.message}>")
        }
        return obj(
            "name" to name,
            "value" to shown.value,
            "type" to shown.type,
            "expandable" to (if (expandable) shown.hasChildren else null),
            "variablesReference" to (if (expandable && shown.hasChildren) handles.valueId(session, value) else null),
        )
    }

    suspend fun variables(args: JsonObject): String {
        val session = debug.activeSession()
        args.int("variablesReference")?.let { ref ->
            val container = container(session, ref)
            return json(container.children().values.map { (name, value) -> view(session, name, value, true) })
        }
        val frame = debug.frameFor(session, args)
        val needle = args.str("filter")?.lowercase()
        fun wanted(name: String) = needle == null || name.lowercase().contains(needle)
        // Scopes, as DAP has them: the frame's own values, then each group
        // the debugger adds (e.g. "Static", "Globals").
        val children = frame.children()
        val result = linkedMapOf<String, Any>()
        result["Locals"] = children.values.filter { wanted(it.first) }.map { (n, v) -> view(session, n, v, false) }
        for ((groupName, group) in children.groups) {
            val values = runCatching { group.children().values }.getOrDefault(emptyList())
            result[groupName] = values.filter { wanted(it.first) }.map { (n, v) -> view(session, n, v, false) }
        }
        return json(result)
    }

    private fun container(session: XDebugSession, ref: Int): XValueContainer {
        val (owner, value) = handles.value(ref)
            ?: fail("no variablesReference $ref — references change when the program moves; read the variables again")
        if (owner !== session) fail("variablesReference $ref belongs to another debug session")
        return value
    }

    private suspend fun evaluate(session: XDebugSession, frame: XStackFrame, expression: String): XValue {
        val evaluator = frame.evaluator ?: fail("this debugger can't evaluate expressions here")
        return evaluator.evaluated(expression, frame.sourcePosition)
    }

    suspend fun evaluate(args: JsonObject): String {
        val expression = args.str("expression")?.takeIf { it.isNotEmpty() } ?: fail("expression is required")
        val session = debug.activeSession()
        val value = evaluate(session, debug.frameFor(session, args), expression)
        val shown = value.shown()
        return json(obj(
            "expression" to expression,
            "result" to shown.value,
            "type" to shown.type,
            "variablesReference" to (if (shown.hasChildren) handles.valueId(session, value) else null),
        ))
    }

    private suspend fun expand(container: XValueContainer, depth: Int, maxDepth: Int, maxItems: Int): Any {
        if (depth >= maxDepth) return "..."
        val items = container.children().values
        val result = linkedMapOf<String, Any?>()
        for ((name, value) in items.take(maxItems)) {
            val shown = runCatching { value.shown() }.getOrNull()
            result[name] = when {
                shown == null -> "<error>"
                shown.hasChildren -> expand(value, depth + 1, maxDepth, maxItems)
                else -> shown.value
            }
        }
        if (items.size > maxItems) result["..."] = "(${items.size - maxItems} more items)"
        return result
    }

    suspend fun inspect(args: JsonObject): String {
        val variable = args.str("variable")?.takeIf { it.isNotEmpty() } ?: fail("variable expression is required")
        val session = debug.activeSession()
        val maxDepth = minOf(args.int("depth") ?: 2, 5)
        val maxItems = minOf(args.int("maxItems") ?: 50, 200)
        val value = evaluate(session, debug.frameFor(session, args), variable)
        val shown = value.shown()
        val expanded = if (shown.hasChildren) expand(value, 0, maxDepth, maxItems) else shown.value
        return json(obj("variable" to variable, "type" to shown.type, "value" to expanded))
    }

    suspend fun setVariable(args: JsonObject): String {
        val name = args.str("name")
        val newValue = args.get("value")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
        if (name == null || newValue == null) fail("\"name\" and \"value\" are required")
        val session = debug.activeSession()
        val container: XValueContainer = args.int("variablesReference")?.let { container(session, it) }
            ?: debug.frameFor(session, args)
        val children = container.children()
        val target = children.values.firstOrNull { it.first == name }?.second
            ?: children.groups.firstNotNullOfOrNull { (_, g) ->
                runCatching { g.children().values.firstOrNull { it.first == name }?.second }.getOrNull()
            }
            ?: fail("no variable named \"$name\" in the current frame; pass variablesReference for a nested one")
        target.shown() // Some debuggers (Java) offer a modifier only once the value is computed.
        val modifier = target.modifier ?: fail("the debugger can't change \"$name\"")
        modifier.changeTo(newValue)
        // Read it back: the old XValue may still show the old value.
        val updated = container.children().values.firstOrNull { it.first == name }?.second ?: target
        val shown = updated.shown()
        return json(obj("name" to name, "value" to shown.value, "type" to shown.type))
    }

    suspend fun watch(args: JsonObject): String {
        val expressions = args.strings("expressions").orEmpty()
        when (args.str("action")) {
            "add" -> {
                if (expressions.isEmpty()) fail("expressions array required for add")
                synchronized(watches) { expressions.forEach { if (it !in watches) watches += it } }
                return "Watching ${expressions.size} expression(s). Total watches: ${watches.size}\n${watches.joinToString(", ")}"
            }
            "remove" -> {
                if (expressions.isEmpty()) fail("expressions array required for remove")
                synchronized(watches) { watches.removeAll(expressions.toSet()) }
                return "Removed ${expressions.size}. Remaining watches: ${watches.size}"
            }
            "clear" -> {
                val count = synchronized(watches) { watches.size.also { watches.clear() } }
                return "Cleared all $count watch expression(s)"
            }
            "list" -> {
                val list = synchronized(watches) { watches.toList() }
                if (list.isEmpty()) return "No watch expressions set. Use action=\"add\" first."
                val session = debug.findSession()
                    ?: return "Watch expressions (${list.size}): ${list.joinToString(", ")}\n(No active debug session — values not available)"
                val frame = debug.frameFor(session, args)
                val result = linkedMapOf<String, Any?>()
                for (e in list) {
                    result[e] = try {
                        val value = evaluate(session, frame, e)
                        val shown = value.shown()
                        if (shown.hasChildren) expand(value, 0, 1, 20) else shown.value
                    } catch (ex: Exception) {
                        "<error: ${ex.message}>"
                    }
                }
                return json(result)
            }
        }
        fail("action must be add, remove, list, or clear")
    }

    // Includes unsaved edits when the file is open.
    private suspend fun readLines(path: String): List<String> {
        findFile(path)?.let { file ->
            readAction { FileDocumentManager.getInstance().getDocument(file)?.text }?.let { return it.lines() }
        }
        return Files.readAllLines(Path.of(path))
    }

    suspend fun sourceContext(args: JsonObject): String {
        val session = debug.activeSession()
        val radius = (args.int("lines") ?: 5).coerceIn(0, 50)
        val frame = debug.frameOf(session, debug.frameFor(session, args))
        if (frame.line < 1) fail("no source for this stack frame")
        val lines = readLines(frame.file)
        val result = ((frame.line - radius).coerceAtLeast(1)..(frame.line + radius).coerceAtMost(lines.size)).map { n ->
            obj("line" to n, "text" to lines[n - 1], "current" to (if (n == frame.line) true else null))
        }
        return json(obj("file" to frame.file, "line" to frame.line, "function" to frame.name, "lines" to result))
    }

    fun output(args: JsonObject): String {
        val page = debug.events.readOutput(args.long("since") ?: 0, args.str("category"), args.str("match"), args.int("limit"))
        return json(obj(
            "entries" to page.entries.map { obj("seq" to it.seq, "session" to it.session, "category" to it.category, "text" to it.text) },
            "nextSince" to page.nextSince,
            "more" to page.more,
        ))
    }
}
