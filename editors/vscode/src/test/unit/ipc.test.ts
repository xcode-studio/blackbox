// Node-only tests for the MCP <-> extension host bridge. No VS Code needed:
// each IPCServer here stands in for one VS Code window.

import * as assert from 'assert';
import * as fs from 'fs';
import * as net from 'net';
import * as os from 'os';
import * as path from 'path';
import { ChildProcess, spawn } from 'child_process';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { IPCServer } from '../../ipc/server';
import { callExtension } from '../../ipc/client';
import { listWindows, matchWindows, findWindows } from '../../ipc/registry';
import { PROTOCOL_VERSION, RegistryEntry, STATUS_TOOL, WindowStatus } from '../../ipc/protocol';
import { BridgeSession } from '../../mcp/session';
import { CURSOR, CURSOR_MAIN_PID, makeEntry, unixOnly } from './fixtures';

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

function tmpDir(prefix: string): string {
    // Keep socket paths short: macOS limits them to 104 bytes.
    const base = process.platform === 'win32' ? os.tmpdir() : '/tmp';
    return fs.realpathSync(fs.mkdtempSync(path.join(base, prefix)));
}

// Fake windows report a separate live process as their pid, so a spawned MCP
// server does not see them as its own ancestor (the chat-panel case).
let windowOwner: ChildProcess;

function fakeWindow(id: string, folder: string, opts: { healthCheckMs?: number } = {}): IPCServer {
    const ipc = new IPCServer({ id, pid: windowOwner.pid, folders: [folder], healthCheckMs: opts.healthCheckMs });
    const status: WindowStatus = { folders: [folder], focused: false, breakpoints: 0, debug: null };
    ipc.register(STATUS_TOOL, async () => JSON.stringify(status));
    ipc.register('whoami', async () => id);
    ipc.register('slow', async () => { await sleep(300); return 'late'; });
    ipc.register('boom', async () => { throw new Error('kaboom'); });
    return ipc;
}

suite('IPC bridge', () => {
    suiteSetup(() => { windowOwner = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1e6)'], { stdio: 'ignore' }); });
    suiteTeardown(() => { windowOwner.kill(); });

    let ipcDir: string;
    let wsA: string;
    let wsB: string;
    let elsewhere: string;
    let windows: IPCServer[];

    setup(() => {
        ipcDir = tmpDir('bbx-ipc-');
        process.env.BLACKBOX_IPC_DIR = ipcDir;
        wsA = tmpDir('bbx-wsA-');
        wsB = tmpDir('bbx-wsB-');
        elsewhere = tmpDir('bbx-else-');
        windows = [];
    });

    teardown(() => {
        windows.forEach(w => w.dispose());
        delete process.env.BLACKBOX_IPC_DIR;
        for (const d of [ipcDir, wsA, wsB, elsewhere]) {
            fs.rmSync(d, { recursive: true, force: true });
        }
    });

    async function open(id: string, folder: string, opts?: { healthCheckMs?: number }) {
        const w = fakeWindow(id, folder, opts);
        await w.start();
        windows.push(w);
        return w;
    }

    const sessionAt = (cwd: string) => new BridgeSession({ listWindows, send: callExtension, cwd, env: {}, ancestors: () => [] });
    const who = (cwd: string) => sessionAt(cwd).call({ tool: 'whoami', args: {}, timeoutMs: 2000 }).then(r => r.resp.result);

    test('routes each call to the window that owns the cwd', async () => {
        await open('A', wsA);
        await open('B', wsB);
        assert.strictEqual(await who(wsA), 'A');
        assert.strictEqual(await who(path.join(wsB, 'src', 'deep')), 'B');
    });

    test('closing one window does not break another (regression)', async () => {
        const a = await open('A', wsA);
        const b = await open('B', wsB);
        b.dispose();
        assert.ok(fs.existsSync(a.socketPath), 'window A socket must survive window B closing');
        assert.strictEqual(await who(wsA), 'A');
        // A is now the only window, so it serves any cwd.
        assert.strictEqual(await who(wsB), 'A');
    });

    test('reloading a window is picked up without reconnecting', async () => {
        const first = await open('A', wsA);
        assert.strictEqual(await who(wsA), 'A');
        first.dispose();
        await open('A2', wsA);
        assert.strictEqual(await who(wsA), 'A2');
    });

    unixOnly('recreates its socket if the file is deleted', async () => {
        const a = await open('A', wsA, { healthCheckMs: 50 });
        fs.unlinkSync(a.socketPath);
        await sleep(200);
        assert.ok(fs.existsSync(a.socketPath), 'socket should be recreated');
        assert.strictEqual(await who(wsA), 'A');
    });

    test('retries while the window is still starting', async () => {
        const pending = who(wsA);
        await sleep(100);
        await open('A', wsA);
        assert.strictEqual(await pending, 'A');
    });

    test('survives a client that hangs up before the reply', async () => {
        const a = await open('A', wsA);
        await assert.rejects(
            callExtension(a.socketPath, { tool: 'slow', args: {}, timeoutMs: 50 }),
            (err: any) => err.code === 'ETIMEDOUT',
        );
        await sleep(400); // Let the handler finish and try to write.
        assert.strictEqual(await who(wsA), 'A');
    });

    test('reaches a TCP window (JetBrains on Windows) with its token', async () => {
        const seen: unknown[] = [];
        const server = net.createServer(socket => {
            socket.on('data', chunk => {
                const req = JSON.parse(chunk.toString().trim());
                seen.push(req.token);
                socket.end(JSON.stringify({ id: req.id, result: `hello ${req.tool}` }) + '\n');
            });
        });
        await new Promise<void>(r => server.listen(0, '127.0.0.1', r));
        try {
            const port = (server.address() as net.AddressInfo).port;
            const resp = await callExtension(`tcp://127.0.0.1:${port}/abc123`, { tool: 'whoami', args: {}, timeoutMs: 2000 });
            assert.strictEqual(resp.result, 'hello whoami');
            assert.deepStrictEqual(seen, ['abc123']);
            await assert.rejects(callExtension('tcp://example.com:80/abc', { tool: 'whoami', args: {}, timeoutMs: 2000 }), /Unsupported window address/);
        } finally {
            server.close();
        }
    });

    test('keeps TCP windows in the registry: their socket is not a file', async () => {
        const tcp = makeEntry('T', [wsA], { pid: windowOwner.pid, socket: 'tcp://127.0.0.1:1/ab' });
        fs.writeFileSync(path.join(ipcDir, 'T.json'), JSON.stringify(tcp));
        assert.ok(listWindows().some(w => w.id === 'T'));
    });

    test('returns handler errors as errors', async () => {
        await open('A', wsA);
        const { resp } = await sessionAt(wsA).call({ tool: 'boom', args: {}, timeoutMs: 2000 });
        assert.strictEqual(resp.error, 'kaboom');
    });

    test('removes registry entries of dead extension hosts', async () => {
        const stale = makeEntry('dead', [wsA], { pid: 2 ** 22 + 1, socket: path.join(ipcDir, 'dead.sock') });
        fs.writeFileSync(path.join(ipcDir, 'dead.json'), JSON.stringify(stale));
        await open('B', wsB);
        assert.deepStrictEqual(listWindows().map(w => w.id), ['B']);
        assert.ok(!fs.existsSync(path.join(ipcDir, 'dead.json')));
    });

    test('writes editor, version and protocol into its registry entry', async () => {
        const w = new IPCServer({ id: 'E', pid: windowOwner.pid, folders: [wsA], app: CURSOR, appPid: CURSOR_MAIN_PID, extensionVersion: '9.9.9' });
        await w.start();
        windows.push(w);
        const [entry] = listWindows();
        assert.deepStrictEqual(
            { app: entry.app, appPid: entry.appPid, extensionVersion: entry.extensionVersion, protocol: entry.protocol },
            { app: CURSOR, appPid: CURSOR_MAIN_PID, extensionVersion: '9.9.9', protocol: PROTOCOL_VERSION },
        );
    });

    test('reads entries from older extensions with defaults', async () => {
        const old = { id: 'old', pid: windowOwner.pid, socket: path.join(ipcDir, 'old.sock'), folders: [wsA], startedAt: 0, focusedAt: 0 };
        fs.writeFileSync(old.socket, ''); // Stands in for the window's live socket.
        fs.writeFileSync(path.join(ipcDir, 'old.json'), JSON.stringify(old));
        const [entry] = listWindows();
        assert.strictEqual(entry.protocol, 1);
        assert.strictEqual(entry.app.name, 'unknown (older extension)');
    });

    unixOnly('treats an entry whose socket is gone as dead, even if its pid is alive (pid reuse)', async () => {
        const ghost = makeEntry('ghost', [wsA], { pid: windowOwner.pid!, socket: path.join(ipcDir, 'ghost.sock') });
        fs.writeFileSync(path.join(ipcDir, 'ghost.json'), JSON.stringify(ghost));
        await open('B', wsB);
        assert.deepStrictEqual(listWindows().map(w => w.id), ['B']);
        assert.ok(!fs.existsSync(path.join(ipcDir, 'ghost.json')), 'the stale entry is removed');
    });

    unixOnly('tightens its own IPC directory if other users could reach it', async () => {
        fs.chmodSync(ipcDir, 0o755);
        await open('A', wsA);
        assert.strictEqual(fs.statSync(ipcDir).mode & 0o777, 0o700);
    });

    unixOnly('refuses an IPC directory that is a symlink', async () => {
        const real = tmpDir('bbx-real-');
        const link = path.join(elsewhere, 'ipc');
        fs.symlinkSync(real, link);
        process.env.BLACKBOX_IPC_DIR = link;
        try {
            await assert.rejects(fakeWindow('A', wsA).start(), /not a directory/);
            assert.deepStrictEqual(listWindows(), []);
        } finally {
            fs.rmSync(real, { recursive: true, force: true });
        }
    });

    unixOnly('ignores registry entries in a directory other users can write', async () => {
        await open('A', wsA);
        fs.chmodSync(ipcDir, 0o777);
        try {
            assert.deepStrictEqual(listWindows(), [], 'a planted entry must not be trusted');
        } finally {
            fs.chmodSync(ipcDir, 0o700);
        }
    });

    test('end to end: an AI session outside any project picks its window', async function () {
        this.timeout(15000);
        await open('A', wsA);
        const b = await open('B', wsB);
        b.register('debug_list_breakpoints', async () => 'breakpoints from B');

        const transport = new StdioClientTransport({
            command: process.execPath,
            args: [path.resolve(__dirname, '../../mcp/server.js')],
            cwd: elsewhere,
            env: { ...process.env, BLACKBOX_IPC_DIR: ipcDir } as Record<string, string>,
        });
        const client = new Client({ name: 'test', version: '0.0.0' });
        await client.connect(transport);
        const call = (name: string, args: Record<string, unknown> = {}): Promise<any> => client.callTool({ name, arguments: args });
        try {
            assert.match(client.getInstructions() ?? '', /ide_list_windows/);

            const ambiguous = await call('debug_list_breakpoints');
            assert.strictEqual(ambiguous.isError, true);
            assert.match(ambiguous.content[0].text, /ide_select_window/);
            assert.match(ambiguous.content[0].text, /window A/);
            assert.match(ambiguous.content[0].text, /window B/);

            const listed = JSON.parse((await call('ide_list_windows')).content[0].text);
            assert.deepStrictEqual(listed.map((w: any) => w.window).sort(), ['A', 'B']);
            assert.ok(listed.every((w: any) => w.reachable && !w.selected && !w.matchesCwd));

            const selected = await call('ide_select_window', { window: path.basename(wsB) });
            assert.ok(!selected.isError, selected.content[0].text);

            const ok = await call('debug_list_breakpoints');
            assert.ok(!ok.isError);
            assert.strictEqual(ok.content[0].text, `[window: ${path.basename(wsB)}]\nbreakpoints from B`);

            // The pinned window closes: fail clearly instead of using A.
            b.dispose();
            const gone = await call('debug_list_breakpoints');
            assert.strictEqual(gone.isError, true);
            assert.match(gone.content[0].text, /no longer running/);
        } finally {
            await client.close();
        }
    });
});

suite('matchWindows / findWindows', () => {
    const entry = (id: string, folders: string[]): RegistryEntry => makeEntry(id, folders);
    const ids = (entries: RegistryEntry[]) => entries.map(e => e.id).sort();

    test('prefers the deepest containing folder', () => {
        assert.deepStrictEqual(ids(matchWindows([entry('outer', ['/proj']), entry('inner', ['/proj/app'])], '/proj/app/src')), ['inner']);
    });

    test('does not match sibling folders sharing a prefix', () => {
        assert.deepStrictEqual(ids(matchWindows([entry('a', ['/proj-a']), entry('b', ['/proj'])], '/proj-a2')), []);
    });

    test('matches windows opened on sub-folders of the directory', () => {
        const all = [entry('x', ['/sites/x']), entry('y', ['/sites/y']), entry('z', ['/other'])];
        assert.deepStrictEqual(ids(matchWindows(all, '/sites')), ['x', 'y']);
    });

    test('returns every window sharing the deepest folder', () => {
        assert.deepStrictEqual(ids(matchWindows([entry('a', ['/proj']), entry('b', ['/proj'])], '/proj/src')), ['a', 'b']);
    });

    test('finds a window by id, path or folder name', () => {
        const all = [entry('1', ['/sites/wpcore.wpx']), entry('2', ['/sites/manheim.wpx'])];
        assert.deepStrictEqual(ids(findWindows(all, '2')), ['2']);
        assert.deepStrictEqual(ids(findWindows(all, '/sites/wpcore.wpx/wp')), ['1']);
        assert.deepStrictEqual(ids(findWindows(all, 'Manheim.WPX')), ['2']);
        assert.deepStrictEqual(ids(findWindows(all, 'nope')), []);
    });
});
