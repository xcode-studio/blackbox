-- Every tool this plugin serves, by name (mirrors tools/catalog.ts in the
-- VS Code extension). Names and arguments follow schema/tools.json; the
-- ide_* tools are answered by the MCP server itself.

local M = {}

function M.handlers()
  local editor = require('blackbox.tools.editor')
  local bp = require('blackbox.tools.breakpoints')
  local session = require('blackbox.tools.session')
  local exec = require('blackbox.tools.execution')
  local inspect = require('blackbox.tools.inspect')

  return {
    -- Breakpoints
    debug_set_breakpoint = bp.set,
    debug_set_function_breakpoint = bp.set_function,
    debug_remove_breakpoint = bp.remove,
    debug_remove_all_breakpoints = bp.remove_all,
    debug_toggle_breakpoints = bp.toggle,
    debug_list_breakpoints = bp.list,
    debug_set_exception_breakpoints = bp.set_exception_filters,

    -- Session control
    debug_get_launch_configs = session.launch_configs,
    debug_start = session.start,
    debug_stop = session.stop,
    debug_restart = session.restart,

    -- Execution
    debug_continue = exec.continue,
    debug_pause = exec.pause,
    debug_step_over = exec.step_over,
    debug_step_into = exec.step_into,
    debug_step_out = exec.step_out,
    debug_run_to_line = exec.run_to_line,
    debug_wait_for_stop = exec.wait_for_stop,

    -- Inspection
    debug_list_threads = inspect.list_threads,
    debug_get_stack_trace = inspect.stack_trace,
    debug_get_variables = inspect.variables,
    debug_evaluate = inspect.evaluate,
    debug_inspect = inspect.inspect,
    debug_set_variable = inspect.set_variable,
    debug_watch = inspect.watch,
    debug_get_source_context = inspect.source_context,
    debug_get_output = inspect.output,

    -- Editor and workspace
    editor_open_file = editor.open_file,
    editor_get_open_files = editor.get_open_files,
    workspace_find_file = editor.find_file,
    workspace_get_diagnostics = editor.get_diagnostics,

    -- Internal: describes this window to the MCP server.
    window_status = editor.window_status,
  }
end

return M
