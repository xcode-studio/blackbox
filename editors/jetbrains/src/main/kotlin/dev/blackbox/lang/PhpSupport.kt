// PHP in PhpStorm (and IntelliJ IDEA Ultimate with the PHP plugin): method
// breakpoints, Xdebug exception breakpoints, and listening for incoming
// Xdebug connections.
//
// Uses the PHP debugger's classes by name: they are only in IDEs with the
// PHP plugin (registered through blackbox-php.xml), and recent PhpStorm
// releases are built for a newer JVM than this plugin compiles against.

package dev.blackbox.lang

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType

class PhpSupport : LanguageSupport {
    @Suppress("UNCHECKED_CAST")
    private fun type(className: String): XBreakpointType<XBreakpoint<XBreakpointProperties<*>>, XBreakpointProperties<*>>? =
        XBreakpointType.EXTENSION_POINT_NAME.extensionList.firstOrNull { it.javaClass.name == className }
            as XBreakpointType<XBreakpoint<XBreakpointProperties<*>>, XBreakpointProperties<*>>?

    private fun call(target: Any, method: String, vararg args: Any?): Any? =
        target.javaClass.methods.first { it.name == method && it.parameterCount == args.size }.invoke(target, *args)

    // "Class::method", "Namespace\\Class::method", "Class->method" or "function".
    override fun addFunctionBreakpoint(project: Project, name: String): XBreakpoint<*>? {
        val type = type(METHOD_TYPE) ?: return null
        val separator = listOf("::", "->").firstOrNull { it in name }
        val className = separator?.let { name.substringBefore(it) }.orEmpty()
        val methodName = separator?.let { name.substringAfter(it) } ?: name
        if (methodName.isEmpty() || !methodName.all { it.isLetterOrDigit() || it == '_' }) return null
        val properties = type.createProperties() ?: return null
        call(properties, "setClassName", className)
        call(properties, "setMethodName", methodName)
        return XDebuggerManager.getInstance(project).breakpointManager.addBreakpoint(type, properties)
    }

    override fun functionName(breakpoint: XBreakpoint<*>): String? {
        if (breakpoint.type.javaClass.name != METHOD_TYPE) return null
        val properties = breakpoint.properties ?: return null
        val className = call(properties, "getClassName") as String?
        val methodName = call(properties, "getMethodName") as String? ?: return null
        return if (className.isNullOrEmpty()) methodName else "$className::$methodName"
    }

    private fun exceptionBreakpoints(project: Project): Map<String, XBreakpoint<*>> {
        val type = type(EXCEPTION_TYPE) ?: return emptyMap()
        return XDebuggerManager.getInstance(project).breakpointManager.getBreakpoints(type)
            .mapNotNull { bp -> (bp.properties?.let { call(it, "getExceptionName") } as String?)?.let { it to bp } }
            .toMap()
    }

    // The same filters as VS Code's PHP Debug extension; Xdebug matches them
    // by name ("*" is everything).
    override fun exceptionFilters(project: Project): List<ExceptionFilter> {
        if (type(EXCEPTION_TYPE) == null) return emptyList()
        val set = exceptionBreakpoints(project)
        return FILTERS.map { (filter, label) -> ExceptionFilter(filter, label, set[filter]?.isEnabled == true) }
    }

    override fun setExceptionFilters(project: Project, filters: Collection<String>) {
        val type = type(EXCEPTION_TYPE) ?: return
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val existing = exceptionBreakpoints(project).filterKeys { it in FILTERS }
        for ((name, bp) in existing) if (name !in filters) manager.removeBreakpoint(bp)
        for (name in filters.filter { it in FILTERS }) {
            val bp = existing[name]
            if (bp != null) {
                bp.isEnabled = true
                continue
            }
            val properties = type.createProperties() ?: continue
            call(properties, "setExceptionName", name)
            manager.addBreakpoint(type, properties)
        }
    }

    override fun listeners(): List<DebugListener> = listOf(XdebugListener)

    private object XdebugListener : DebugListener {
        override val name = "Listen for PHP Debug Connections"
        override val hint = "PhpStorm is listening for Xdebug. Trigger the code (e.g. a request with ?XDEBUG_TRIGGER=1, " +
            "or a CLI script with XDEBUG_SESSION=1), then call debug_wait_for_stop. The first connection from a new " +
            "server may show a dialog in PhpStorm to map its paths."

        private fun accepter(project: Project): Any? = runCatching {
            Class.forName(ACCEPTER, true, PhpSupport::class.java.classLoader)
                .getMethod("getInstance", Project::class.java).invoke(null, project)
        }.getOrNull()

        override fun isListening(project: Project): Boolean =
            accepter(project)?.let { it.javaClass.getMethod("isStarted").invoke(it) as Boolean } == true

        override fun setListening(project: Project, on: Boolean) {
            val accepter = accepter(project) ?: return
            if (isListening(project) != on) accepter.javaClass.getMethod("doSwitch").invoke(accepter)
        }
    }

    private companion object {
        const val METHOD_TYPE = "com.jetbrains.php.debug.common.PhpMethodBreakpointType"
        const val EXCEPTION_TYPE = "com.jetbrains.php.debug.common.PhpExceptionBreakpointType"
        const val ACCEPTER = "com.jetbrains.php.debug.listener.PhpDebugExternalConnectionsAccepter"
        val FILTERS = linkedMapOf(
            "Notice" to "Notices",
            "Warning" to "Warnings",
            "Error" to "Errors",
            "Exception" to "Exceptions",
            "*" to "Everything",
        )
    }
}
