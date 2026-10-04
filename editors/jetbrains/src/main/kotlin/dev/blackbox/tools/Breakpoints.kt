// Breakpoint tools on the IDE's breakpoint manager. Line breakpoints use the
// line's own breakpoint type (Java, PHP, Go, ...), conditions are native,
// hit conditions and log messages are Blackbox's (BreakpointExtras).

package dev.blackbox.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import dev.blackbox.debug.BreakpointExtras
import dev.blackbox.debug.Extra
import dev.blackbox.debug.HitCondition
import dev.blackbox.lang.LanguageSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Breakpoints(private val project: Project) {
    private val manager get() = XDebuggerManager.getInstance(project).breakpointManager
    private val extras get() = BreakpointExtras.of(project)

    private class Spec(val file: String?, val line: Int?, val condition: String?, val hitCondition: String?, val logMessage: String?) {
        fun describe() = "$file:$line" +
            (condition?.let { " (if: $it)" } ?: "") +
            (hitCondition?.let { " (hits: $it)" } ?: "") +
            (logMessage?.let { " (log: $it)" } ?: "")
    }

    private fun spec(o: JsonObject) =
        Spec(o.str("file"), o.int("line"), o.str("condition"), o.str("hitCondition"), o.str("logMessage"))

    private suspend fun <T> write(block: () -> T): T = withContext(Dispatchers.EDT) { WriteAction.compute<T, Throwable>(block) }

    // Source breakpoints: line breakpoints that aren't function breakpoints.
    private fun sourceBreakpoints(): List<XLineBreakpoint<*>> = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .filter { LanguageSupport.functionName(it) == null }

    private fun functionBreakpoints(): List<Pair<String, XBreakpoint<*>>> = manager.allBreakpoints
        .mapNotNull { bp -> LanguageSupport.functionName(bp)?.let { it to bp } }

    private fun at(file: String, line: Int): List<XLineBreakpoint<*>> =
        sourceBreakpoints().filter { it.line == line - 1 && sameFile(it.sourcePosition?.file?.path ?: urlPath(it.fileUrl), file) }

    private fun urlPath(url: String) = url.removePrefix("file://")

    // The line breakpoint type for this line (the one the gutter would use)
    // and the line to use: the same, or the next line that can hold one, as
    // debug adapters move breakpoints off blank lines and comments.
    private suspend fun placement(file: VirtualFile, line0: Int): Pair<XLineBreakpointType<*>, Int>? = readAction {
        val types = XDebuggerUtil.getInstance().lineBreakpointTypes.sortedByDescending { it.priority }
        (line0..line0 + MAX_LINE_SHIFT).firstNotNullOfOrNull { candidate ->
            types.firstOrNull { !isFunctionType(it) && it.canPutAt(file, candidate, project) }
                ?.let { it to candidate }
        }
    }

    // Method breakpoint types also fit some lines; they're not line breakpoints.
    private fun isFunctionType(type: XLineBreakpointType<*>) =
        type.id.contains("method", ignoreCase = true)

    private fun add(type: XLineBreakpointType<*>, file: VirtualFile, line0: Int, spec: Spec): XLineBreakpoint<*> {
        @Suppress("UNCHECKED_CAST")
        val t = type as XLineBreakpointType<XBreakpointProperties<*>>
        val bp = manager.addLineBreakpoint(t, file.url, line0, t.createBreakpointProperties(file, line0))
        spec.condition?.let {
            bp.conditionExpression = XDebuggerUtil.getInstance().createExpression(it, null, null, EvaluationMode.EXPRESSION)
        }
        // Blackbox counts hits and logs messages itself, so the program must pause there.
        if (spec.hitCondition != null || spec.logMessage != null) bp.suspendPolicy = SuspendPolicy.ALL
        extras.set(bp, Extra(spec.hitCondition, spec.logMessage))
        return bp
    }

    suspend fun set(args: JsonObject): String {
        val specs = args.objects("breakpoints")?.map(::spec) ?: listOf(spec(args))
        val results = specs.map { spec ->
            val file = spec.file
            val line = spec.line
            when {
                file == null || line == null || line < 1 -> "skip: invalid — file and line (>= 1) required"
                spec.hitCondition != null && !HitCondition.isValid(spec.hitCondition) ->
                    "skip: ${spec.describe()} — hitCondition must look like \"5\", \">= 10\" or \"% 3\""
                else -> setOne(file, line, spec)
            }
        }
        return results.joinToString("\n")
    }

    private suspend fun setOne(file: String, line: Int, spec: Spec): String {
        val vfile = findFile(file) ?: return "skip: no such file $file"
        val (type, line0) = placement(vfile, line - 1)
            ?: return "skip: ${spec.describe()} — no line from $line to ${line + MAX_LINE_SHIFT} can hold a breakpoint"
        write {
            at(file, line).forEach(manager::removeBreakpoint) // Replaces one already there.
            if (line0 != line - 1) at(file, line0 + 1).forEach(manager::removeBreakpoint)
            add(type, vfile, line0, spec)
        }
        val moved = if (line0 != line - 1) " (moved to line ${line0 + 1}, the next line that can hold a breakpoint)" else ""
        return "ok: ${spec.describe()}$moved"
    }

    suspend fun setFunction(args: JsonObject): String {
        val name = args.str("name")?.takeIf { it.isNotEmpty() } ?: fail("function name is required")
        val supports = LanguageSupport.all()
        if (supports.isEmpty()) fail("function breakpoints aren't supported in this IDE; set a breakpoint on the function's first line instead")
        val condition = args.str("condition")
        val hitCondition = args.str("hitCondition")
        smartReadAction(project) {} // Finding the function needs the indexes.
        val bp = write {
            functionBreakpoints().filter { LanguageSupport.nameMatches(it.first, name) }.forEach { manager.removeBreakpoint(it.second) }
            supports.firstNotNullOfOrNull { it.addFunctionBreakpoint(project, name) }?.also { bp ->
                condition?.let {
                    bp.conditionExpression = XDebuggerUtil.getInstance().createExpression(it, null, null, EvaluationMode.EXPRESSION)
                }
            }
        } ?: fail("no function named \"$name\" found in this project (use the qualified form, e.g. \"App.add\")")
        if (hitCondition != null && bp is XLineBreakpoint<*>) extras.set(bp, Extra(hitCondition, null))
        return "ok: function $name" +
            (condition?.let { " (if: $it)" } ?: "") +
            (hitCondition?.let { " (hits: $it)" } ?: "")
    }

    suspend fun remove(args: JsonObject): String {
        val specs = args.objects("breakpoints")
            ?: args.str("file")?.let { listOf(obj("file" to it, "line" to args.int("line"))) }
            ?: emptyList()
        val results = mutableListOf<String>()
        write {
            for (spec in specs) {
                val file = spec.str("file") ?: continue
                val line = spec.int("line") ?: continue
                val found = at(file, line)
                found.forEach(manager::removeBreakpoint)
                results += if (found.isNotEmpty()) "ok: removed $file:$line" else "skip: no breakpoint at $file:$line"
            }
            for (name in args.strings("functions") ?: emptyList()) {
                val found = functionBreakpoints().filter { LanguageSupport.nameMatches(it.first, name) }
                found.forEach { manager.removeBreakpoint(it.second) }
                results += if (found.isNotEmpty()) "ok: removed function $name" else "skip: no function breakpoint on $name"
            }
        }
        return results.joinToString("\n").ifEmpty { "Nothing to remove: pass file+line, breakpoints or functions" }
    }

    suspend fun removeAll(): String {
        val count = write {
            val all = manager.allBreakpoints.filter { !manager.isDefaultBreakpoint(it) }
            all.forEach(manager::removeBreakpoint)
            all.size
        }
        return if (count == 0) "No breakpoints to remove" else "Removed all $count breakpoint(s)"
    }

    suspend fun toggle(args: JsonObject): String {
        val enabled = args.bool("enabled") ?: fail("\"enabled\" (true or false) is required")
        val wantedSources = args.objects("breakpoints")
        val wantedFunctions = args.strings("functions")
        val all = wantedSources == null && wantedFunctions == null
        var changed = 0
        var already = 0
        write {
            val sources = sourceBreakpoints().filter { bp ->
                all || wantedSources.orEmpty().any { w ->
                    w.int("line") == bp.line + 1 && sameFile(bp.sourcePosition?.file?.path ?: urlPath(bp.fileUrl), w.str("file"))
                }
            }
            val functions = functionBreakpoints()
                .filter { (name, _) -> all || wantedFunctions.orEmpty().any { LanguageSupport.nameMatches(name, it) } }
                .map { it.second }
            for (bp in sources + functions) {
                if (bp.isEnabled == enabled) already++ else {
                    bp.isEnabled = enabled
                    changed++
                }
            }
        }
        val verb = if (enabled) "Enabled" else "Disabled"
        return "$verb $changed breakpoint(s)" +
            (if (already > 0) "; $already already ${verb.lowercase()}" else "") +
            (if (changed + already == 0) ". No matching breakpoints." else "")
    }

    suspend fun list(): String = readAction {
        val sources = sourceBreakpoints().map { bp ->
            val extra = extras.get(bp)
            obj(
                "type" to "source",
                "file" to (bp.sourcePosition?.file?.path ?: urlPath(bp.fileUrl)),
                "line" to bp.line + 1,
                "enabled" to bp.isEnabled,
                "condition" to bp.conditionExpression?.expression?.ifEmpty { null },
                "hitCondition" to extra?.hitCondition,
                "logMessage" to extra?.logMessage,
            )
        }.sortedWith(compareBy({ it.str("file") }, { it.int("line") }))
        val functions = functionBreakpoints().map { (name, bp) ->
            obj(
                "type" to "function",
                "name" to name,
                "enabled" to bp.isEnabled,
                "condition" to bp.conditionExpression?.expression?.ifEmpty { null },
                "hitCondition" to extras.get(bp)?.hitCondition,
            )
        }
        json(sources + functions)
    }

    fun count(): Int = manager.allBreakpoints.count { !manager.isDefaultBreakpoint(it) }

    suspend fun setExceptionFilters(args: JsonObject, sessionName: String?): String {
        val supports = LanguageSupport.all()
        val available = readAction { supports.flatMap { it.exceptionFilters(project) } }
        val filters = args.strings("filters")
            ?: return json(obj(
                "session" to sessionName,
                "available" to available.map { obj("filter" to it.filter, "label" to it.label, "default" to it.enabled) },
            ))
        val known = available.map { it.filter }.toSet()
        val unknown = filters.filter { it !in known }
        if (unknown.isNotEmpty()) {
            fail("unknown exception filter(s): ${unknown.joinToString(", ")}. Available: ${known.joinToString(", ").ifEmpty { "(none in this IDE)" }}")
        }
        write { supports.forEach { it.setExceptionFilters(project, filters) } }
        return "Exception breakpoints: ${filters.joinToString(", ").ifEmpty { "(none)" }}. " +
            "They are the IDE's exception breakpoints, so they stay set for later debug sessions."
    }

    companion object {
        private const val MAX_LINE_SHIFT = 5
    }
}
