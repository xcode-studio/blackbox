-- Breakpoint tools on nvim-dap's breakpoint store. nvim-dap has no function
-- breakpoints and no disabled state, so Blackbox keeps those two lists
-- itself and sends them to the debug adapters.

local common = require('blackbox.tools.common')

local M = {}

local SYNC_MS = 1000

local function_breakpoints = {} -- { name, condition, hitCondition, logMessage, enabled }
local disabled = {}             -- source breakpoints switched off: { file, line, condition, hitCondition, logMessage }

-- Loads nvim-dap itself first: that runs its plugin/ setup (breakpoint
-- signs), including when a plugin manager loads nvim-dap lazily.
local function store()
  common.dap()
  return require('dap.breakpoints')
end

local function bufnr_for(file)
  local bufnr = vim.fn.bufadd(file)
  vim.fn.bufload(bufnr)
  return bufnr
end

local function file_of(bufnr) return vim.api.nvim_buf_get_name(bufnr) end

-- Root sessions and their children (js-debug runs the program in a child).
local function all_sessions()
  local all = {}
  for _, s in pairs(common.dap().sessions()) do
    table.insert(all, s)
    for _, child in pairs(s.children or {}) do table.insert(all, child) end
  end
  return all
end

-- Sends the current breakpoints of these buffers to every running debug
-- adapter and waits (up to 1s) until all have answered, so a continue right
-- after a change can't race it.
local function sync(bufnrs)
  local all = all_sessions()
  if #all == 0 or #bufnrs == 0 then return end
  local done, wait = common.counter(#all)
  for _, session in ipairs(all) do
    local payload = {}
    for _, bufnr in ipairs(bufnrs) do
      payload[bufnr] = store().get(bufnr)[bufnr] or {}
    end
    session:set_breakpoints(payload, done)
  end
  wait(SYNC_MS)
end

local function send_function_breakpoints(session)
  if not session.capabilities.supportsFunctionBreakpoints then return end
  local enabled = {}
  for _, bp in ipairs(function_breakpoints) do
    if bp.enabled then
      table.insert(enabled, { name = bp.name, condition = bp.condition, hitCondition = bp.hitCondition })
    end
  end
  session:request('setFunctionBreakpoints', { breakpoints = enabled }, function() end)
end

local function sync_functions()
  for _, session in ipairs(all_sessions()) do send_function_breakpoints(session) end
end

-- New sessions get the function breakpoints once they are initialized.
function M.attach(dap)
  dap.listeners.after.event_initialized['blackbox_function_breakpoints'] = function(session)
    if #function_breakpoints > 0 then send_function_breakpoints(session) end
  end
end

local function describe(spec)
  return spec.file .. ':' .. spec.line
    .. (spec.condition and (' (if: ' .. spec.condition .. ')') or '')
    .. (spec.hitCondition and (' (hits: ' .. spec.hitCondition .. ')') or '')
    .. (spec.logMessage and (' (log: ' .. spec.logMessage .. ')') or '')
end

local function unsupported_note(capability, feature)
  local session = common.find_session()
  if session and next(session.capabilities or {}) and not session.capabilities[capability] then
    return ('\nnote: the "%s" debug adapter does not support %s; it may be ignored'):format(session.config.type, feature)
  end
  return ''
end

local function add(spec)
  store().set({ condition = spec.condition, hit_condition = spec.hitCondition, log_message = spec.logMessage },
    bufnr_for(spec.file), spec.line)
end

local function remove_disabled(file, line)
  for i = #disabled, 1, -1 do
    if common.same_file(disabled[i].file, file) and disabled[i].line == line then table.remove(disabled, i) end
  end
end

function M.set(args)
  local specs = args.breakpoints or { {
    file = args.file, line = args.line, condition = args.condition, hitCondition = args.hitCondition, logMessage = args.logMessage,
  } }
  local results, bufnrs = {}, {}
  for _, spec in ipairs(specs) do
    if not spec.file or not spec.line or spec.line < 1 then
      table.insert(results, 'skip: invalid — file and line (>= 1) required')
    else
      add(spec)
      remove_disabled(spec.file, spec.line)
      table.insert(bufnrs, bufnr_for(spec.file))
      table.insert(results, 'ok: ' .. describe(spec))
    end
  end
  sync(bufnrs)
  local hits = vim.iter(specs):any(function(s) return s.hitCondition ~= nil end)
  return table.concat(results, '\n')
    .. (hits and unsupported_note('supportsHitConditionalBreakpoints', 'hit conditions') or '')
end

function M.set_function(args)
  if not args.name or args.name == '' then error('function name is required', 0) end
  for i = #function_breakpoints, 1, -1 do
    if function_breakpoints[i].name == args.name then table.remove(function_breakpoints, i) end
  end
  table.insert(function_breakpoints, {
    name = args.name, condition = args.condition, hitCondition = args.hitCondition, logMessage = args.logMessage, enabled = true,
  })
  sync_functions()
  return 'ok: function ' .. args.name
    .. (args.condition and (' (if: ' .. args.condition .. ')') or '')
    .. (args.hitCondition and (' (hits: ' .. args.hitCondition .. ')') or '')
    .. unsupported_note('supportsFunctionBreakpoints', 'function breakpoints')
end

local function source_at(file, line)
  local bufnr = common.buffer_for(file)
  if bufnr == -1 then return nil end
  for _, bp in ipairs(store().get(bufnr)[bufnr] or {}) do
    if bp.line == line then return bufnr, bp end
  end
  return nil
end

function M.remove(args)
  local specs = args.breakpoints or (args.file and { { file = args.file, line = args.line } } or {})
  local results, bufnrs = {}, {}
  for _, spec in ipairs(specs) do
    local bufnr = source_at(spec.file, spec.line)
    local was_disabled = vim.iter(disabled):any(function(d) return common.same_file(d.file, spec.file) and d.line == spec.line end)
    if bufnr then
      store().remove(bufnr, spec.line)
      table.insert(bufnrs, bufnr)
    end
    remove_disabled(spec.file, spec.line)
    table.insert(results, (bufnr or was_disabled) and ('ok: removed ' .. spec.file .. ':' .. spec.line)
      or ('skip: no breakpoint at ' .. spec.file .. ':' .. spec.line))
  end
  local functions_changed = false
  for _, name in ipairs(args.functions or {}) do
    local before = #function_breakpoints
    function_breakpoints = vim.tbl_filter(function(bp) return bp.name ~= name end, function_breakpoints)
    local removed = #function_breakpoints < before
    functions_changed = functions_changed or removed
    table.insert(results, removed and ('ok: removed function ' .. name) or ('skip: no function breakpoint on ' .. name))
  end
  sync(bufnrs)
  if functions_changed then sync_functions() end
  return #results > 0 and table.concat(results, '\n') or 'Nothing to remove: pass file+line, breakpoints or functions'
end

function M.remove_all()
  local count = #disabled + #function_breakpoints
  local bufnrs = {}
  for bufnr, bps in pairs(store().get()) do
    count = count + #bps
    table.insert(bufnrs, bufnr)
  end
  if count == 0 then return 'No breakpoints to remove' end
  store().clear()
  disabled, function_breakpoints = {}, {}
  sync(bufnrs)
  sync_functions()
  return 'Removed all ' .. count .. ' breakpoint(s)'
end

function M.toggle(args)
  if type(args.enabled) ~= 'boolean' then error('"enabled" (true or false) is required', 0) end
  local all = args.breakpoints == nil and args.functions == nil
  local changed, already, bufnrs = 0, 0, {}

  local function wanted_source(file, line)
    if all then return true end
    return vim.iter(args.breakpoints or {}):any(function(b) return common.same_file(b.file, file) and b.line == line end)
  end

  if args.enabled then
    for i = #disabled, 1, -1 do
      local d = disabled[i]
      if wanted_source(d.file, d.line) then
        add(d)
        table.insert(bufnrs, bufnr_for(d.file))
        table.remove(disabled, i)
        changed = changed + 1
      end
    end
  else
    for bufnr, bps in pairs(store().get()) do
      for _, bp in ipairs(bps) do
        if wanted_source(file_of(bufnr), bp.line) then
          table.insert(disabled, { file = file_of(bufnr), line = bp.line, condition = bp.condition, hitCondition = bp.hitCondition, logMessage = bp.logMessage })
          store().remove(bufnr, bp.line)
          table.insert(bufnrs, bufnr)
          changed = changed + 1
        end
      end
    end
  end
  for _, bp in ipairs(function_breakpoints) do
    if all or vim.tbl_contains(args.functions or {}, bp.name) then
      if bp.enabled == args.enabled then
        already = already + 1
      else
        bp.enabled = args.enabled
        changed = changed + 1
      end
    end
  end
  sync(bufnrs)
  sync_functions()
  return ('%s %d breakpoint(s)'):format(args.enabled and 'Enabled' or 'Disabled', changed)
    .. (already > 0 and ('; %d already %s'):format(already, args.enabled and 'enabled' or 'disabled') or '')
    .. ((changed + already) == 0 and '. No matching breakpoints.' or '')
end

function M.list()
  local result = {}
  for bufnr, bps in pairs(store().get()) do
    for _, bp in ipairs(bps) do
      table.insert(result, {
        type = 'source', file = file_of(bufnr), line = bp.line, enabled = true,
        condition = bp.condition, hitCondition = bp.hitCondition, logMessage = bp.logMessage,
      })
    end
  end
  for _, d in ipairs(disabled) do
    table.insert(result, {
      type = 'source', file = d.file, line = d.line, enabled = false,
      condition = d.condition, hitCondition = d.hitCondition, logMessage = d.logMessage,
    })
  end
  table.sort(result, function(a, b) return a.file == b.file and a.line < b.line or a.file < b.file end)
  for _, bp in ipairs(function_breakpoints) do
    table.insert(result, { type = 'function', name = bp.name, enabled = bp.enabled, condition = bp.condition, hitCondition = bp.hitCondition })
  end
  return #result > 0 and common.json(result) or '[]'
end

-- Breakpoints the store doesn't count: disabled and function ones.
function M.extra_count()
  return #disabled + #function_breakpoints
end

-- Used by run_to_line: a plain breakpoint added and later removed.
function M.add_temporary(file, line)
  if source_at(file, line) then return false end
  add({ file = file, line = line })
  sync({ bufnr_for(file) })
  return true
end

function M.remove_temporary(file, line)
  local bufnr = source_at(file, line)
  if bufnr then
    store().remove(bufnr, line)
    sync({ bufnr })
  end
end

function M.set_exception_filters(args)
  local session = common.active_session()
  local available = session.capabilities.exceptionBreakpointFilters or {}
  if args.filters == nil then
    return common.json({ session = session.config.name, available = available })
  end
  local known = {}
  for _, f in ipairs(available) do known[f.filter] = true end
  local unknown = vim.tbl_filter(function(f) return not known[f] end, args.filters)
  if #available > 0 and #unknown > 0 then
    error(('unknown exception filter(s): %s. Available: %s'):format(
      table.concat(unknown, ', '), table.concat(vim.tbl_map(function(f) return f.filter end, available), ', ')), 0)
  end
  common.request(session, 'setExceptionBreakpoints', { filters = args.filters })
  return ('Exception breakpoints for "%s": %s. Note: this resets when a new debug session starts.'):format(
    session.config.name, #args.filters > 0 and table.concat(args.filters, ', ') or '(none)')
end

return M
