// Suspend wrappers over XDebugger's callback APIs. Debuggers answer on their
// own threads, sometimes in several batches, and sometimes never (a value
// that can't be computed), so every call has a timeout.

package dev.blackbox.debug

import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.SimpleColoredText
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueContainer
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil
import dev.blackbox.ipc.ToolError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.swing.Icon
import kotlin.coroutines.resume

const val REQUEST_TIMEOUT_MS = 30_000L

// The IDE shows this while a value is still being computed.
private const val COLLECTING = "Collecting data"

private suspend fun <T> callback(what: String, block: (CancellableContinuation<T>) -> Unit): T =
    withTimeoutOrNull(REQUEST_TIMEOUT_MS) { suspendCancellableCoroutine(block) }
        ?: throw ToolError("the debugger did not answer $what within ${REQUEST_TIMEOUT_MS / 1000}s")

private fun <T> CancellableContinuation<T>.complete(value: T) {
    if (isActive) resume(value)
}

private fun CancellableContinuation<*>.fail(message: String) {
    if (isActive) resumeWith(Result.failure(ToolError(message)))
}

suspend fun XSuspendContext.stacks(): List<XExecutionStack> = callback("threads") { cont ->
    val all = mutableListOf<XExecutionStack>()
    computeExecutionStacks(object : XSuspendContext.XExecutionStackContainer {
        override fun addExecutionStack(stacks: List<XExecutionStack>, last: Boolean) {
            all += stacks
            if (last) cont.complete(all.toList())
        }

        override fun errorOccurred(errorMessage: String) = cont.fail(errorMessage)
        override fun isObsolete() = !cont.isActive
    })
}

suspend fun XExecutionStack.frames(limit: Int): List<XStackFrame> = callback("stackTrace") { cont ->
    val all = mutableListOf<XStackFrame>()
    computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
        override fun addStackFrames(frames: List<XStackFrame>, last: Boolean) {
            all += frames
            if (last || all.size >= limit) cont.complete(all.take(limit))
        }

        override fun errorOccurred(errorMessage: String) = cont.fail(errorMessage)
        override fun isObsolete() = !cont.isActive
    })
}

// A container's children: values first, then groups (e.g. "Static"),
// which hold more values.
class Children(val values: List<Pair<String, XValue>>, val groups: List<Pair<String, XValueContainer>>)

suspend fun XValueContainer.children(): Children = callback("variables") { cont ->
    val values = mutableListOf<Pair<String, XValue>>()
    val groups = mutableListOf<Pair<String, XValueContainer>>()
    computeChildren(object : XCompositeNode {
        override fun addChildren(children: XValueChildrenList, last: Boolean) {
            for (group in children.topGroups) groups += group.name to group
            for (i in 0 until children.size()) values += children.getName(i) to children.getValue(i)
            for (group in children.bottomGroups) groups += group.name to group
            if (last) cont.complete(Children(values.toList(), groups.toList()))
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun tooManyChildren(remaining: Int) = cont.complete(Children(values.toList(), groups.toList()))
        override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) = tooManyChildren(remaining)
        override fun setAlreadySorted(alreadySorted: Boolean) {}
        override fun setErrorMessage(errorMessage: String) = cont.fail(errorMessage)
        override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) = cont.fail(errorMessage)
        override fun setMessage(
            message: String, icon: Icon?, attributes: com.intellij.ui.SimpleTextAttributes,
            link: XDebuggerTreeNodeHyperlink?,
        ) {}
        override fun isObsolete() = !cont.isActive
    })
}

data class Shown(val value: String, val type: String?, val hasChildren: Boolean)

// What the Variables view would show for a value.
suspend fun XValue.shown(): Shown = callback("the value") { cont ->
    computePresentation(object : XValueNode {
        override fun setPresentation(icon: Icon?, type: @NlsSafe String?, value: @NlsSafe String, hasChildren: Boolean) {
            if (!value.startsWith(COLLECTING)) cont.complete(Shown(value, type?.ifEmpty { null }, hasChildren))
        }

        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val text = XValuePresentationUtil.computeValueText(presentation)
            if (!text.startsWith(COLLECTING)) cont.complete(Shown(text, presentation.type?.ifEmpty { null }, hasChildren))
        }

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
        override fun isObsolete() = !cont.isActive
    }, XValuePlace.TREE)
}

suspend fun XDebuggerEvaluator.evaluated(expression: String, position: XSourcePosition?): XValue =
    callback("the evaluation") { cont ->
        val expr = XDebuggerUtil.getInstance().createExpression(expression, null, null, EvaluationMode.EXPRESSION)
        evaluate(expr, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) = cont.complete(result)
            override fun errorOccurred(errorMessage: String) = cont.fail(errorMessage)
        }, position)
    }

suspend fun XValueModifier.changeTo(value: String) = callback<Unit>("the change") { cont ->
    val expr = XDebuggerUtil.getInstance().createExpression(value, null, null, EvaluationMode.EXPRESSION)
    setValue(expr, object : XValueModifier.XModificationCallback {
        override fun valueModified() = cont.complete(Unit)
        override fun errorOccurred(errorMessage: String) = cont.fail(errorMessage)
    })
}

// The frame's name as the Frames view shows it, e.g. "add:7, App".
fun XStackFrame.title(): String {
    val text = SimpleColoredText()
    customizePresentation(text)
    return text.toString()
}
