// Conformance: the AI debugging loop against an editor's window side, through
// the real Blackbox MCP server. Mirrors editors/vscode/src/test/suite/loop.test.ts.
//
//   BLACKBOX_EDITOR  nvim (Go fixture, delve; default) or jetbrains (Java fixture, IntelliJ IDEA)
//   BLACKBOX_SERVER  MCP server to use (default: the VS Code build's out/mcp/server.js)

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');

const HARNESSES = {
    nvim: { start: () => require('../harness/nvim').startNvim, setupMs: 30_000 },
    jetbrains: { start: () => require('../harness/jetbrains').startJetBrains, setupMs: 11 * 60_000 },
};
const EDITOR = process.env.BLACKBOX_EDITOR || 'nvim';
const HARNESS = HARNESSES[EDITOR];
if (!HARNESS) { throw new Error(`unknown BLACKBOX_EDITOR "${EDITOR}"; one of ${Object.keys(HARNESSES).join(', ')}`); }

// Both fixtures have the same lines.
const BREAKPOINT_LINE = 14;
const LOGPOINT_LINE = 15;
const SERVER = process.env.BLACKBOX_SERVER || path.resolve(__dirname, '../../vscode/out/mcp/server.js');

suite(`Debug loop (${EDITOR})`, () => {
    let ipcDir;
    let editor;
    let client;
    let WORKSPACE;
    let FIXTURE;

    async function call(tool, args = {}) {
        const result = await client.callTool({ name: tool, arguments: args });
        const text = result.content[0].text;
        if (result.isError) { throw new Error(`${tool}: ${text}`); }
        return text;
    }
    const callJson = async (tool, args) => JSON.parse(await call(tool, args));

    suiteSetup(async function () {
        this.timeout(HARNESS.setupMs);
        ipcDir = fs.realpathSync(fs.mkdtempSync(path.join(process.platform === 'win32' ? os.tmpdir() : '/tmp', 'bbx-conf-')));
        fs.chmodSync(ipcDir, 0o700);
        editor = await HARNESS.start()({ ipcDir });
        WORKSPACE = editor.workspace;
        FIXTURE = editor.file;
        client = new Client({ name: 'conformance', version: '0' });
        await client.connect(new StdioClientTransport({
            command: process.execPath,
            args: [SERVER],
            cwd: WORKSPACE,
            env: { ...process.env, BLACKBOX_IPC_DIR: ipcDir },
        }));
    });

    suiteTeardown(async () => {
        await client?.close();
        await editor?.stop();
        fs.rmSync(ipcDir, { recursive: true, force: true });
    });

    teardown(async function () {
        if (this.currentTest?.state === 'failed') { console.error(editor.stderr()); }
        await call('debug_stop').catch(() => undefined);
        await call('debug_remove_all_breakpoints').catch(() => undefined);
    });

    test('the window is listed with its editor', async () => {
        const [window] = await callJson('ide_list_windows');
        assert.strictEqual(window.app.name, editor.name);
        assert.strictEqual(window.reachable, true);
        assert.deepStrictEqual(window.folders, [WORKSPACE]);
    });

    test('the MCP launcher the editor installed serves its windows', async function () {
        if (!editor.home) { this.skip(); } // Editors that rely on the VS Code extension's.
        const launcher = new Client({ name: 'conformance-launcher', version: '0' });
        await launcher.connect(new StdioClientTransport({
            command: process.execPath,
            args: [path.join(editor.home, 'blackbox-mcp.js')],
            cwd: WORKSPACE,
            env: { ...process.env, BLACKBOX_IPC_DIR: ipcDir, BLACKBOX_HOME: editor.home },
        }));
        try {
            const result = await launcher.callTool({ name: 'ide_list_windows', arguments: {} });
            assert.strictEqual(JSON.parse(result.content[0].text)[0].app.name, editor.name);
        } finally {
            await launcher.close();
        }
    });

    test('start by name, wait, step, read logpoints, stop', async () => {
        await call('debug_set_breakpoint', { breakpoints: [
            { file: FIXTURE, line: BREAKPOINT_LINE },
            { file: FIXTURE, line: LOGPOINT_LINE, logMessage: 'logpoint total={total}' },
        ] });
        assert.match(await call('debug_start', { configName: 'Fixture' }), /started/);

        const hit = await callJson('debug_wait_for_stop', { timeoutMs: 60_000 });
        assert.strictEqual(hit.state, 'stopped', JSON.stringify(hit));
        assert.strictEqual(hit.line, BREAKPOINT_LINE, JSON.stringify(hit));
        assert.ok(hit.file.endsWith(path.basename(FIXTURE)), hit.file);

        const stepped = await callJson('debug_step_over');
        assert.strictEqual(stepped.state, 'stopped', JSON.stringify(stepped));
        assert.strictEqual(stepped.line, LOGPOINT_LINE);

        const filters = await callJson('debug_set_exception_breakpoints');
        assert.ok(Array.isArray(filters.available), JSON.stringify(filters));

        await call('debug_remove_breakpoint', { file: FIXTURE, line: BREAKPOINT_LINE });
        assert.match(await call('debug_continue'), /debug_wait_for_stop/);
        const ended = await callJson('debug_wait_for_stop', { timeoutMs: 30_000 });
        assert.strictEqual(ended.state, 'terminated', JSON.stringify(ended));

        const output = await callJson('debug_get_output', { match: 'logpoint total=' });
        const texts = output.entries.map(e => e.text).join('');
        assert.match(texts, /logpoint total=1/);
        assert.match(texts, /logpoint total=3/);
    });

    test('an unknown launch configuration name lists the available ones', async () => {
        await assert.rejects(call('debug_start', { configName: 'Nope' }), /Available: Fixture/);
    });

    test('hit counts, threads, frames, set variable, toggle, function breakpoints, run to line', async () => {
        await call('debug_set_breakpoint', { file: FIXTURE, line: BREAKPOINT_LINE, hitCondition: '2' });
        await call('debug_start', { configName: 'Fixture' });
        const hit = await callJson('debug_wait_for_stop', { timeoutMs: 60_000 });
        assert.strictEqual(hit.line, BREAKPOINT_LINE, JSON.stringify(hit));
        assert.strictEqual((await callJson('debug_evaluate', { expression: 'i' })).result, '2', 'hit condition skips the first pass');

        const { threads } = await callJson('debug_list_threads');
        assert.ok(threads.some(t => t.id === hit.threadId && t.stopped), JSON.stringify(threads));

        const context = await callJson('debug_get_source_context', { lines: 1 });
        assert.deepStrictEqual(context.lines.map(l => l.line), [BREAKPOINT_LINE - 1, BREAKPOINT_LINE, BREAKPOINT_LINE + 1]);
        assert.match(context.lines.find(l => l.current).text, /total = add\(total, i\)/);

        const frames = await callJson('debug_get_stack_trace', { levels: 2 });
        assert.ok(frames.length >= 1);
        const locals = await callJson('debug_get_variables', { frameId: frames[0].id, filter: 'total' });
        assert.ok(JSON.stringify(locals).includes('"total"'), JSON.stringify(locals));

        assert.match(await call('debug_set_variable', { name: 'total', value: '100' }), /100/);
        assert.strictEqual((await callJson('debug_evaluate', { expression: 'total' })).result, '100');

        assert.match(await call('debug_toggle_breakpoints', { enabled: false }), /Disabled 1/);
        assert.strictEqual((await callJson('debug_list_breakpoints'))[0].enabled, false);
        assert.match(await call('debug_toggle_breakpoints', { enabled: true, breakpoints: [{ file: FIXTURE, line: BREAKPOINT_LINE }] }), /Enabled 1/);
        assert.strictEqual((await callJson('debug_list_breakpoints'))[0].hitCondition, '2', 'toggling keeps the hit condition');

        const fn = editor.functionName;
        assert.match(await call('debug_set_function_breakpoint', { name: fn }), new RegExp(`ok: function ${fn}`));
        const withFunction = await callJson('debug_list_breakpoints');
        assert.ok(withFunction.some(b => b.type === 'function' && b.name.endsWith(fn)), JSON.stringify(withFunction));
        assert.match(await call('debug_remove_breakpoint', { functions: [fn] }), new RegExp(`removed function ${fn}`));

        const reached = await callJson('debug_run_to_line', { file: FIXTURE, line: LOGPOINT_LINE, timeoutMs: 20_000 });
        assert.strictEqual(reached.reachedTarget, true, JSON.stringify(reached));
        // The changed variable took effect: add(100, 2). (Program stdout isn't
        // reliably a DAP output event, e.g. delve prints it to its terminal.)
        assert.strictEqual((await callJson('debug_evaluate', { expression: 'total' })).result, '102');
        const lines = (await callJson('debug_list_breakpoints')).map(b => b.line);
        assert.deepStrictEqual(lines, [BREAKPOINT_LINE], 'the temporary breakpoint is removed');

        await call('debug_remove_all_breakpoints');
        await call('debug_continue');
        assert.strictEqual((await callJson('debug_wait_for_stop', { timeoutMs: 30_000 })).state, 'terminated');
    });

    test('after a stop, tools answer at once instead of talking to the ended session', async () => {
        await call('debug_set_breakpoint', { file: FIXTURE, line: BREAKPOINT_LINE });
        await call('debug_start', { configName: 'Fixture' });
        assert.strictEqual((await callJson('debug_wait_for_stop', { timeoutMs: 60_000 })).line, BREAKPOINT_LINE);
        await call('debug_stop');
        const started = Date.now();
        await assert.rejects(call('debug_evaluate', { expression: 'total' }), /no active debug session|session (has )?ended/);
        assert.strictEqual((await callJson('debug_wait_for_stop', { timeoutMs: 10_000 })).state, 'terminated');
        assert.ok(Date.now() - started < 5_000, `answered in ${Date.now() - started}ms`);
    });

    test('a launch the adapter refuses is reported as an error, and nothing is left waiting', async () => {
        await assert.rejects(call('debug_start', { configName: 'Fixture (broken)' }), /refused to launch/);
        const after = await callJson('debug_wait_for_stop', { timeoutMs: 10_000 });
        assert.strictEqual(after.state, 'terminated', JSON.stringify(after));
    });

    test('a start that never comes up fails with an explanation instead of hanging', async function () {
        if (editor.skip.includes('stalled start')) { this.skip(); }
        const started = Date.now();
        await assert.rejects(call('debug_start', { configName: 'Fixture (stalled)' }), /has not started after 20s/);
        assert.ok(Date.now() - started < 30_000, 'answers within the stall limit');
    });
});
