// Hit conditions and log messages of line breakpoints. The IDE's debugger
// platform has neither in a form every language supports, so Blackbox keeps
// them and applies them itself when a breakpoint pauses (DebugEvents).
//
// They are kept per breakpoint object, so they follow the breakpoint when
// edits move it, and saved in the workspace file by file and line.

package dev.blackbox.debug

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import java.util.WeakHashMap

data class Extra(val hitCondition: String?, val logMessage: String?) {
    val isEmpty get() = hitCondition == null && logMessage == null
}

@Service(Service.Level.PROJECT)
@State(name = "BlackboxBreakpoints", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class BreakpointExtras(private val project: Project) : PersistentStateComponent<BreakpointExtras.Saved> {
    class SavedEntry {
        var url: String = ""
        var line: Int = 0
        var hitCondition: String? = null
        var logMessage: String? = null
    }

    class Saved {
        var entries: MutableList<SavedEntry> = mutableListOf()
    }

    private val extras = WeakHashMap<XLineBreakpoint<*>, Extra>()
    private var unresolved: List<SavedEntry> = emptyList()

    @Synchronized
    fun get(breakpoint: XBreakpoint<*>): Extra? {
        resolveSaved()
        return extras[breakpoint]
    }

    @Synchronized
    fun set(breakpoint: XLineBreakpoint<*>, extra: Extra) {
        resolveSaved()
        if (extra.isEmpty) extras.remove(breakpoint) else extras[breakpoint] = extra
    }

    @Synchronized
    fun remove(breakpoint: XBreakpoint<*>) {
        extras.remove(breakpoint)
    }

    // Matches entries loaded from the workspace file to breakpoints, once the
    // IDE has loaded those.
    private fun resolveSaved() {
        if (unresolved.isEmpty()) return
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val lines = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
        for (entry in unresolved) {
            lines.firstOrNull { it.fileUrl == entry.url && it.line == entry.line }?.let {
                extras[it] = Extra(entry.hitCondition, entry.logMessage)
            }
        }
        unresolved = emptyList()
    }

    @Synchronized
    override fun getState(): Saved = Saved().also { saved ->
        resolveSaved()
        saved.entries = extras.entries.mapTo(mutableListOf()) { (bp, extra) ->
            SavedEntry().apply {
                url = bp.fileUrl
                line = bp.line
                hitCondition = extra.hitCondition
                logMessage = extra.logMessage
            }
        }
    }

    @Synchronized
    override fun loadState(state: Saved) {
        unresolved = state.entries.toList()
    }

    companion object {
        fun of(project: Project): BreakpointExtras = project.service()
    }
}

// Hit conditions as the tool contract describes them: "5" (the 5th hit),
// ">= 10", "> 3", "< 3", "<= 3", "== 5", "% 3" (every 3rd).
object HitCondition {
    private val PATTERN = Regex("""^\s*(>=|<=|==|>|<|%)?\s*(\d+)\s*$""")

    fun isValid(condition: String): Boolean = PATTERN.matches(condition)

    fun matches(condition: String, hits: Int): Boolean {
        val match = PATTERN.matchEntire(condition) ?: return true
        val n = match.groupValues[2].toInt()
        return when (match.groupValues[1]) {
            ">=" -> hits >= n
            "<=" -> hits <= n
            ">" -> hits > n
            "<" -> hits < n
            "%" -> n > 0 && hits % n == 0
            else -> hits == n
        }
    }
}
