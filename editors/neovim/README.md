# Blackbox for Neovim

> **Status:** working, not yet published. Install it from this repository (below).

Lets AI tools (Claude Code, Cursor, any MCP client) drive Neovim's debugger: set breakpoints, start sessions by launch configuration name, wait for pauses, step, inspect and change variables, read logpoint output. It uses [nvim-dap](https://github.com/mfussenegger/nvim-dap), so it works with any debug adapter you've set up there (PHP/Xdebug, Go/delve, Python/debugpy, Node, …).

It speaks the same protocol as the [VS Code extension](../vscode/), so one Blackbox MCP server sees Neovim next to VS Code, Cursor and Antigravity windows.

## Requirements
- Neovim 0.10 or later
- [nvim-dap](https://github.com/mfussenegger/nvim-dap), with an adapter for your language
- The Blackbox MCP server. Today it comes with the VS Code extension: install Blackbox in VS Code (or Cursor/Antigravity) once and it keeps `~/.blackbox/blackbox-mcp.js` up to date.

## Install (lazy.nvim)
```lua
{
  dir = "~/GitHub/blackbox/editors/neovim", -- this folder
  dependencies = { "mfussenegger/nvim-dap" },
  opts = {},  -- calls require("blackbox").setup()
}
```
Without a plugin manager: add this folder to `runtimepath` and call `require("blackbox").setup()`.

## Connect your AI tool
Run `:BlackboxCopyMcpConfig` and add the copied entry under `mcpServers` in your MCP client's config (for Claude Code: `~/.claude.json`), then reconnect it. The entry is:
```json
{ "blackbox": { "command": "/path/to/node", "args": ["/Users/<you>/.blackbox/blackbox-mcp.js"] } }
```

## How a session finds this Neovim
- **`claude` (or another AI tool) started in Neovim's `:terminal`:** it uses this Neovim automatically. Neovim is a parent process, and `BLACKBOX_WINDOW` is set there.
- **Elsewhere:** the window whose working directory contains the AI tool's folder. If several windows match, the AI calls `ide_list_windows` and `ide_select_window`, where this window shows as `"app": "Neovim"`.

## Commands
| Command | |
|---|---|
| `:BlackboxStatus` | Socket, folders, debug sessions, and whether the MCP launcher is installed |
| `:BlackboxCopyMcpConfig` | Copy an MCP server entry (absolute `node` path + launcher) |

## Launch configurations
`debug_start {configName}` looks in each folder's `.vscode/launch.json` and in `dap.configurations`. The configuration's `type` must have an adapter in `dap.adapters`. For PHP, for example, install `php-debug-adapter` with `:MasonInstall php-debug-adapter` and point `dap.adapters.php` at it.

## How it differs from VS Code
- **Disabled and function breakpoints:** nvim-dap has neither, so Blackbox keeps them itself. Disabled breakpoints aren't sent to the adapter. Function breakpoints are sent to running sessions and to each new one.
- **Program output:** depends on the adapter. Delve, for example, prints the program's stdout to its terminal, not as debug output, while logpoints always arrive.
- **Exception filters** are whatever the adapter offers, so the list may be empty.

## Development
- **Lua unit tests:**
  ```sh
  nvim --headless --noplugin -u tests/minimal_init.lua -c "PlenaryBustedDirectory tests/unit { minimal_init = 'tests/minimal_init.lua', sequential = true }"
  ```
- **Conformance:** the same checks as the VS Code extension, through the real MCP server, against a Go fixture with delve:
  ```sh
  (cd ../vscode && npm ci && npm run compile) && (cd ../conformance && npm ci && npm run test:nvim)
  ```
  Set `NVIM_DAP_PATH` / `PLENARY_PATH` if they aren't in lazy.nvim's folder.
