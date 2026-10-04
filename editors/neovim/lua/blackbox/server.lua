-- This Neovim instance's side of the Blackbox bridge (mirrors IPCServer in
-- editors/vscode/src/ipc/server.ts): a socket the MCP server connects to,
-- plus a registry entry describing this window. Requests are newline-
-- delimited JSON: {id, tool, args} -> {id, result} | {id, error}.

local protocol = require('blackbox.protocol')

local uv = vim.uv or vim.loop

local HEALTH_CHECK_MS = 5000

-- Milliseconds since the epoch, like Date.now() on the VS Code side.
local function now_ms()
  local sec, usec = uv.gettimeofday()
  return sec * 1000 + math.floor(usec / 1000)
end

local Server = {}
Server.__index = Server

---@class blackbox.ServerOptions
---@field id string?            window id (default: this process's pid)
---@field folders fun(): string[]
---@field app { name: string, scheme: string, version: string }
---@field extension_version string
---@field handlers table<string, fun(args: table): string>  run inside a coroutine
---@field health_check_ms integer?

---@param opts blackbox.ServerOptions
function Server.new(opts)
  local id = opts.id or tostring(uv.os_getpid())
  local now = now_ms()
  local self = setmetatable({
    handlers = opts.handlers,
    folders = opts.folders,
    health_check_ms = opts.health_check_ms or HEALTH_CHECK_MS,
    connections = {},
    disposed = false,
    entry = {
      id = id,
      pid = uv.os_getpid(),
      socket = protocol.socket_path(id),
      folders = opts.folders(),
      startedAt = now,
      focusedAt = now,
      app = opts.app,
      -- Neovim is a single process: it is both the window and the editor.
      appPid = uv.os_getpid(),
      extensionVersion = opts.extension_version,
      protocol = protocol.PROTOCOL_VERSION,
    },
  }, Server)
  return self
end

function Server:start()
  protocol.ensure_private_dir(protocol.ipc_dir())
  self:listen()
  self:write_registry()
  self.health_timer = uv.new_timer()
  self.health_timer:start(self.health_check_ms, self.health_check_ms, vim.schedule_wrap(function()
    self:check_health()
  end))
end

function Server:listen()
  if not protocol.is_windows then
    uv.fs_unlink(self.entry.socket) -- Leftover of a previous process with this pid.
  end
  local pipe = assert(uv.new_pipe(false))
  assert(pipe:bind(self.entry.socket))
  assert(pipe:listen(64, function(err)
    if err then return end
    local client = uv.new_pipe(false)
    if pipe:accept(client) then
      self:handle_connection(client)
    else
      client:close()
    end
  end))
  self.pipe = pipe
  self.socket_ino = not protocol.is_windows and uv.fs_stat(self.entry.socket).ino or nil
end

function Server:handle_connection(client)
  self.connections[client] = true
  local buffer = ''
  client:read_start(function(err, chunk)
    if err or not chunk then
      self.connections[client] = nil
      if not client:is_closing() then client:close() end
      return
    end
    buffer = buffer .. chunk
    while true do
      local nl = buffer:find('\n', 1, true)
      if not nl then break end
      local line = buffer:sub(1, nl - 1)
      buffer = buffer:sub(nl + 1)
      -- Editor APIs can't be used from libuv callbacks: answer on the main loop.
      vim.schedule(function() self:handle_message(line, client) end)
    end
  end)
end

local function reply(client, response)
  if client:is_closing() then return end -- The client hung up (e.g. timed out).
  client:write(vim.json.encode(response) .. '\n')
end

function Server:handle_message(line, client)
  -- luanil: JSON null becomes nil, not vim.NIL (which is truthy).
  local ok, request = pcall(vim.json.decode, line, { luanil = { object = true, array = true } })
  if not ok or type(request) ~= 'table' then return end

  local handler = self.handlers[request.tool]
  if not handler then
    return reply(client, { id = request.id, error = 'unknown tool: ' .. tostring(request.tool) })
  end
  -- A coroutine, so handlers can wait for debug adapter responses.
  coroutine.wrap(function()
    local args = type(request.args) == 'table' and request.args or {}
    local done, result = xpcall(handler, function(e)
      -- Drop "file.lua:12: " so the AI sees just the message.
      return type(e) == 'string' and (e:gsub('^[^:]*:%d+: ', '')) or tostring(e)
    end, args)
    if done then
      reply(client, { id = request.id, result = result or '' })
    else
      reply(client, { id = request.id, error = result })
    end
  end)()
end

function Server:write_registry()
  if self.disposed then return end
  local ok, err = pcall(protocol.write_atomic, protocol.registry_path(self.entry.id), vim.json.encode(self.entry))
  if not ok then
    vim.notify('blackbox: could not write the registry entry: ' .. tostring(err), vim.log.levels.WARN)
  end
end

function Server:set_folders(folders)
  self.entry.folders = folders
  self:write_registry()
end

function Server:mark_focused()
  self.entry.focusedAt = now_ms()
  self:write_registry()
end

function Server:owns_socket()
  local stat = uv.fs_stat(self.entry.socket)
  return stat ~= nil and stat.ino == self.socket_ino
end

-- Recreates the socket or registry file if something removed them.
function Server:check_health()
  if self.disposed then return end
  if not uv.fs_stat(protocol.registry_path(self.entry.id)) then
    self:write_registry()
  end
  if protocol.is_windows or self:owns_socket() then return end
  self:close_pipe()
  local ok, err = pcall(self.listen, self)
  if not ok then
    vim.notify('blackbox: could not restart the bridge: ' .. tostring(err), vim.log.levels.WARN)
  end
end

-- Stops listening and drops open connections, so the address is free at once.
function Server:close_pipe()
  if self.pipe and not self.pipe:is_closing() then self.pipe:close() end
  self.pipe = nil
  for client in pairs(self.connections) do
    if not client:is_closing() then client:close() end
  end
  self.connections = {}
end

function Server:dispose()
  if self.disposed then return end
  self.disposed = true
  if self.health_timer then
    self.health_timer:stop()
    self.health_timer:close()
  end
  self:close_pipe()
  if not protocol.is_windows and self:owns_socket() then
    uv.fs_unlink(self.entry.socket)
  end
  uv.fs_unlink(protocol.registry_path(self.entry.id))
end

return Server
