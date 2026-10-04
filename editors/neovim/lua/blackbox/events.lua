-- Follows nvim-dap's sessions so tools can wait for the debugger to pause and
-- read program output (port of editors/vscode/src/tools/impl/debugEvents.ts).
--
-- Pause state is kept per thread: some adapters (Xdebug) pause each thread,
-- i.e. each HTTP request, on its own. Waits cover every session, because some
-- adapters (js-debug) pause a child session rather than the one started.

local uv = vim.uv or vim.loop

local M = {}

local MAX_OUTPUT = 1000
local DEFAULT_OUTPUT_LIMIT = 200
-- Some adapters log their own protocol traffic as output (php-debug with
-- "log": true sends megabytes), so keep responses small.
local MAX_ENTRY_CHARS = 2000
local MAX_OUTPUT_CHARS = 50000
local NO_THREAD = -1

-- Responses after which threads run until their next stopped event. Only
-- continue-like ones: a stepped thread's stopped event can be processed
-- before the step's response, and Blackbox's own steps mark the thread as
-- running before sending the request anyway.
local RESUMING = { 'continue', 'reverseContinue' }

local tracked = {}   -- session id -> { session, paused = { [thread] = { stop, order } }, last_stop }
local listeners = {} -- set of waiter callbacks
local output, output_seq, stop_counter = {}, 0, 0

local function track(session)
  local t = tracked[session.id]
  if not t then
    t = { session = session, paused = {} }
    tracked[session.id] = t
  end
  return t
end

local function notify(result)
  for listener in pairs(listeners) do listener(result) end
end

local function live_sessions()
  local ok, dap = pcall(require, 'dap')
  return ok and dap.sessions() or {}
end

-- Event and response handlers; exposed for tests.
M.on = {}

function M.on.stopped(session, body)
  local t = track(session)
  local stop = { reason = body.reason, threadId = body.threadId, description = body.description, text = body.text }
  t.last_stop = stop
  stop_counter = stop_counter + 1
  t.paused[body.threadId or NO_THREAD] = { stop = stop, order = stop_counter }
  if body.allThreadsStopped then t.all_stopped = true end
  notify({ kind = 'stopped', session = session, stop = stop })
end

-- Marks one thread, or with no thread id every thread, as running.
function M.on.resumed(session, thread_id)
  local t = track(session)
  if thread_id == nil then
    t.paused = {}
  else
    t.paused[thread_id] = nil
    t.paused[NO_THREAD] = nil
  end
end

-- As in DAP and debugEvents.ts: only the given thread, unless the adapter
-- says all threads continued.
function M.on.continued(session, body)
  M.on.resumed(session, body.allThreadsContinued and nil or body.threadId)
end

-- A response to a resuming request the user sent from Neovim.
-- A continue response resumes every thread unless it says otherwise.
function M.on.resume_response(session, err, response, request_args)
  if err then return end
  local thread = type(request_args) == 'table' and request_args.threadId or nil
  local all = not (type(response) == 'table' and response.allThreadsContinued == false)
  M.on.resumed(session, all and nil or thread)
end

function M.on.output(session, body)
  if type(body.output) ~= 'string' or body.category == 'telemetry' then return end
  output_seq = output_seq + 1
  table.insert(output, {
    seq = output_seq,
    session = session.config and session.config.name or tostring(session.id),
    category = body.category or 'console',
    text = body.output,
  })
  if #output > MAX_OUTPUT then table.remove(output, 1) end
end

function M.on.ended(session, sessions_left)
  tracked[session.id] = nil
  require('blackbox.tools.common').session_closed(session)
  if sessions_left == 0 then notify({ kind = 'terminated' }) end
end

function M.attach(dap)
  local key = 'blackbox'
  local after = dap.listeners.after
  after.event_stopped[key] = function(session, body) M.on.stopped(session, body or {}) end
  after.event_continued[key] = function(session, body) M.on.continued(session, body or {}) end
  after.event_output[key] = function(session, body) M.on.output(session, body or {}) end
  for _, command in ipairs(RESUMING) do
    after[command][key] = function(session, err, response, args) M.on.resume_response(session, err, response, args) end
  end
  -- Every way a session ends goes through Session:close() and its on_close
  -- hooks (terminate response, disconnect timeout, adapter crash, ...), so
  -- hook in as soon as the adapter is up. The hook may run in a libuv
  -- callback, hence vim.schedule.
  after.initialize[key] = function(session)
    track(session)
    session.on_close[key] = function(s)
      vim.schedule(function() M.on.ended(s, M.live_count()) end)
    end
  end
end

-- Sessions not closed yet (nvim-dap drops closed ones a little later).
function M.live_count()
  local count = 0
  for _, s in pairs(live_sessions()) do
    if not s.closed then count = count + 1 end
  end
  return count
end

local function latest_pause(t)
  local latest
  for _, pause in pairs(t.paused) do
    if not latest or pause.order > latest.order then latest = pause end
  end
  return latest
end

-- The most recent pause still in effect, across sessions, with its location
-- when nvim-dap knows the current frame.
function M.current_stop()
  local best
  for _, t in pairs(tracked) do
    local pause = not t.session.closed and latest_pause(t) or nil
    if pause and (not best or pause.order > best.order) then
      best = { order = pause.order, session = t.session, stop = pause.stop }
    end
  end
  if not best then return nil end
  local frame = best.session.current_frame
  if frame and frame.source and frame.source.path then
    best.location = { file = frame.source.path, line = frame.line }
  end
  return best
end

function M.is_paused(session_id)
  local t = tracked[session_id]
  return t ~= nil and not t.session.closed and next(t.paused) ~= nil
end

function M.paused_threads(session_id)
  local ids = {}
  for id in pairs((tracked[session_id] or {}).paused or {}) do
    if id ~= NO_THREAD then table.insert(ids, id) end
  end
  return ids
end

-- The most recent pause in effect in this session, else the last one seen.
function M.last_stop(session_id)
  local t = tracked[session_id]
  if not t then return nil end
  local pause = latest_pause(t)
  return pause and pause.stop or t.last_stop
end

-- Starts listening now, before a request is sent, so a fast pause is not
-- missed. Returns wait(timeout_ms), to call from a coroutine.
function M.arm()
  local result = M.live_count() == 0 and { kind = 'terminated' } or nil
  local waiting -- the coroutine to resume
  local listener
  listener = function(r)
    listeners[listener] = nil
    result = r
    if waiting then
      local co = waiting
      waiting = nil
      coroutine.resume(co)
    end
  end
  if not result then listeners[listener] = true end

  return function(timeout_ms)
    if result then return result end
    waiting = assert(coroutine.running(), 'wait() must run in a coroutine')
    local timer = uv.new_timer()
    timer:start(timeout_ms, 0, vim.schedule_wrap(function()
      if waiting then listener({ kind = 'timeout' }) end
    end))
    coroutine.yield()
    timer:stop()
    timer:close()
    return result
  end
end

-- Returns the current pause at once, unless `next` asks for a new one.
function M.wait_for_stop(timeout_ms, next_only)
  local current = not next_only and M.current_stop() or nil
  if current then
    return { kind = 'stopped', session = current.session, stop = current.stop }
  end
  return M.arm()(timeout_ms)
end

local function truncate(text, max)
  if #text <= max then return text end
  return ('%s… (%d more characters)'):format(text:sub(1, max), #text - max)
end

---@param query { since: integer?, category: string?, match: string?, limit: integer? }
function M.read_output(query)
  local since, category = query.since or 0, query.category
  local limit = math.max(1, query.limit or DEFAULT_OUTPUT_LIMIT)
  local needle = query.match and query.match:lower() or nil
  local matching = {}
  for _, e in ipairs(output) do
    if e.seq > since and (not category or e.category == category)
      and (not needle or e.text:lower():find(needle, 1, true)) then
      table.insert(matching, e)
    end
  end
  local entries, budget = {}, MAX_OUTPUT_CHARS
  for _, e in ipairs(matching) do
    local text = truncate(e.text, MAX_ENTRY_CHARS)
    if #entries >= limit or (#entries > 0 and #text > budget) then break end
    table.insert(entries, { seq = e.seq, session = e.session, category = e.category, text = text })
    budget = budget - #text
  end
  local more = #entries < #matching
  local next_since = more and entries[#entries].seq or math.max(since, output_seq)
  return { entries = entries, nextSince = next_since, more = more }
end

-- For tests: forget everything.
function M._reset()
  tracked, listeners, output, output_seq, stop_counter = {}, {}, {}, 0, 0
end

return M
