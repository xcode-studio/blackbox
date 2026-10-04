-- Launch configurations and session control on nvim-dap.

local common = require('blackbox.tools.common')

local uv = vim.uv or vim.loop

local M = {}

local START_PROMPT_MS = 20000
-- Below the MCP server's 60s limit for debug_start, so the note arrives.
local LAUNCH_MS = 45000
local TICK_MS = 1000

-- Launch configurations: each folder's .vscode/launch.json, then
-- nvim-dap's own dap.configurations (per filetype).
local function launch_files()
  local files = {}
  local ok_vscode, vscode = pcall(require, 'dap.ext.vscode')
  for _, folder in ipairs(require('blackbox').folders()) do
    local path = vim.fs.joinpath(folder, '.vscode', 'launch.json')
    if ok_vscode and uv.fs_stat(path) then
      local ok, configs = pcall(vscode.getconfigs, path)
      if ok and configs and #configs > 0 then
        table.insert(files, { folder = folder, configurations = configs, from_launch_json = true })
      end
    end
  end
  for filetype, configs in pairs(common.dap().configurations or {}) do
    if type(configs) == 'table' and #configs > 0 then
      table.insert(files, { folder = 'nvim-dap configurations (' .. filetype .. ')', configurations = configs })
    end
  end
  return files
end

-- Configurations may hold functions (nvim-dap allows them); JSON shows a marker.
local function printable(value)
  if type(value) == 'function' then return '<function>' end
  if type(value) ~= 'table' then return value end
  local copy = {}
  for k, v in pairs(value) do copy[k] = printable(v) end
  return copy
end

function M.launch_configs()
  local files = vim.tbl_map(function(f) return { folder = f.folder, configurations = f.configurations } end, launch_files())
  if #files == 0 then return 'No launch configurations found (.vscode/launch.json or dap.configurations)' end
  return common.json(printable(files))
end

-- Fills ${workspaceFolder} (and its basename) with the folder the
-- configuration came from: nvim-dap would use the current window's cwd,
-- which differs for configurations from another tab's folder.
local function with_folder(value, folder)
  if type(value) == 'string' then
    return (value:gsub('%${workspaceFolder}', function() return folder end)
      :gsub('%${workspaceFolderBasename}', function() return vim.fs.basename(folder) end))
  elseif type(value) == 'table' then
    local copy = {}
    for k, v in pairs(value) do copy[k] = with_folder(v, folder) end
    return copy
  end
  return value
end
M._with_folder = with_folder -- for tests

-- The named configuration, with its folder filled in when it came from a
-- launch.json.
local function find_config(name, folder)
  local names = {}
  for _, file in ipairs(launch_files()) do
    if not folder or file.folder == folder then
      for _, config in ipairs(file.configurations) do
        if config.name == name then
          return file.from_launch_json and with_folder(config, file.folder) or vim.deepcopy(config)
        end
        table.insert(names, config.name)
      end
    end
  end
  error(('no launch configuration named "%s". Available: %s'):format(
    name, #names > 0 and table.concat(names, ', ') or '(none)'), 0)
end

local function check_adapter(config)
  local dap = common.dap()
  if not dap.adapters[config.type] then
    error(('no nvim-dap adapter for "%s". Install one (e.g. :MasonInstall php-debug-adapter for PHP) '
      .. 'and define dap.adapters.%s'):format(config.type, config.type), 0)
  end
end

-- Starts the session and waits until nvim-dap has one, or explains what is
-- probably holding it up instead of hanging.
-- Starts the session and waits for the adapter's answer to the launch (or
-- attach) request: its error is returned instead of a false "started".
-- If no adapter comes up at all, explains what is probably holding it up
-- instead of hanging.
local function run_watched(config)
  local dap = common.dap()
  local co = coroutine.running()
  local outcome -- nil while waiting; then 'started' | 'slow' | 'stalled' | { error = message }
  local initialized = false
  local key = 'blackbox_start_' .. tostring(uv.hrtime())
  local started_at = uv.now()

  local function finish(value)
    if outcome then return end
    outcome = value
    vim.schedule(function()
      if coroutine.status(co) == 'suspended' then coroutine.resume(co) end
    end)
  end
  -- Works on every nvim-dap version (listeners.on_session is newer).
  dap.listeners.after.initialize[key] = function(session)
    initialized = true
    session.on_close[key] = function()
      finish({ error = 'the debug session ended before it launched' })
    end
  end
  for _, request in ipairs({ 'launch', 'attach' }) do
    dap.listeners.after[request][key] = function(session, err)
      session.on_close[key] = nil
      finish(err and { error = common.error_text(err) } or 'started')
    end
  end
  local timer = uv.new_timer()
  timer:start(TICK_MS, TICK_MS, vim.schedule_wrap(function()
    local elapsed = uv.now() - started_at
    if not initialized and elapsed >= START_PROMPT_MS then
      finish('stalled')
    elseif initialized and elapsed >= LAUNCH_MS then
      finish('slow') -- Up and initialized; the launch itself takes long (e.g. a big build).
    end
  end))

  -- In a coroutine of its own: nvim-dap may yield inside run() (e.g. a
  -- process picker), and its errors must not skip the cleanup below.
  coroutine.wrap(function()
    local ok, err = pcall(dap.run, config, { new = true })
    if not ok then finish({ error = tostring(err) }) end
  end)()
  if not outcome then coroutine.yield() end

  dap.listeners.after.initialize[key] = nil
  dap.listeners.after.launch[key] = nil
  dap.listeners.after.attach[key] = nil
  timer:stop()
  timer:close()

  if outcome == 'stalled' then
    error(('the debugger has not started after %ds. Check the adapter (:DapShowLog) and any prompt '
      .. 'Neovim is showing; if it starts later, call debug_wait_for_stop rather than starting again.'):format(START_PROMPT_MS / 1000), 0)
  elseif type(outcome) == 'table' then
    error(('the debug adapter refused to %s: %s'):format(config.request, outcome.error), 0)
  end
  return outcome
end

function M.start(args)
  local config
  if type(args.configName) == 'string' and args.configName ~= '' then
    config = find_config(args.configName, args.folder)
  else
    if not args.type then error('"type" is required (e.g. php, go, python, node), or pass "configName"', 0) end
    if not args.request then error('"request" is required (launch or attach)', 0) end
    config = vim.deepcopy(args)
    config.name = config.name or ('Debug (' .. config.type .. ')')
  end
  check_adapter(config)
  local outcome = run_watched(config)
  local note = outcome == 'slow' and ' (the adapter is still launching; call debug_wait_for_stop)' or ''
  return ('Debug session "%s" started%s%s'):format(config.name,
    args.configName and ' from its launch configuration' or (' (type: ' .. config.type .. ', request: ' .. config.request .. ')'), note)
end

function M.stop()
  local session = common.find_session()
  if not session then return 'No active debug session' end
  local root = common.root_session(session)
  local dap = common.dap()
  dap.set_session(root)
  local done, wait = common.counter(1)
  dap.terminate(nil, nil, done)
  wait(5000)
  return 'Debug session "' .. root.config.name .. '" stopped'
end

function M.restart()
  local session = common.find_session()
  if not session then error('no active debug session', 0) end
  local root = common.root_session(session)
  if root.capabilities.supportsRestartRequest then
    common.request(root, 'restart', {})
    return 'Debug session "' .. root.config.name .. '" restarted'
  end
  local dap = common.dap()
  dap.set_session(root)
  dap.restart()
  return 'Debug session "' .. root.config.name .. '" restarted (terminated and started again)'
end

return M
