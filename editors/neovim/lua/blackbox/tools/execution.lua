-- Continue, pause, step, run-to-line and waiting for the debugger to pause.

local common = require('blackbox.tools.common')
local events = require('blackbox.events')

local M = {}

-- Whether nvim-dap's own command can do it: same session, its stopped
-- thread. Then Neovim's signs and UI stay in step with what happened.
local function nvim_dap_owns(session, thread_id)
  return session == common.dap().session() and thread_id == session.stopped_thread_id
end

-- Sends a resuming request and reports where the debugger pauses next.
local function resume_and_report(command, thread_id, wait_ms)
  local session = common.active_session()
  local thread = common.resolve_thread(session, thread_id)
  local wait = events.arm() -- Before the request, so a fast pause is not missed.
  if command ~= 'pause' then events.on.resumed(session, thread) end
  local dap = common.dap()
  local native = { next = dap.step_over, stepIn = dap.step_into, stepOut = dap.step_out }
  if native[command] and nvim_dap_owns(session, thread) then
    native[command]()
  else
    common.request(session, command, { threadId = thread })
  end
  return common.json(common.describe_wait(wait(wait_ms)))
end

function M.continue(args)
  local session = common.active_session()
  local thread = common.resolve_thread(session, args.threadId)
  events.on.resumed(session, thread)
  if nvim_dap_owns(session, thread) then
    common.dap().continue()
  else
    common.request(session, 'continue', { threadId = thread })
  end
  return 'Resumed execution. Call debug_wait_for_stop to wait for the next pause.'
end

function M.pause(args) return resume_and_report('pause', args.threadId, common.PAUSE_WAIT_MS) end
function M.step_over(args) return resume_and_report('next', args.threadId, common.STEP_WAIT_MS) end
function M.step_into(args) return resume_and_report('stepIn', args.threadId, common.STEP_WAIT_MS) end
function M.step_out(args) return resume_and_report('stepOut', args.threadId, common.STEP_WAIT_MS) end

function M.wait_for_stop(args)
  return common.json(common.describe_wait(events.wait_for_stop(args.timeoutMs or common.DEFAULT_STOP_WAIT_MS, args.next)))
end

function M.run_to_line(args)
  if not args.file or not args.line or args.line < 1 then error('file and line (>= 1) are required', 0) end
  local breakpoints = require('blackbox.tools.breakpoints')
  local session = common.active_session()
  local added = breakpoints.add_temporary(args.file, args.line)
  local ok, report = pcall(function()
    local thread = common.resolve_thread(session, args.threadId)
    local wait = events.arm()
    events.on.resumed(session, thread)
    common.request(session, 'continue', { threadId = thread })
    local result = common.describe_wait(wait(args.timeoutMs or common.DEFAULT_STOP_WAIT_MS))
    result.reachedTarget = result.state == 'stopped' and result.line == args.line and common.same_file(result.file, args.file)
    return result
  end)
  if added then breakpoints.remove_temporary(args.file, args.line) end
  if not ok then error(report, 0) end
  return common.json(report)
end

return M
