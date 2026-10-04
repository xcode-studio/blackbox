-- Test init: this plugin plus plenary and nvim-dap.
--   PLENARY_PATH / NVIM_DAP_PATH override where they are checked out.
local lazy = vim.fn.expand('~/.local/share/nvim/lazy')
local root = vim.fn.fnamemodify(debug.getinfo(1, 'S').source:sub(2), ':p:h:h')
vim.opt.rtp:prepend(root)
vim.opt.rtp:prepend(os.getenv('PLENARY_PATH') or (lazy .. '/plenary.nvim'))
vim.opt.rtp:prepend(os.getenv('NVIM_DAP_PATH') or (lazy .. '/nvim-dap'))
vim.cmd('runtime plugin/plenary.vim')
-- What a plugin manager does when it loads nvim-dap (defines its signs).
vim.cmd('runtime plugin/dap.lua')
