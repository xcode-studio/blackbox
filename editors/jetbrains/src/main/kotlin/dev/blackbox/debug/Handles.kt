// Numeric ids for threads, frames and values, as DAP gives them: the AI
// refers back to them in later calls. Thread ids stay stable for a session
// (by thread name); frame and value ids are valid until the program resumes.

package dev.blackbox.debug

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueContainer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class Handles {
    private val nextId = AtomicInteger(1)
    private val threadIds = ConcurrentHashMap<XDebugSession, MutableMap<String, Int>>()
    private val frames = ConcurrentHashMap<Int, Pair<XDebugSession, XStackFrame>>()
    private val values = ConcurrentHashMap<Int, Pair<XDebugSession, XValueContainer>>()

    // Debuggers create new stack objects on every pause; the thread's name is
    // what stays the same. Same-named threads are told apart by position.
    fun threadIds(session: XDebugSession, stacks: List<XExecutionStack>): List<Int> {
        val ids = threadIds.computeIfAbsent(session) { ConcurrentHashMap() }
        val seen = mutableMapOf<String, Int>()
        return stacks.map { stack ->
            val n = seen.merge(stack.displayName, 1, Int::plus)!!
            val key = if (n == 1) stack.displayName else "${stack.displayName}#$n"
            ids.computeIfAbsent(key) { nextId.getAndIncrement() }
        }
    }

    fun frameId(session: XDebugSession, frame: XStackFrame): Int {
        frames.entries.firstOrNull { it.value.second === frame }?.let { return it.key }
        return nextId.getAndIncrement().also { frames[it] = session to frame }
    }

    fun frame(id: Int): Pair<XDebugSession, XStackFrame>? = frames[id]

    fun valueId(session: XDebugSession, value: XValueContainer): Int =
        nextId.getAndIncrement().also { values[it] = session to value }

    fun value(id: Int): Pair<XDebugSession, XValueContainer>? = values[id]

    // The program moved on: its frames and values are gone.
    fun resumed(session: XDebugSession) {
        frames.values.removeIf { it.first === session }
        values.values.removeIf { it.first === session }
    }

    fun ended(session: XDebugSession) {
        resumed(session)
        threadIds.remove(session)
    }
}
