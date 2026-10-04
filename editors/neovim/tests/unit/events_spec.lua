-- Port of editors/vscode/src/test/unit/events.test.ts: the events hub fed
-- with fake sessions, as nvim-dap's listeners would.
local events = require('blackbox.events')

local function session(id, name) return { id = id, config = { name = name } } end

-- Runs fn in a coroutine and waits for it (wait() needs one).
local function run(fn)
  local result, finished
  coroutine.wrap(function() result = fn(); finished = true end)()
  vim.wait(2000, function() return finished end, 5)
  return result
end

describe('events', function()
  local parent, child
  before_each(function()
    events._reset()
    parent, child = session(1, 'Launch app'), session(2, 'app.go')
    -- Pretend both sessions are live.
    package.loaded['dap'] = { sessions = function() return { parent, child } end }
  end)
  after_each(function() package.loaded['dap'] = nil end)

  it('a wait armed before the request sees a fast pause', function()
    local result = run(function()
      local wait = events.arm()
      events.on.stopped(child, { reason = 'step', threadId = 3 })
      return wait(1000)
    end)
    assert.are.same({ 'stopped', 2, 'step', 3 }, { result.kind, result.session.id, result.stop.reason, result.stop.threadId })
  end)

  it('a pause on a child session satisfies the wait', function()
    local result = run(function()
      vim.defer_fn(function() events.on.stopped(child, { reason = 'breakpoint', threadId = 1 }) end, 10)
      return events.wait_for_stop(1000)
    end)
    assert.equals(2, result.session.id)
  end)

  it('waiting while already paused returns at once; next waits for a new pause', function()
    events.on.stopped(child, { reason = 'breakpoint', threadId = 2 })
    assert.equals('stopped', run(function() return events.wait_for_stop(1) end).kind)
    local result = run(function()
      vim.defer_fn(function() events.on.stopped(child, { reason = 'breakpoint', threadId = 3 }) end, 10)
      return events.wait_for_stop(1000, true)
    end)
    assert.equals(3, result.stop.threadId)
    assert.equals('timeout', run(function() return events.wait_for_stop(20, true) end).kind)
  end)

  it('times out without a pause', function()
    assert.equals('timeout', run(function() return events.wait_for_stop(20) end).kind)
  end)

  it('resolves as terminated when the last session ends', function()
    local result = run(function()
      vim.defer_fn(function() events.on.ended(child, 0) end, 10)
      return events.wait_for_stop(1000)
    end)
    assert.equals('terminated', result.kind)
  end)

  it('tracks several paused threads in one session (Xdebug requests)', function()
    events.on.stopped(child, { reason = 'breakpoint', threadId = 2 })
    events.on.stopped(child, { reason = 'breakpoint', threadId = 3 })
    local threads = events.paused_threads(2)
    table.sort(threads)
    assert.are.same({ 2, 3 }, threads)
    assert.equals(3, events.current_stop().stop.threadId)
    events.on.resumed(child, 3)
    assert.are.same({ 2 }, events.paused_threads(2))
    assert.equals(2, events.last_stop(2).threadId)
  end)

  it('a continued event affects only its thread unless all threads continued (as DAP says)', function()
    events.on.stopped(child, { reason = 'breakpoint', threadId = 2 })
    events.on.stopped(child, { reason = 'breakpoint', threadId = 3 })
    events.on.continued(child, { threadId = 2 })
    assert.are.same({ 3 }, events.paused_threads(2))
    events.on.continued(child, { threadId = 3, allThreadsContinued = true })
    assert.are.same({}, events.paused_threads(2))
  end)

  it('a continue response resumes every thread unless it says otherwise', function()
    events.on.stopped(child, { threadId = 2 })
    events.on.stopped(child, { threadId = 3 })
    events.on.resume_response(child, nil, { allThreadsContinued = false }, { threadId = 3 })
    assert.are.same({ 2 }, events.paused_threads(2))
    events.on.resume_response(child, nil, {}, { threadId = 2 })
    assert.is_false(events.is_paused(2))
    events.on.stopped(child, { threadId = 2 })
    events.on.resume_response(child, { message = 'no' }, nil, { threadId = 2 })
    assert.is_true(events.is_paused(2), 'a failed continue leaves it paused')
  end)

  it('a closed session no longer counts as paused, and its waits end', function()
    events.on.stopped(child, { threadId = 1 })
    child.closed = true
    assert.is_false(events.is_paused(2))
    assert.is_nil(events.current_stop())
    assert.equals(1, events.live_count())
  end)

  it('keeps output with sequence numbers, filters and pages it', function()
    events.on.output(child, { category = 'stdout', output = 'hello\n' })
    events.on.output(parent, { category = 'console', output = 'logpoint: x=1\n' })
    events.on.output(parent, { category = 'telemetry', output = 'ignored' })
    local all = events.read_output({})
    assert.are.same({ 1, 2 }, vim.tbl_map(function(e) return e.seq end, all.entries))
    assert.equals('app.go', all.entries[1].session)
    assert.equals(2, all.nextSince)
    assert.are.same({ 2 }, vim.tbl_map(function(e) return e.seq end, events.read_output({ since = 1 }).entries))
    assert.are.same({ 2 }, vim.tbl_map(function(e) return e.seq end, events.read_output({ match = 'LOGPOINT' }).entries))
    local page = events.read_output({ limit = 1 })
    assert.is_true(page.more)
    assert.equals(1, page.nextSince)
  end)

  it('shortens huge entries and keeps a response within its budget', function()
    events.on.output(child, { output = ('x'):rep(3000000) })
    local huge = events.read_output({}).entries[1]
    assert.is_true(#huge.text < 2100)
    assert.is_truthy(huge.text:find('more characters%)$'))
    events._reset()
    for _ = 1, 60 do events.on.output(child, { output = ('y'):rep(1900) }) end
    local page = events.read_output({})
    local size = 0
    for _, e in ipairs(page.entries) do size = size + #e.text end
    assert.is_true(size <= 50000)
    assert.is_true(page.more)
  end)
end)
