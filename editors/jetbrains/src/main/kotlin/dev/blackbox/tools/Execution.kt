// Continue, pause, step, run-to-line and waiting for the debugger to pause.
// XDebugger steps the thread selected in the Frames view, so a requested
// thread is selected first.

package dev.blackbox.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Execution(private val debug: Debug) {
    private val events get() = debug.events

    // Makes the requested thread the one the debugger acts on.
    private suspend fun select(session: XDebugSession, threadId: Int?) {
        if (threadId == null) return
        val (_, stack) = debug.resolveThread(session, threadId)
        val top = stack.topFrame ?: return
        withContext(Dispatchers.EDT) { session.setCurrentStackFrame(stack, top) }
    }

    // Sends a resuming command and reports where the debugger pauses next.
    private suspend fun runAndReport(args: JsonObject, action: String, waitMs: Long, command: (XDebugSession) -> Unit): String {
        val session = debug.activeSession()
        if (action != "pause" && !events.isPaused(session)) fail("the program is running — call debug_pause or debug_wait_for_stop first")
        select(session, args.int("threadId"))
        val waiter = events.arm() // Before the command, so a fast pause is not missed.
        events.expect(session, action)
        withContext(Dispatchers.EDT) { command(session) }
        return json(debug.describeWait(waiter.await(waitMs)))
    }

    suspend fun continue_(args: JsonObject): String {
        val session = debug.activeSession()
        select(session, args.int("threadId"))
        withContext(Dispatchers.EDT) { session.resume() }
        return "Resumed execution. Call debug_wait_for_stop to wait for the next pause."
    }

    suspend fun pause(args: JsonObject) = runAndReport(args, "pause", PAUSE_WAIT_MS) { it.pause() }
    suspend fun stepOver(args: JsonObject) = runAndReport(args, "step", STEP_WAIT_MS) { it.stepOver(false) }
    suspend fun stepInto(args: JsonObject) = runAndReport(args, "step", STEP_WAIT_MS) { it.stepInto() }
    suspend fun stepOut(args: JsonObject) = runAndReport(args, "step", STEP_WAIT_MS) { it.stepOut() }

    suspend fun waitForStop(args: JsonObject): String =
        json(debug.describeWait(events.waitForStop(args.long("timeoutMs") ?: DEFAULT_STOP_WAIT_MS, args.bool("next") == true)))

    suspend fun runToLine(args: JsonObject): String {
        val file = args.str("file")
        val line = args.int("line")
        if (file == null || line == null || line < 1) fail("file and line (>= 1) are required")
        val vfile = findFile(file) ?: fail("no such file $file")
        val position = readAction { XDebuggerUtil.getInstance().createPosition(vfile, line - 1) } ?: fail("no line $line in $file")
        val session = debug.activeSession()
        if (!events.isPaused(session)) fail("the program is running — call debug_pause or debug_wait_for_stop first")
        select(session, args.int("threadId"))
        val waiter = events.arm()
        events.expect(session, "goto")
        withContext(Dispatchers.EDT) { session.runToPosition(position, false) }
        val result = debug.describeWait(waiter.await(args.long("timeoutMs") ?: DEFAULT_STOP_WAIT_MS))
        val reached = result.str("state") == "stopped" && result.int("line") == line && sameFile(result.str("file"), file)
        result.addProperty("reachedTarget", reached)
        return json(result)
    }
}
