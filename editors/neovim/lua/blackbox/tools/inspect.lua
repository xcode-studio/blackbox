-- Threads, stack, variables, evaluation, watches, source context and output.

local common = require('blackbox.tools.common')
local events = require('blackbox.events')

local M = {}

local watches = {} -- watch expressions, kept across steps and sessions

function M.list_threads()
  local session = common.active_session()
  local response = common.request(session, 'threads', nil)
  local paused = {}
  for _, id in ipairs(events.paused_threads(session.id)) do paused[id] = true end
  local threads = vim.tbl_map(function(t)
    return { id = t.id, name = t.name, stopped = paused[t.id] or nil }
  end, response.threads or {})
  return common.json({ session = session.config.name, threads = threads })
end

function M.stack_trace(args)
  local session = common.active_session()
  local levels = math.min(args.levels or 20, 200)
  return common.json(common.stack_frames(session, common.resolve_thread(session, args.threadId), levels))
end

local function variable_view(v, expandable)
  local view = { name = v.name, value = v.value, type = v.type ~= '' and v.type or nil }
  if expandable then
    view.expandable = (v.variablesReference or 0) > 0
    view.variablesReference = (v.variablesReference or 0) > 0 and v.variablesReference or nil
  end
  return view
end

function M.variables(args)
  local session = common.active_session()
  if args.variablesReference then
    local vars = common.request(session, 'variables', { variablesReference = args.variablesReference }).variables or {}
    return common.json(vim.tbl_map(function(v) return variable_view(v, true) end, vars))
  end
  local frame_id = common.frame_id_for(session, args)
  local scopes = common.request(session, 'scopes', { frameId = frame_id }).scopes or {}
  local result = vim.empty_dict()
  local needle = args.filter and args.filter:lower()
  for _, scope in ipairs(scopes) do
    local vars = common.request(session, 'variables', { variablesReference = scope.variablesReference }).variables or {}
    if needle then
      vars = vim.tbl_filter(function(v) return v.name:lower():find(needle, 1, true) ~= nil end, vars)
    end
    result[scope.name] = vim.tbl_map(function(v) return variable_view(v, false) end, vars)
  end
  return common.json(result)
end

function M.evaluate(args)
  local session = common.active_session()
  local response = common.request(session, 'evaluate', {
    expression = args.expression, frameId = common.frame_id_for(session, args), context = 'repl',
  })
  return common.json({
    expression = args.expression,
    result = response.result,
    type = response.type ~= '' and response.type or nil,
    variablesReference = (response.variablesReference or 0) > 0 and response.variablesReference or nil,
  })
end

local function expand(session, ref, depth, max_depth, max_items)
  if depth >= max_depth or ref <= 0 then return '...' end
  local items = common.request(session, 'variables', { variablesReference = ref }).variables or {}
  local result = vim.empty_dict()
  for i = 1, math.min(#items, max_items) do
    local v = items[i]
    result[v.name] = (v.variablesReference or 0) > 0 and expand(session, v.variablesReference, depth + 1, max_depth, max_items) or v.value
  end
  if #items > max_items then result['...'] = ('(%d more items)'):format(#items - max_items) end
  return result
end

function M.inspect(args)
  if not args.variable or args.variable == '' then error('variable expression is required', 0) end
  local session = common.active_session()
  local max_depth = math.min(args.depth or 2, 5)
  local max_items = math.min(args.maxItems or 50, 200)
  local response = common.request(session, 'evaluate', {
    expression = args.variable, frameId = common.frame_id_for(session, args), context = 'repl',
  })
  local value = (response.variablesReference or 0) > 0
    and expand(session, response.variablesReference, 0, max_depth, max_items) or response.result
  return common.json({ variable = args.variable, type = response.type ~= '' and response.type or nil, value = value })
end

-- The scope reference holding `name` in the given frame, if any.
local function scope_containing(session, frame_id, name)
  for _, scope in ipairs(common.request(session, 'scopes', { frameId = frame_id }).scopes or {}) do
    for _, v in ipairs(common.request(session, 'variables', { variablesReference = scope.variablesReference }).variables or {}) do
      if v.name == name then return scope.variablesReference end
    end
  end
  return nil
end

function M.set_variable(args)
  if not args.name or args.value == nil then error('"name" and "value" are required', 0) end
  local session = common.active_session()
  local caps = session.capabilities or {}
  local frame_id = common.frame_id_for(session, args)
  if caps.supportsSetVariable then
    local ref = args.variablesReference or scope_containing(session, frame_id, args.name)
    if ref then
      local r = common.request(session, 'setVariable', { variablesReference = ref, name = args.name, value = args.value })
      return common.json({ name = args.name, value = r.value, type = r.type })
    end
  end
  if caps.supportsSetExpression then
    local r = common.request(session, 'setExpression', { expression = args.name, value = args.value, frameId = frame_id })
    return common.json({ name = args.name, value = r.value, type = r.type })
  end
  error(caps.supportsSetVariable
    and ('no variable named "%s" in the current frame; pass variablesReference for a nested one'):format(args.name)
    or ('the "%s" debug adapter cannot change variables'):format(session.config.type), 0)
end

function M.watch(args)
  local action, expressions = args.action, args.expressions or {}
  if action == 'add' then
    if #expressions == 0 then error('expressions array required for add', 0) end
    for _, e in ipairs(expressions) do
      if not vim.tbl_contains(watches, e) then table.insert(watches, e) end
    end
    return ('Watching %d expression(s). Total watches: %d\n%s'):format(#expressions, #watches, table.concat(watches, ', '))
  elseif action == 'remove' then
    if #expressions == 0 then error('expressions array required for remove', 0) end
    watches = vim.tbl_filter(function(e) return not vim.tbl_contains(expressions, e) end, watches)
    return ('Removed %d. Remaining watches: %d'):format(#expressions, #watches)
  elseif action == 'clear' then
    local count = #watches
    watches = {}
    return ('Cleared all %d watch expression(s)'):format(count)
  elseif action == 'list' then
    if #watches == 0 then return 'No watch expressions set. Use action="add" first.' end
    local session = common.find_session()
    if not session then
      return ('Watch expressions (%d): %s\n(No active debug session — values not available)'):format(#watches, table.concat(watches, ', '))
    end
    local frame_id = common.frame_id_for(session, args)
    local result = vim.empty_dict()
    for _, e in ipairs(watches) do
      local ok, r = pcall(common.request, session, 'evaluate', { expression = e, frameId = frame_id, context = 'watch' })
      if not ok then
        result[e] = '<error: ' .. tostring(r) .. '>'
      elseif (r.variablesReference or 0) > 0 then
        result[e] = expand(session, r.variablesReference, 0, 1, 20)
      else
        result[e] = r.result
      end
    end
    return common.json(result)
  end
  error('action must be add, remove, list, or clear', 0)
end

local function read_lines(file)
  local bufnr = common.buffer_for(file)
  if bufnr ~= -1 and vim.api.nvim_buf_is_loaded(bufnr) then
    return vim.api.nvim_buf_get_lines(bufnr, 0, -1, false) -- Includes unsaved edits.
  end
  return vim.fn.readfile(file)
end

function M.source_context(args)
  local session = common.active_session()
  local radius = math.max(0, math.min(args.lines or 5, 50))
  local frames = common.stack_frames(session, common.resolve_thread(session, args.threadId), 200)
  local frame = frames[1]
  if args.frameId ~= nil then
    frame = vim.iter(frames):find(function(f) return f.id == args.frameId end)
  end
  if not frame then error('no such stack frame — is the debugger stopped at a breakpoint?', 0) end
  local lines = read_lines(frame.file)
  local result = {}
  for n = math.max(1, frame.line - radius), math.min(#lines, frame.line + radius) do
    table.insert(result, { line = n, text = lines[n], current = n == frame.line or nil })
  end
  return common.json({ file = frame.file, line = frame.line, ['function'] = frame.name, lines = result })
end

function M.output(args)
  return common.json(events.read_output(args))
end

return M
