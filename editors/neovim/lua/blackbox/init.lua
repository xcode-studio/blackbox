-- Blackbox for Neovim: lets AI tools (Claude Code, Cursor, ...) drive this
-- Neovim's debugger through the Blackbox MCP server.
--
--   require('blackbox').setup()

local M = {}

M.version = '0.1.0'

local uv = vim.uv or vim.loop

---@class blackbox.State
---@field server any
---@field focused boolean
M.state = { server = nil, focused = true }

-- Folders this window works in: the global cwd and every tab-local cwd.
function M.folders()
  local seen, folders = {}, {}
  local function add(dir)
    local real = dir and dir ~= '' and (uv.fs_realpath(dir) or dir)
    if real and not seen[real] then
      seen[real] = true
      table.insert(folders, real)
    end
  end
  add(vim.fn.getcwd(-1, -1))
  for tab = 1, vim.fn.tabpagenr('$') do
    add(vim.fn.getcwd(-1, tab))
  end
  return folders
end

local function app_info()
  local v = vim.version()
  return { name = 'Neovim', scheme = 'nvim', version = ('%d.%d.%d'):format(v.major, v.minor, v.patch) }
end

function M.start()
  if M.state.server then return M.state.server end
  local Server = require('blackbox.server')
  local server = Server.new({
    folders = M.folders,
    app = app_info(),
    extension_version = M.version,
    handlers = require('blackbox.tools.catalog').handlers(),
  })
  server:start()
  M.state.server = server

  -- AI tools started in :terminal inherit it and use this window.
  vim.env.BLACKBOX_WINDOW = server.entry.id

  local group = vim.api.nvim_create_augroup('blackbox', { clear = true })
  vim.api.nvim_create_autocmd({ 'DirChanged', 'TabNew', 'TabClosed' }, {
    group = group, callback = function() server:set_folders(M.folders()) end,
  })
  vim.api.nvim_create_autocmd('FocusGained', {
    group = group, callback = function() M.state.focused = true; server:mark_focused() end,
  })
  vim.api.nvim_create_autocmd('FocusLost', {
    group = group, callback = function() M.state.focused = false end,
  })
  vim.api.nvim_create_autocmd('VimLeavePre', {
    group = group, callback = function() M.stop() end,
  })
  return server
end

function M.stop()
  if M.state.server then
    M.state.server:dispose()
    M.state.server = nil
  end
end

---@param opts table? reserved for future options
function M.setup(opts)
  M.opts = opts or {}
  local ok, dap = pcall(require, 'dap')
  if ok then
    require('blackbox.events').attach(dap)
    require('blackbox.tools.breakpoints').attach(dap)
  else
    vim.notify('blackbox: nvim-dap is not installed; debug tools will report that', vim.log.levels.WARN)
  end
  local started, err = pcall(M.start)
  if not started then
    vim.notify('blackbox: could not start the MCP bridge (' .. tostring(err) .. ')', vim.log.levels.ERROR)
  end
end

return M
