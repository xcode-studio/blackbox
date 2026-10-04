// Tests for working out which window or editor started the MCP server.

import * as assert from 'assert';
import { ancestorPids, detectLaunch } from '../../mcp/launch';
import { CURSOR, CURSOR_MAIN_PID, VSCODE_MAIN_PID, makeEntry } from './fixtures';

const VSCODE_WPCORE = makeEntry('4416', ['/sites/wpcore.wpx'], { pid: 4416 });
const CURSOR_WPCORE = makeEntry('5000', ['/sites/wpcore.wpx'], { pid: 5000, app: CURSOR, appPid: CURSOR_MAIN_PID });
const WINDOWS = [VSCODE_WPCORE, CURSOR_WPCORE];

suite('detectLaunch', () => {
    test('chat panel: a parent process is the window itself', () => {
        // MCP server -> claude -> Cursor extension host -> Cursor
        assert.strictEqual(detectLaunch(WINDOWS, [7001, 5000, CURSOR_MAIN_PID], {}).window?.id, '5000');
    });

    test('integrated terminal: BLACKBOX_WINDOW names the window', () => {
        assert.strictEqual(detectLaunch(WINDOWS, [], { BLACKBOX_WINDOW: '4416' }).window?.id, '4416');
    });

    test('integrated terminal without the variable: only the editor is known', () => {
        // MCP server -> claude -> zsh -> pty host -> VS Code
        const launch = detectLaunch(WINDOWS, [7001, 7002, 961, VSCODE_MAIN_PID], {});
        assert.strictEqual(launch.window, undefined);
        assert.strictEqual(launch.appPid, VSCODE_MAIN_PID);
    });

    test('a BLACKBOX_WINDOW from a closed window is ignored', () => {
        assert.deepStrictEqual(detectLaunch(WINDOWS, [], { BLACKBOX_WINDOW: '999' }), {});
    });

    test('several windows in one process (JetBrains projects): the process names only the editor', () => {
        const ide = 6000;
        const projects = [
            makeEntry('6000-aa', ['/sites/a'], { pid: ide, appPid: ide }),
            makeEntry('6000-bb', ['/sites/b'], { pid: ide, appPid: ide }),
        ];
        assert.deepStrictEqual(detectLaunch(projects, [7001, ide], {}), { appPid: ide });
        assert.strictEqual(detectLaunch(projects, [7001, ide], { BLACKBOX_WINDOW: '6000-bb' }).window?.id, '6000-bb');
    });

    test('external terminal: no link', () => {
        assert.deepStrictEqual(detectLaunch(WINDOWS, [7001, 43533], {}), {});
    });
});

suite('ancestorPids', () => {
    test('includes this process\'s parent', () => {
        const ancestors = ancestorPids();
        assert.strictEqual(ancestors[0], process.ppid);
        assert.ok(!ancestors.includes(process.pid));
    });
});
