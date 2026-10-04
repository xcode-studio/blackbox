// Follows the project's debug sessions so tools can wait for the debugger to
// pause and read program output (port of editors/vscode/src/tools/impl/
// debugEvents.ts and blackbox.nvim's events.lua).
//
// It also applies Blackbox's hit conditions and logpoints (BreakpointExtras):
// such a breakpoint pauses the program, and here it is counted or its
// message logged, and the program resumed, before anyone sees the pause.

package dev.blackbox.debug

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.blackbox.lang.LanguageSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class Stop(val reason: String, val threadId: Int?, val description: String? = null)

sealed interface WaitResult {
    data class Stopped(val session: XDebugSession, val stop: Stop) : WaitResult
    data object Timeout : WaitResult
    data object Terminated : WaitResult
}

class OutputEntry(val seq: Long, val session: String, val category: String, val text: String)

@Service(Service.Level.PROJECT)
class DebugEvents(private val project: Project, private val scope: CoroutineScope) : Disposable {
    val handles = Handles()

    private class Pause(val stop: Stop, val order: Long)

    private class Tracked(val session: XDebugSession) {
        @Volatile var pause: Pause? = null
        @Volatile var lastStop: Stop? = null
        // What Blackbox last asked this session to do ("step", "pause", "goto"),
        // to name the next pause; null for a pause the program ran into.
        @Volatile var action: String? = null
        val hits = ConcurrentHashMap<XBreakpoint<*>, Int>()
    }

    private val tracked = ConcurrentHashMap<XDebugSession, Tracked>()
    private val waiters = ConcurrentHashMap.newKeySet<CompletableDeferred<WaitResult>>()
    private val stopCounter = AtomicLong()

    private val output = ArrayDeque<OutputEntry>()
    private var outputSeq = 0L

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            // The session doesn't know its process yet at this point.
            override fun processStarted(process: XDebugProcess) = track(process.session, process)
            override fun processStopped(process: XDebugProcess) = ended(process.session)
        })
        connection.subscribe(XBreakpointListener.TOPIC, object : XBreakpointListener<XBreakpoint<*>> {
            override fun breakpointRemoved(breakpoint: XBreakpoint<*>) = BreakpointExtras.of(project).remove(breakpoint)
        })
        XDebuggerManager.getInstance(project).debugSessions.forEach { track(it, it.debugProcess) }
    }

    private fun track(session: XDebugSession, process: XDebugProcess) {
        if (tracked.putIfAbsent(session, Tracked(session)) != null) return
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                scope.launch { onPaused(session) }
            }

            override fun sessionResumed() = resumed(session)
            override fun sessionStopped() = ended(session)
        }, this)
        process.processHandler?.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val category = when (outputType) {
                    ProcessOutputTypes.STDOUT -> "stdout"
                    ProcessOutputTypes.STDERR -> "stderr"
                    else -> "console"
                }
                addOutput(session, category, event.text)
            }
        }, this)
    }

    // The next pause of this session comes from Blackbox's own request.
    fun expect(session: XDebugSession, action: String) {
        tracked[session]?.action = action
    }

    private suspend fun onPaused(session: XDebugSession) {
        val t = tracked[session] ?: return
        val action = t.action.also { t.action = null }
        val position = session.currentPosition
        val breakpoint = position?.let { lineBreakpointAt(it) }
        val extra = breakpoint?.let { BreakpointExtras.of(project).get(it) }

        if (action != null && extra?.logMessage != null) {
            // A step that lands on a logpoint logs it, as debug adapters do.
            addOutput(session, "console", render(session, extra.logMessage) + "\n")
        } else if (action == null && breakpoint != null && extra != null) {
            val hits = t.hits.merge(breakpoint, 1, Int::plus)!!
            if (extra.hitCondition != null && !HitCondition.matches(extra.hitCondition, hits)) {
                return resume(session)
            }
            if (extra.logMessage != null) {
                addOutput(session, "console", render(session, extra.logMessage) + "\n")
                return resume(session)
            }
        }

        val stack = session.suspendContext?.activeExecutionStack
        val threadId = stack?.let { handles.threadIds(session, listOf(it)).first() }
        val exception = activeNonLineBreakpoint(session)
        val reason = action ?: when {
            exception != null -> "exception"
            breakpoint != null -> "breakpoint"
            else -> "pause"
        }
        val stop = Stop(reason, threadId, exception?.let { describeBreakpoint(it) })
        t.lastStop = stop
        t.pause = Pause(stop, stopCounter.incrementAndGet())
        notify(WaitResult.Stopped(session, stop))
    }

    // The exception (or other non-line) breakpoint the session paused on. The
    // platform only offers this on its session implementation, so it is
    // looked up by name and left out if that ever changes.
    private fun activeNonLineBreakpoint(session: XDebugSession): XBreakpoint<*>? = runCatching {
        session.javaClass.getMethod("getActiveNonLineBreakpoint").invoke(session) as XBreakpoint<*>?
    }.getOrNull()

    private fun describeBreakpoint(breakpoint: XBreakpoint<*>): String? = runCatching {
        @Suppress("UNCHECKED_CAST")
        (breakpoint.type as com.intellij.xdebugger.breakpoints.XBreakpointType<XBreakpoint<*>, *>).getDisplayText(breakpoint)
    }.getOrNull()

    private suspend fun lineBreakpointAt(position: XSourcePosition): XLineBreakpoint<*>? = readAction {
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .firstOrNull { it.isEnabled && it.line == position.line && it.fileUrl == position.file.url }
    }

    // A logpoint message with its {expression} parts evaluated in the paused frame.
    private suspend fun render(session: XDebugSession, template: String): String {
        val frame = session.currentStackFrame
        val evaluator = frame?.evaluator ?: return template
        return LOG_EXPRESSION.replace(template) { "\u0000${it.groupValues[1]}\u0000" }
            .split('\u0000')
            .mapIndexed { i, part ->
                if (i % 2 == 0) part else try {
                    evaluator.evaluated(part, frame.sourcePosition).shown().value.removeSurrounding("\"")
                } catch (e: Exception) {
                    "<error: ${e.message}>"
                }
            }
            .joinToString("")
    }

    private suspend fun resume(session: XDebugSession) {
        resumed(session)
        withContext(Dispatchers.EDT) { if (!session.isStopped) session.resume() }
    }

    private fun resumed(session: XDebugSession) {
        tracked[session]?.pause = null
        handles.resumed(session)
    }

    private fun ended(session: XDebugSession) {
        if (tracked.remove(session) == null) return
        handles.ended(session)
        if (!active()) notify(WaitResult.Terminated)
    }

    private fun notify(result: WaitResult) {
        for (waiter in waiters.toList()) {
            if (waiters.remove(waiter)) waiter.complete(result)
        }
    }

    fun liveSessions(): List<XDebugSession> = tracked.keys.filter { !it.isStopped }

    // A session runs, or the IDE listens for one (PHP): a pause may still come.
    private fun active(): Boolean = liveSessions().isNotEmpty() || LanguageSupport.anyListening(project)

    fun isPaused(session: XDebugSession): Boolean = tracked[session]?.pause != null && !session.isStopped

    class Current(val session: XDebugSession, val stop: Stop)

    // The most recent pause still in effect, across sessions.
    fun currentStop(): Current? = tracked.values
        .filter { !it.session.isStopped }
        .mapNotNull { t -> t.pause?.let { t to it } }
        .maxByOrNull { it.second.order }
        ?.let { Current(it.first.session, it.second.stop) }

    // The most recent pause in effect in this session, else the last one seen.
    fun lastStop(session: XDebugSession): Stop? = tracked[session]?.let { it.pause?.stop ?: it.lastStop }

    // Starts listening now, before a request is sent, so a fast pause is not
    // missed; await() then waits for it.
    inner class Waiter internal constructor() {
        private val result = CompletableDeferred<WaitResult>()

        init {
            if (!active()) result.complete(WaitResult.Terminated) else waiters += result
        }

        suspend fun await(timeoutMs: Long): WaitResult =
            withTimeoutOrNull(timeoutMs) { result.await() } ?: WaitResult.Timeout.also { waiters -= result }
    }

    fun arm(): Waiter = Waiter()

    // Returns the current pause at once, unless `next` asks for a new one.
    suspend fun waitForStop(timeoutMs: Long, next: Boolean): WaitResult {
        if (!next) currentStop()?.let { return WaitResult.Stopped(it.session, it.stop) }
        return arm().await(timeoutMs)
    }

    // The session tools act on: the focused one if it is paused, otherwise
    // the most recently paused one, otherwise the focused one.
    fun findSession(): XDebugSession? {
        val current = XDebuggerManager.getInstance(project).currentSession?.takeIf { !it.isStopped }
        if (current != null && isPaused(current)) return current
        return currentStop()?.session ?: current ?: liveSessions().firstOrNull()
    }

    private fun addOutput(session: XDebugSession, category: String, text: String) = synchronized(output) {
        output.addLast(OutputEntry(++outputSeq, session.sessionName, category, text))
        if (output.size > MAX_OUTPUT) output.removeFirst()
    }

    class OutputPage(val entries: List<OutputEntry>, val nextSince: Long, val more: Boolean)

    fun readOutput(since: Long, category: String?, match: String?, limit: Int?): OutputPage = synchronized(output) {
        val needle = match?.lowercase()
        val matching = output.filter {
            it.seq > since && (category == null || it.category == category) &&
                (needle == null || it.text.lowercase().contains(needle))
        }
        val max = maxOf(1, limit ?: DEFAULT_OUTPUT_LIMIT)
        val entries = mutableListOf<OutputEntry>()
        var budget = MAX_OUTPUT_CHARS
        for (e in matching) {
            val text = truncate(e.text, MAX_ENTRY_CHARS)
            if (entries.size >= max || (entries.isNotEmpty() && text.length > budget)) break
            entries += OutputEntry(e.seq, e.session, e.category, text)
            budget -= text.length
        }
        val more = entries.size < matching.size
        OutputPage(entries, if (more) entries.last().seq else maxOf(since, outputSeq), more)
    }

    private fun truncate(text: String, max: Int) =
        if (text.length <= max) text else "${text.take(max)}… (${text.length - max} more characters)"

    override fun dispose() {
        notify(WaitResult.Terminated)
    }

    companion object {
        private const val MAX_OUTPUT = 1000
        private const val DEFAULT_OUTPUT_LIMIT = 200
        // Some debuggers log a lot; keep responses small.
        private const val MAX_ENTRY_CHARS = 2000
        private const val MAX_OUTPUT_CHARS = 50_000
        private val LOG_EXPRESSION = Regex("""\{([^{}]+)\}""")

        fun of(project: Project): DebugEvents = project.service()
    }
}
