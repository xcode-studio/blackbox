-- :BlackboxStatus and :BlackboxCopyMcpConfig.

local uv = vim.uv or vim.loop

local M = {}

local function launcher_path()
  local home = os.getenv('BLACKBOX_HOME')
  if not home or home == '' then home = vim.fs.joinpath(uv.os_homedir(), '.blackbox') end
  return vim.fs.joinpath(home, 'blackbox-mcp.js')
end

-- Absolute path of node through the login shell: apps started from the Dock
-- often lack nvm & co. on their PATH (same as findNode in the VS Code side).
local function find_node()
  local cmd = vim.fn.has('win32') == 1 and { 'where', 'node' } or { os.getenv('SHELL') or '/bin/sh', '-lc', 'command -v node' }
  local ok, result = pcall(vim.fn.systemlist, cmd)
  if ok and vim.v.shell_error == 0 then
    for _, line in ipairs(result) do
      if vim.fn.isabsolutepath ~= nil and vim.fn.isabsolutepath(line) == 1 then return line end
      if line:sub(1, 1) == '/' then return line end
    end
  end
  return 'node'
end

function M.status()
  local blackbox = require('blackbox')
  local server = blackbox.state.server
  local lines = {}
  if not server then
    table.insert(lines, 'Bridge: not running (call require("blackbox").setup())')
  else
    table.insert(lines, 'Bridge: listening on ' .. server.entry.socket)
    table.insert(lines, 'Window id: ' .. server.entry.id .. '  folders: ' .. table.concat(server.entry.folders, ', '))
  end
  local ok, dap = pcall(require, 'dap')
  if ok then
    local count = vim.tbl_count(dap.sessions())
    table.insert(lines, ('Debug sessions: %d%s'):format(count, dap.session() and (' (focused: ' .. dap.session().config.name .. ')') or ''))
  else
    table.insert(lines, 'nvim-dap: not installed')
  end
  local launcher = launcher_path()
  table.insert(lines, uv.fs_stat(launcher) and ('MCP launcher: ' .. launcher)
    or 'MCP launcher: not installed yet (install Blackbox in VS Code once, or run the MCP server directly)')
  vim.notify(table.concat(lines, '\n'), vim.log.levels.INFO, { title = 'Blackbox' })
end

function M.copy_mcp_config()
  local config = { blackbox = { command = find_node(), args = { launcher_path() } } }
  local text = vim.json.encode(config)
  vim.fn.setreg('+', text)
  vim.fn.setreg('"', text)
  vim.notify('Blackbox MCP server configuration copied (add it under "mcpServers"):\n' .. text, vim.log.levels.INFO)
end

return M
