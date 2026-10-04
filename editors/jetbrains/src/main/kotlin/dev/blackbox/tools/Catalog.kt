// Every tool this plugin serves, by name (mirrors tools/catalog.ts in the
// VS Code extension). Names and arguments follow schema/tools.json; the
// ide_* tools are answered by the MCP server itself.

package dev.blackbox.tools

import com.intellij.openapi.project.Project
import dev.blackbox.ipc.Protocol
import dev.blackbox.ipc.ToolHandler

object Catalog {
    fun handlers(project: Project): Map<String, ToolHandler> {
        val debug = Debug(project)
        val bp = Breakpoints(project)
        val sessions = Sessions(project, debug)
        val exec = Execution(debug)
        val inspect = Inspect(debug)
        val editor = Editor(project, debug, bp)

        return mapOf(
            // Breakpoints
            "debug_set_breakpoint" to { a -> bp.set(a) },
            "debug_set_function_breakpoint" to { a -> bp.setFunction(a) },
            "debug_remove_breakpoint" to { a -> bp.remove(a) },
            "debug_remove_all_breakpoints" to { _ -> bp.removeAll() },
            "debug_toggle_breakpoints" to { a -> bp.toggle(a) },
            "debug_list_breakpoints" to { _ -> bp.list() },
            "debug_set_exception_breakpoints" to { a -> bp.setExceptionFilters(a, debug.findSession()?.sessionName) },

            // Session control
            "debug_get_launch_configs" to { _ -> sessions.launchConfigs() },
            "debug_start" to { a -> sessions.start(a) },
            "debug_stop" to { _ -> sessions.stop() },
            "debug_restart" to { _ -> sessions.restart() },

            // Execution
            "debug_continue" to { a -> exec.continue_(a) },
            "debug_pause" to { a -> exec.pause(a) },
            "debug_step_over" to { a -> exec.stepOver(a) },
            "debug_step_into" to { a -> exec.stepInto(a) },
            "debug_step_out" to { a -> exec.stepOut(a) },
            "debug_run_to_line" to { a -> exec.runToLine(a) },
            "debug_wait_for_stop" to { a -> exec.waitForStop(a) },

            // Inspection
            "debug_list_threads" to { _ -> inspect.listThreads() },
            "debug_get_stack_trace" to { a -> inspect.stackTrace(a) },
            "debug_get_variables" to { a -> inspect.variables(a) },
            "debug_evaluate" to { a -> inspect.evaluate(a) },
            "debug_inspect" to { a -> inspect.inspect(a) },
            "debug_set_variable" to { a -> inspect.setVariable(a) },
            "debug_watch" to { a -> inspect.watch(a) },
            "debug_get_source_context" to { a -> inspect.sourceContext(a) },
            "debug_get_output" to { a -> inspect.output(a) },

            // Editor and workspace
            "editor_open_file" to { a -> editor.openFile(a) },
            "editor_get_open_files" to { _ -> editor.openFiles() },
            "workspace_find_file" to { a -> editor.findFile(a) },
            "workspace_get_diagnostics" to { a -> editor.diagnostics(a) },

            // Internal: describes this window to the MCP server.
            Protocol.STATUS_TOOL to { _ -> editor.windowStatus() },
        )
    }
}
