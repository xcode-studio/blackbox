-- The bridge server over a real socket: line framing, errors, registry, cleanup.
local Server = require('blackbox.server')
local protocol = require('blackbox.protocol')
local uv = vim.uv or vim.loop

local function request(socket, lines)
  local client = uv.new_pipe(false)
  local received, connected = '', false
  client:connect(socket, function(err)
    assert(not err, err)
    connected = true
    client:read_start(function(_, chunk) if chunk then received = received .. chunk end end)
    -- Split across writes, to exercise the framing.
    local text = table.concat(lines, '\n') .. '\n'
    client:write(text:sub(1, 7))
    client:write(text:sub(8))
  end)
  vim.wait(2000, function() local _, n = received:gsub('\n', ''); return connected and n >= #lines end, 5)
  client:close()
  return vim.tbl_map(vim.json.decode, vim.split(vim.trim(received), '\n'))
end

describe('server', function()
  local dir, server
  before_each(function()
    dir = vim.fn.tempname()
    vim.fn.mkdir(dir, 'p')
    vim.env.BLACKBOX_IPC_DIR = dir
    server = Server.new({
      id = 'T',
      folders = function() return { '/proj' } end,
      app = { name = 'Neovim', scheme = 'nvim', version = '0.10.4' },
      extension_version = '0.1.0',
      handlers = {
        echo = function(args) return 'echo:' .. args.text end,
        boom = function() error('kaboom') end,
        nil_check = function(args) return args.threadId == nil and 'nil' or type(args.threadId) end,
      },
    })
    server:start()
  end)
  after_each(function()
    server:dispose()
    vim.env.BLACKBOX_IPC_DIR = nil
  end)

  it('answers requests split across packets, in order, with errors as errors', function()
    local replies = request(server.entry.socket, {
      vim.json.encode({ id = '1', tool = 'echo', args = { text = 'hi' } }),
      vim.json.encode({ id = '2', tool = 'boom', args = {} }),
      vim.json.encode({ id = '3', tool = 'nope', args = {} }),
    })
    table.sort(replies, function(a, b) return a.id < b.id end)
    assert.are.same({ id = '1', result = 'echo:hi' }, replies[1])
    assert.are.same({ id = '2', error = 'kaboom' }, replies[2])
    assert.are.same({ id = '3', error = 'unknown tool: nope' }, replies[3])
  end)

  it('turns JSON null arguments into nil, not vim.NIL', function()
    local reply = request(server.entry.socket, { '{"id":"5","tool":"nil_check","args":{"threadId":null}}' })
    assert.equals('nil', reply[1].result)
  end)

  it('writes a registry entry in the shared format and removes it on dispose', function()
    local entry = vim.json.decode(table.concat(vim.fn.readfile(protocol.registry_path('T')), ''))
    assert.equals('T', entry.id)
    assert.equals(uv.os_getpid(), entry.pid)
    assert.equals(entry.pid, entry.appPid)
    assert.equals(protocol.PROTOCOL_VERSION, entry.protocol)
    assert.are.same({ name = 'Neovim', scheme = 'nvim', version = '0.10.4' }, entry.app)
    assert.are.same({ '/proj' }, entry.folders)
    server:dispose()
    assert.is_nil(uv.fs_stat(protocol.registry_path('T')))
    assert.is_nil(uv.fs_stat(server.entry.socket))
  end)

  it('recreates its socket if the file is deleted', function()
    if protocol.is_windows then return end
    uv.fs_unlink(server.entry.socket)
    server:check_health()
    assert.is_truthy(uv.fs_stat(server.entry.socket))
    local reply = request(server.entry.socket, { vim.json.encode({ id = '9', tool = 'echo', args = { text = 'back' } }) })
    assert.equals('echo:back', reply[1].result)
  end)
end)
