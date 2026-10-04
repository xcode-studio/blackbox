local protocol = require('blackbox.protocol')
local uv = vim.uv or vim.loop

local function tmpdir()
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, 'p')
  return uv.fs_realpath(dir)
end

describe('protocol', function()
  it('uses /tmp/blackbox-<uid>, or BLACKBOX_IPC_DIR', function()
    if protocol.is_windows then return end
    local saved = os.getenv('BLACKBOX_IPC_DIR')
    vim.env.BLACKBOX_IPC_DIR = nil
    assert.equals('/tmp/blackbox-' .. uv.getuid(), protocol.ipc_dir())
    vim.env.BLACKBOX_IPC_DIR = '/elsewhere'
    assert.equals('/elsewhere', protocol.ipc_dir())
    vim.env.BLACKBOX_IPC_DIR = saved
  end)

  it('tightens its own folder and refuses symlinks', function()
    if protocol.is_windows then return end
    local dir = tmpdir()
    uv.fs_chmod(dir, tonumber('755', 8))
    protocol.ensure_private_dir(dir)
    assert.equals(tonumber('700', 8), bit.band(uv.fs_stat(dir).mode, tonumber('777', 8)))
    local link = dir .. '-link'
    uv.fs_symlink(dir, link)
    assert.is_truthy(protocol.private_dir_problem(link):find('not a directory'))
    assert.has_error(function() protocol.ensure_private_dir(link) end)
    uv.fs_unlink(link)
  end)

  it('flags a folder other users can reach', function()
    if protocol.is_windows then return end
    local dir = tmpdir()
    uv.fs_chmod(dir, tonumber('777', 8))
    assert.is_truthy(protocol.private_dir_problem(dir):find('accessible to other users'))
  end)

  it('writes files atomically', function()
    local file = tmpdir() .. '/entry.json'
    protocol.write_atomic(file, '{"a":1}')
    assert.are.same({ '{"a":1}' }, vim.fn.readfile(file))
  end)
end)
