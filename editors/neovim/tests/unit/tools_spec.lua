-- Breakpoint stores and helpers that don't need a running debug adapter.
local breakpoints = require('blackbox.tools.breakpoints')
local common = require('blackbox.tools.common')

local function run(fn)
  local result, err, finished
  coroutine.wrap(function()
    local ok, r = pcall(fn)
    if ok then result = r else err = r end
    finished = true
  end)()
  vim.wait(2000, function() return finished end, 5)
  if err then error(err, 0) end
  return result
end

describe('breakpoint tools (no session)', function()
  local file
  before_each(function()
    run(breakpoints.remove_all)
    file = vim.fn.tempname() .. '.go'
    vim.fn.writefile({ 'package main', 'func main() {', '  x := 1', '  _ = x', '}' }, file)
  end)

  it('sets, lists, disables, enables and removes source and function breakpoints', function()
    assert.is_truthy(run(function() return breakpoints.set({ file = file, line = 3, hitCondition = '2' }) end):find('ok: '))
    run(function() return breakpoints.set_function({ name = 'main.main' }) end)
    local list = vim.json.decode(run(breakpoints.list))
    assert.equals(2, #list)
    assert.are.same({ 'source', 3, true, '2' }, { list[1].type, list[1].line, list[1].enabled, list[1].hitCondition })
    assert.are.same({ 'function', 'main.main', true }, { list[2].type, list[2].name, list[2].enabled })

    assert.equals('Disabled 2 breakpoint(s)', run(function() return breakpoints.toggle({ enabled = false }) end))
    list = vim.json.decode(run(breakpoints.list))
    assert.is_false(list[1].enabled)
    assert.equals('2', list[1].hitCondition, 'keeps the hit condition while disabled')
    assert.equals(2, breakpoints.extra_count())

    assert.equals('Enabled 1 breakpoint(s)', run(function() return breakpoints.toggle({ enabled = true, breakpoints = { { file = file, line = 3 } } }) end))
    assert.is_true(vim.json.decode(run(breakpoints.list))[1].enabled)

    local removed = run(function() return breakpoints.remove({ file = file, line = 3, functions = { 'main.main', 'nope' } }) end)
    assert.is_truthy(removed:find('ok: removed ' .. vim.pesc(file) .. ':3'))
    assert.is_truthy(removed:find('ok: removed function main.main'))
    assert.is_truthy(removed:find('skip: no function breakpoint on nope'))
    assert.equals('[]', run(breakpoints.list))
  end)

  it('rejects invalid input with plain messages', function()
    assert.is_truthy(run(function() return breakpoints.set({ file = file }) end):find('skip: invalid'))
    assert.has_error(function() run(function() return breakpoints.toggle({}) end) end, '"enabled" (true or false) is required')
    assert.has_error(function() run(function() return breakpoints.set_exception_filters({}) end) end, 'no active debug session')
  end)
end)

describe('helpers', function()
  it('json does not escape slashes and round-trips', function()
    local text = common.json({ p = '/a/b', b = 'x\\/y' })
    assert.equals('{"p":"/a/b","b":"x\\\\/y"}', text:gsub('"b":"x\\\\/y","p":"/a/b"', '"p":"/a/b","b":"x\\\\/y"'))
    assert.are.same({ p = '/a/b', b = 'x\\/y' }, vim.json.decode(text))
  end)

  it('same_file sees through symlinks', function()
    local dir = vim.uv.fs_realpath(vim.fn.tempname():match('(.*)/')) .. '/bbx-link-' .. vim.uv.hrtime()
    vim.fn.mkdir(dir .. '/src', 'p')
    vim.fn.writefile({}, dir .. '/src/index.php')
    vim.uv.fs_symlink(dir .. '/src', dir .. '/wp')
    assert.is_true(common.same_file(dir .. '/wp/index.php', dir .. '/src/index.php'))
    assert.is_false(common.same_file(dir .. '/wp/index.php', dir .. '/src/other.php'))
    vim.fn.delete(dir, 'rf')
  end)
end)

describe('error_text', function()
  it('adds the adapter detail with its variables filled in', function()
    local err = { message = 'Failed to launch', body = { error = { format = 'Version of {tool} is too old', variables = { tool = 'Delve' } } } }
    assert.equals('Failed to launch: Version of Delve is too old', common.error_text(err))
  end)
  it('falls back to the message or the format alone', function()
    assert.equals('boom', common.error_text({ message = 'boom' }))
    assert.equals('only detail', common.error_text({ body = { error = { format = 'only detail' } } }))
    assert.equals('same', common.error_text({ message = 'same', body = { error = { format = 'same' } } }))
    assert.equals('Failed to launch: too old', common.error_text({ message = 'Failed to launch', body = { error = { format = 'Failed to launch: too old' } } }))
  end)
end)

describe('requests', function()
  -- A session whose adapter never answers, like one that is closing.
  local function silent_session() return { id = 99, request = function() end } end

  it('fail at once when the session closes instead of waiting forever', function()
    local session = silent_session()
    local finished, err
    coroutine.wrap(function()
      local ok, e = pcall(common.request, session, 'evaluate', { expression = 'x' })
      finished, err = true, (not ok) and e or nil
    end)()
    assert.is_nil(finished)
    common.session_closed(session)
    vim.wait(1000, function() return finished end, 5)
    assert.equals('the debug session ended', err)
  end)

  it('refuse a session that already closed', function()
    local session = silent_session()
    session.closed = true
    assert.has_error(function() run(function() return common.request(session, 'threads') end) end, 'the debug session has ended')
  end)
end)

describe('buffer_for', function()
  it('matches the exact file only, not a partial name', function()
    local root = vim.uv.fs_realpath(vim.fn.tempname():match('(.*)/'))
    local inner, outer = root .. '/bbx-y-' .. vim.uv.hrtime() .. '/x/app.go', root .. '/bbx-x-' .. vim.uv.hrtime() .. '/app.go'
    vim.fn.mkdir(vim.fs.dirname(inner), 'p')
    vim.fn.writefile({}, inner)
    local bufnr = vim.fn.bufadd(inner)
    assert.equals(bufnr, common.buffer_for(inner))
    assert.equals(-1, common.buffer_for(outer), 'a file that only shares the tail is not this buffer')
  end)
end)

describe('launch configurations', function()
  it('fill ${workspaceFolder} with the folder they came from', function()
    local config = require('blackbox.tools.session')._with_folder(
      { program = '${workspaceFolder}/app.go', name = 'x (${workspaceFolderBasename})', args = { '${workspaceFolder}' } }, '/sites/manheim.wpx')
    assert.are.same({ program = '/sites/manheim.wpx/app.go', name = 'x (manheim.wpx)', args = { '/sites/manheim.wpx' } }, config)
  end)
end)

describe('find_file', function()
  it('skips node_modules and .git at any depth', function()
    local root = vim.uv.fs_realpath(vim.fn.tempname():match('(.*)/')) .. '/bbx-find-' .. vim.uv.hrtime()
    for _, dir in ipairs({ '/src', '/packages/a/node_modules/dep', '/packages/a/.git' }) do vim.fn.mkdir(root .. dir, 'p') end
    vim.fn.writefile({}, root .. '/src/target.lua')
    vim.fn.writefile({}, root .. '/packages/a/node_modules/dep/target.lua')
    vim.fn.writefile({}, root .. '/packages/a/.git/target.lua')
    local saved = require('blackbox').folders
    require('blackbox').folders = function() return { root } end
    local found = vim.json.decode(require('blackbox.tools.editor').find_file({ pattern = '**/target.lua' }))
    require('blackbox').folders = saved
    assert.are.same({ root .. '/src/target.lua' }, found)
    vim.fn.delete(root, 'rf')
  end)
end)

describe('output', function()
  it('never fails on a limit of 0', function()
    local events = require('blackbox.events')
    events._reset()
    events.on.output({ id = 1, config = { name = 's' } }, { output = 'a' })
    events.on.output({ id = 1, config = { name = 's' } }, { output = 'b' })
    local page = events.read_output({ limit = 0 })
    assert.equals(1, #page.entries)
    assert.is_true(page.more)
  end)
end)
