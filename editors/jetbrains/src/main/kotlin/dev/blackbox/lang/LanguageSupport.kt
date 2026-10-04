// What differs between languages: function breakpoints and exception
// breakpoints are language-specific breakpoint types in JetBrains IDEs.
// Implementations are registered only when their language plugin is present
// (see java.xml), so one Blackbox plugin works in every IDE.

package dev.blackbox.lang

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint

data class ExceptionFilter(val filter: String, val label: String, val enabled: Boolean)

// A way to debug that waits for the program to connect instead of starting
// it, e.g. PhpStorm's "Listen for PHP Debug Connections" (VS Code's "Listen
// for Xdebug"). It shows as a launch configuration named `name`.
interface DebugListener {
    val name: String
    val hint: String // What to do once it listens, for the AI.
    fun isListening(project: Project): Boolean
    fun setListening(project: Project, on: Boolean) // Called on the EDT.
}

interface LanguageSupport {
    // Adds a breakpoint on the named function, or returns null if this
    // language has no such function. Called on the EDT inside a write action.
    fun addFunctionBreakpoint(project: Project, name: String): XBreakpoint<*>?

    // The function name of a function breakpoint of this language, else null.
    fun functionName(breakpoint: XBreakpoint<*>): String?

    fun exceptionFilters(project: Project): List<ExceptionFilter>

    // Called on the EDT.
    fun setExceptionFilters(project: Project, filters: Collection<String>)

    fun listeners(): List<DebugListener> = emptyList()

    companion object {
        val EP = ExtensionPointName<LanguageSupport>("dev.blackbox.languageSupport")

        fun all(): List<LanguageSupport> = EP.extensionList

        fun functionName(breakpoint: XBreakpoint<*>): String? = all().firstNotNullOfOrNull { it.functionName(breakpoint) }

        fun listeners(): List<DebugListener> = all().flatMap { it.listeners() }

        // Whether a program may still connect, so waits must not end yet.
        fun anyListening(project: Project): Boolean = listeners().any { it.isListening(project) }

        // A function breakpoint on `name`: the full name, or its tail after a
        // package or namespace ("App.add" matches "com.example.App.add",
        // "Foo::bar" matches "App\\Foo::bar").
        fun nameMatches(actual: String, wanted: String) =
            actual == wanted || actual.endsWith(".$wanted") || actual.endsWith("\\$wanted")
    }
}
