-- The Blackbox window protocol, shared with the VS Code extension
-- (editors/vscode/src/ipc/protocol.ts). Each editor window listens on its own
-- socket in a private per-user folder and describes itself in a registry
-- file next to it; the MCP server reads the registry to find windows.
--
--   <dir>/<id>.sock   the socket (a named pipe on Windows)
--   <dir>/<id>.json   the registry entry

local uv = vim.uv or vim.loop

local M = {}

-- Bumped when the registry entry or internal tools change shape.
M.PROTOCOL_VERSION = 2
-- Internal tool the MCP server uses to describe windows.
M.STATUS_TOOL = 'window_status'

local is_windows = vim.fn.has('win32') == 1
M.is_windows = is_windows

-- Same location rule as ipcDir() in protocol.ts: a fixed per-user folder,
-- because editors and MCP servers often see different TMPDIRs.
function M.ipc_dir()
  local override = os.getenv('BLACKBOX_IPC_DIR')
  if override and override ~= '' then
    return override
  end
  if is_windows then
    return vim.fs.joinpath(os.getenv('TEMP') or uv.os_tmpdir(), 'blackbox')
  end
  return '/tmp/blackbox-' .. uv.getuid()
end

-- Windows named pipes share one namespace across users, so the name gets an
-- unguessable part that only the private registry file reveals.
function M.socket_path(id)
  if is_windows then
    local random = vim.fn.sha256(tostring(uv.hrtime()) .. tostring(math.random())):sub(1, 24)
    return ('\\\\.\\pipe\\blackbox-%s-%s'):format(id, random)
  end
  return vim.fs.joinpath(M.ipc_dir(), id .. '.sock')
end

function M.registry_path(id)
  return vim.fs.joinpath(M.ipc_dir(), id .. '.json')
end

local OTHERS_MASK = tonumber('077', 8)

-- Error message unless dir is a real directory owned by this user and closed
-- to everyone else (mirrors assertPrivateDir). nil when it is fine.
function M.private_dir_problem(dir)
  if is_windows then
    return nil -- %TEMP% is already per-user.
  end
  local stat = uv.fs_lstat(dir)
  if not stat or stat.type ~= 'directory' then
    return dir .. ' is not a directory'
  end
  if stat.uid ~= uv.getuid() then
    return dir .. ' is owned by another user'
  end
  if bit.band(stat.mode, OTHERS_MASK) ~= 0 then
    return ('%s is accessible to other users (mode %o)'):format(dir, bit.band(stat.mode, tonumber('777', 8)))
  end
  return nil
end

-- Creates the folder, or tightens one this user already owns; errors if
-- another user could still reach it (mirrors ensurePrivateDir).
function M.ensure_private_dir(dir)
  if is_windows then
    vim.fn.mkdir(dir, 'p')
    return
  end
  local private = tonumber('700', 8)
  uv.fs_mkdir(dir, private)
  local stat = uv.fs_lstat(dir)
  if stat and stat.type == 'directory' and stat.uid == uv.getuid() and bit.band(stat.mode, OTHERS_MASK) ~= 0 then
    uv.fs_chmod(dir, private)
  end
  local problem = M.private_dir_problem(dir)
  if problem then
    error(problem, 0)
  end
end

-- Writes then renames, so readers never see a half-written file.
function M.write_atomic(path, content)
  local tmp = ('%s.%d.tmp'):format(path, uv.os_getpid())
  local fd = assert(uv.fs_open(tmp, 'w', tonumber('600', 8)))
  assert(uv.fs_write(fd, content))
  assert(uv.fs_close(fd))
  assert(uv.fs_rename(tmp, path))
end

return M
