-- Helpers shared by the debug tools (mirrors the helper section of
-- editors/vscode/src/tools/impl/debug.ts). Everything here runs inside the
-- request coroutine started by server.lua.

local events = require('blackbox.events')

local uv = vim.uv or vim.loop

local M = {}

M.STEP_WAIT_MS = 10000
M.PAUSE_WAIT_MS = 5000
M.DEFAULT_STOP_WAIT_MS = 30000
M.STOP_FRAMES = 5

function M.dap()
  local ok, dap = pcall(require, 'dap')
  if not ok then
    error('nvim-dap is not installed; Blackbox debug tools need https://github.com/mfussenegger/nvim-dap', 0)
  end
  return dap
end

-- The full text of a DAP error response: the short message plus the
-- adapter's detailed one (body.error.format, with its {variables} filled).
function M.error_text(err)
  local detail = type(err) == 'table' and type(err.body) == 'table' and type(err.body.error) == 'table' and err.body.error or nil
  local text = detail and detail.format and detail.format:gsub('{(%w+)}', function(name)
    local value = (detail.variables or {})[name]
    return value ~= nil and tostring(value) or ('{' .. name .. '}')
  end) or nil
  local message
  if type(err) == 'table' then message = err.message else message = tostring(err) end
  if text and message and text:sub(1, #message) ~= message then
    return message .. ': ' .. text
  end
  if text then return text end -- Already includes the message (e.g. delve).
  return message or text or 'unknown error'
end

local REQUEST_TIMEOUT_MS = 60000

-- Requests waiting for an answer, per session: closing a session drops its
-- pending callbacks inside nvim-dap, so they are failed from here instead.
local pending = setmetatable({}, { __mode = 'k' })

-- Resumes a suspended coroutine once; later calls do nothing.
local function resumer(co)
  local used = false
  return function(...)
    if used or coroutine.status(co) ~= 'suspended' then return end
    used = true
    coroutine.resume(co, ...)
  end
end

-- Sends a DAP request and waits for its response, failing if the session
-- closes or the adapter doesn't answer in time.
function M.request(session, command, arguments)
  if session.closed then error('the debug session has ended', 0) end
  local co = assert(coroutine.running(), 'request() must run in a coroutine')
  local resume = resumer(co)
  pending[session] = pending[session] or {}
  pending[session][resume] = true

  local timer = uv.new_timer()
  timer:start(REQUEST_TIMEOUT_MS, 0, vim.schedule_wrap(function()
    resume({ message = ('the debug adapter did not answer %s within %ds'):format(command, REQUEST_TIMEOUT_MS / 1000) })
  end))
  session:request(command, arguments, function(err, response) resume(err, response) end)
  local err, response = coroutine.yield()
  timer:stop()
  timer:close()
  pending[session][resume] = nil
  if err then
    error(M.error_text(err), 0)
  end
  return response or {}
end

-- Called when a session closes: fails every request still waiting on it.
function M.session_closed(session)
  for resume in pairs(pending[session] or {}) do
    resume({ message = 'the debug session ended' })
  end
  pending[session] = nil
end

-- The session commands act on: the focused one if it is paused, otherwise
-- the most recently paused one (adapters such as js-debug pause a child
-- session), otherwise the focused one.
function M.find_session()
  local focused = M.dap().session()
  if focused and events.is_paused(focused.id) then return focused end
  local current = events.current_stop()
  return current and current.session or focused
end

function M.active_session()
  return M.find_session() or error('no active debug session', 0)
end

-- The session the user started; child sessions end with it.
function M.root_session(session)
  while session.parent do session = session.parent end
  return session
end

-- The thread a command acts on: the one asked for, the one nvim-dap has
-- focused, the one that last paused, then the first thread. Thread ids can
-- be 0 (js-debug), so test for nil, not falsiness.
function M.resolve_thread(session, thread_id)
  if thread_id ~= nil then return thread_id end
  if session.stopped_thread_id ~= nil then return session.stopped_thread_id end
  local stopped = events.last_stop(session.id)
  if stopped and stopped.threadId ~= nil then return stopped.threadId end
  local threads = M.request(session, 'threads', nil).threads or {}
  if threads[1] == nil then
    error('no threads — is the debugger stopped at a breakpoint?', 0)
  end
  return threads[1].id
end

local function to_frame(f)
  return {
    id = f.id,
    name = f.name,
    file = f.source and (f.source.path or f.source.name) or '(unknown)',
    line = f.line,
  }
end

function M.stack_frames(session, thread_id, levels)
  local response = M.request(session, 'stackTrace', { threadId = thread_id, startFrame = 0, levels = levels })
  return vim.tbl_map(to_frame, response.stackFrames or {})
end

function M.top_frame_id(session, thread_id)
  local frame = M.stack_frames(session, thread_id, 1)[1]
  if not frame then
    error('no stack frames — is the debugger stopped at a breakpoint?', 0)
  end
  return frame.id
end

-- The frame to inspect: the one asked for, else the top of the chosen thread.
function M.frame_id_for(session, target)
  if target.frameId ~= nil then return target.frameId end
  return M.top_frame_id(session, M.resolve_thread(session, target.threadId))
end

function M.describe_stop(session, stop)
  local thread_id = stop.threadId
  if thread_id == nil then thread_id = M.resolve_thread(session) end
  local ok, frames = pcall(M.stack_frames, session, thread_id, M.STOP_FRAMES)
  frames = ok and frames or {}
  local top = frames[1] or {}
  return {
    state = 'stopped',
    session = session.config and session.config.name,
    reason = stop.reason,
    description = stop.description,
    threadId = thread_id,
    file = top.file,
    line = top.line,
    ['function'] = top.name,
    frames = frames,
  }
end

function M.describe_wait(result)
  if result.kind == 'stopped' then
    return M.describe_stop(result.session, result.stop)
  elseif result.kind == 'timeout' then
    return { state = 'running', hint = 'Not paused yet. Call debug_wait_for_stop to keep waiting.' }
  end
  return { state = 'terminated' }
end

-- vim.json.encode escapes "/" as "\/" (and 0.10 has no option to stop it).
-- Undo that for readability: a literal backslash is encoded as "\\", so
-- the remaining "\\/" still decodes to the same text.
function M.json(value)
  return (vim.json.encode(value):gsub('\\/', '/'))
end

-- Waits for `count` callbacks (or the timeout), from a coroutine.
-- Returns a function to pass as each callback, and a wait() function.
function M.counter(count)
  local remaining, waiting, timed_out = count, nil, false
  local function done()
    remaining = remaining - 1
    if remaining <= 0 and waiting then
      local co = waiting
      waiting = nil
      coroutine.resume(co)
    end
  end
  local function wait(timeout_ms)
    if remaining <= 0 then return true end
    waiting = coroutine.running()
    local timer = uv.new_timer()
    timer:start(timeout_ms, 0, vim.schedule_wrap(function()
      if waiting then
        timed_out = true
        local co = waiting
        waiting = nil
        coroutine.resume(co)
      end
    end))
    coroutine.yield()
    timer:stop()
    timer:close()
    return not timed_out
  end
  return done, wait
end

-- Lets the editor process pending events, from a coroutine.
function M.sleep(ms)
  local co = coroutine.running()
  vim.defer_fn(function() coroutine.resume(co) end, ms)
  coroutine.yield()
end

-- The buffer showing exactly this file, or -1. (vim.fn.bufnr() would also
-- accept a unique partial match, i.e. possibly another file.)
function M.buffer_for(file)
  for _, bufnr in ipairs(vim.api.nvim_list_bufs()) do
    local name = vim.api.nvim_buf_get_name(bufnr)
    if name ~= '' and M.same_file(name, file) then return bufnr end
  end
  return -1
end

-- Same file, seeing through symlinks (e.g. WordPress wp/ -> wordpress-develop/src/).
function M.same_file(a, b)
  if not a or not b then return false end
  if a == b then return true end
  return (uv.fs_realpath(a) or vim.fs.normalize(a)) == (uv.fs_realpath(b) or vim.fs.normalize(b))
end

return M
