// Where this MCP server was launched from, used to break ties between
// windows that match the same folder (e.g. one project open in VS Code and
// in Cursor).
//
//   Claude/Copilot chat panel  -> a parent process is that window's extension host
//   Editor integrated terminal -> BLACKBOX_WINDOW is set; an ancestor is the editor
//   Any other terminal         -> no link

import { execFileSync } from 'child_process';
import { RegistryEntry } from '../ipc/protocol';

const MAX_DEPTH = 64;

export interface LaunchInfo {
    readonly window?: RegistryEntry; // The exact window, when known.
    readonly appPid?: number;        // The editor's main process, when only the editor is known.
}

function processTable(): Map<number, number> {
    const parents = new Map<number, number>();
    const output = process.platform === 'win32'
        ? execFileSync('powershell.exe', ['-NoProfile', '-Command',
            'Get-CimInstance Win32_Process | ForEach-Object { "$($_.ProcessId) $($_.ParentProcessId)" }'], { encoding: 'utf8' })
        : execFileSync('ps', ['-A', '-o', 'pid=,ppid='], { encoding: 'utf8' });
    for (const line of output.split('\n')) {
        const [pid, ppid] = line.trim().split(/\s+/).map(Number);
        if (pid > 0 && ppid >= 0) {
            parents.set(pid, ppid);
        }
    }
    return parents;
}

// Ancestors of `pid`, nearest first. Empty if the process table can't be read.
export function ancestorPids(pid = process.pid): number[] {
    let parents: Map<number, number>;
    try {
        parents = processTable();
    } catch {
        return [];
    }
    const ancestors: number[] = [];
    for (let current = parents.get(pid); current && current > 1 && ancestors.length < MAX_DEPTH; current = parents.get(current)) {
        ancestors.push(current);
    }
    return ancestors;
}

export function detectLaunch(windows: readonly RegistryEntry[], ancestors: readonly number[], env: NodeJS.ProcessEnv): LaunchInfo {
    const fromTerminal = env.BLACKBOX_WINDOW && windows.find(w => w.id === env.BLACKBOX_WINDOW);
    if (fromTerminal) {
        return { window: fromTerminal };
    }
    // Only a process of its own identifies a window: JetBrains IDEs serve
    // every project window from one process, so there it names the editor.
    const fromChatPanel = windows.filter(w => ancestors.includes(w.pid));
    if (fromChatPanel.length === 1) {
        return { window: fromChatPanel[0] };
    }
    const editor = windows.find(w => w.appPid > 0 && ancestors.includes(w.appPid));
    return editor ? { appPid: editor.appPid } : {};
}
