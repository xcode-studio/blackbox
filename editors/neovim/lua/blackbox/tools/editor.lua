-- Editor and workspace tools, plus window_status (mirrors
-- editors/vscode/src/tools/impl/editor.ts, workspace.ts and window.ts).

local M = {}

local json = require('blackbox.tools.common').json

local function folders()
  return require('blackbox').folders()
end

function M.open_file(args)
  local file, line = args.file, args.line
  if not file or file == '' then error('file path is required', 0) end
  if vim.fn.filereadable(file) == 0 then error('Error opening file: no such file ' .. file, 0) end
  vim.cmd.edit(vim.fn.fnameescape(file))
  if line and line > 0 then
    local last = vim.api.nvim_buf_line_count(0)
    vim.api.nvim_win_set_cursor(0, { math.min(line, last), 0 })
  end
  return 'Opened ' .. file .. (line and (':' .. line) or '')
end

function M.get_open_files()
  local current = vim.api.nvim_get_current_buf()
  local files = {}
  for _, buf in ipairs(vim.api.nvim_list_bufs()) do
    local name = vim.api.nvim_buf_get_name(buf)
    if vim.bo[buf].buflisted and vim.bo[buf].buftype == '' and name ~= '' then
      table.insert(files, { file = name, active = buf == current, dirty = vim.bo[buf].modified })
    end
  end
  return json(files)
end

-- Turns a glob such as "**/*.php" into a Lua pattern on the relative path.
local function glob_to_pattern(glob)
  local pattern = glob:gsub('[%^%$%(%)%%%.%[%]%+%-]', '%%%0')
  pattern = pattern:gsub('%*%*/', '\1'):gsub('%*%*', '\2'):gsub('%*', '[^/]*'):gsub('%?', '[^/]')
  pattern = pattern:gsub('\1', '.-'):gsub('\2', '.*')
  return '^' .. pattern .. '$'
end

function M.find_file(args)
  local glob, limit = args.pattern, args.maxResults or 20
  if not glob or glob == '' then error('glob pattern is required', 0) end
  local pattern = glob_to_pattern(glob)
  local found = {}
  for _, root in ipairs(folders()) do
    for name, kind in vim.fs.dir(root, {
      depth = 25,
      -- dir is relative to root (e.g. packages/a/node_modules): skip at any depth.
      skip = function(dir)
        local name = vim.fs.basename(dir)
        return name ~= 'node_modules' and name ~= '.git'
      end,
    }) do
      if kind == 'file' and (name:match(pattern) or ('/' .. name):match(pattern)) then
        table.insert(found, vim.fs.joinpath(root, name))
        if #found >= limit then return json(found) end
      end
    end
  end
  return json(found)
end

local SEVERITY = { [vim.diagnostic.severity.ERROR] = 'error', [vim.diagnostic.severity.WARN] = 'warning' }

function M.get_diagnostics(args)
  local max = args.severity == 'error' and vim.diagnostic.severity.ERROR
    or args.severity == 'warning' and vim.diagnostic.severity.WARN or nil
  local bufnr = args.file and require('blackbox.tools.common').buffer_for(args.file) or nil
  if args.file and bufnr == -1 then return 'No diagnostics found' end
  local by_file, order = {}, {}
  for _, d in ipairs(vim.diagnostic.get(bufnr)) do
    if not max or d.severity <= max then
      local file = vim.api.nvim_buf_get_name(d.bufnr)
      if not by_file[file] then
        by_file[file] = {}
        table.insert(order, file)
      end
      table.insert(by_file[file], {
        line = d.lnum + 1, severity = SEVERITY[d.severity] or 'info', message = d.message, source = d.source,
      })
    end
  end
  if #order == 0 then return 'No diagnostics found' end
  local result = {}
  for _, file in ipairs(order) do
    table.insert(result, { file = file, issues = by_file[file] })
  end
  return json(result)
end

function M.window_status()
  local state = require('blackbox').state
  local ok, dap = pcall(require, 'dap')
  local breakpoints, debug = 0, vim.NIL
  if ok then
    for _, bps in pairs(require('dap.breakpoints').get()) do
      breakpoints = breakpoints + #bps
    end
    breakpoints = breakpoints + require('blackbox.tools.breakpoints').extra_count()
    local session = dap.session()
    if session then
      local stop = require('blackbox.events').current_stop()
      debug = {
        name = session.config.name or 'Debug',
        type = session.config.type or '',
        state = stop and 'stopped' or 'running',
        stoppedAt = stop and stop.location or nil,
      }
    end
  end
  return json({ folders = folders(), focused = state.focused, breakpoints = breakpoints, debug = debug })
end

return M
