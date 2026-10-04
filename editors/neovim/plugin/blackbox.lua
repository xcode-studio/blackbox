-- Commands only; nothing starts until require('blackbox').setup().
if vim.g.loaded_blackbox then return end
vim.g.loaded_blackbox = true

vim.api.nvim_create_user_command('BlackboxStatus', function()
  require('blackbox.commands').status()
end, { desc = 'Blackbox: show the MCP bridge status' })

vim.api.nvim_create_user_command('BlackboxCopyMcpConfig', function()
  require('blackbox.commands').copy_mcp_config()
end, { desc = 'Blackbox: copy an MCP server configuration entry' })
