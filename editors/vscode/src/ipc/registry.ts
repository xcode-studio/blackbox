// Discovery of running editor windows (VS Code, Cursor, Antigravity, ...). Used by the MCP server to decide
// which extension host a tool call should go to.

import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { RegistryEntry, UNKNOWN_APP, ipcDir, isPrivateDir, isTcpSocket } from './protocol';

// A window's socket disappears with it. Checking it too catches entries
// whose pid was reused by another process. Named pipes (Windows) and TCP
// sockets aren't files, so there only the pid is checked.
function socketExists(socket: string): boolean {
    return process.platform === 'win32' || isTcpSocket(socket) || fs.existsSync(socket);
}

function isAlive(pid: number): boolean {
    try {
        process.kill(pid, 0);
        return true;
    } catch (err: unknown) {
        // EPERM means the process exists but belongs to someone else.
        return (err as NodeJS.ErrnoException).code === 'EPERM';
    }
}

// Fills fields that entries written by older extensions lack.
function withDefaults(raw: Partial<RegistryEntry> & Pick<RegistryEntry, 'id' | 'pid' | 'socket'>): RegistryEntry {
    return {
        folders: [],
        startedAt: 0,
        focusedAt: 0,
        app: UNKNOWN_APP,
        appPid: 0,
        extensionVersion: '',
        protocol: 1,
        ...raw,
    };
}

function readDir(dir: string): RegistryEntry[] {
    if (!isPrivateDir(dir)) {
        return []; // Missing, or reachable by other users: never trust it.
    }
    const entries: RegistryEntry[] = [];
    for (const name of fs.readdirSync(dir).filter(n => n.endsWith('.json'))) {
        const file = path.join(dir, name);
        let entry: RegistryEntry;
        try {
            entry = withDefaults(JSON.parse(fs.readFileSync(file, 'utf8')));
        } catch {
            continue; // Partially written or foreign file.
        }
        if (!isAlive(entry.pid) || !socketExists(entry.socket)) {
            try { fs.unlinkSync(file); } catch { /* ignore */ }
            try { fs.unlinkSync(entry.socket); } catch { /* ignore */ }
            continue;
        }
        entries.push(entry);
    }
    return entries;
}

// Returns registry entries of live windows. Entries left behind by crashed
// extension hosts are removed along with their sockets.
export function listWindows(): RegistryEntry[] {
    return readDir(ipcDir());
}

function normalize(p: string): string {
    let resolved = path.resolve(p);
    try { resolved = fs.realpathSync(resolved); } catch { /* keep as is */ }
    // macOS and Windows file systems are case-insensitive by default.
    return process.platform === 'linux' ? resolved : resolved.toLowerCase();
}

function isTooBroad(dir: string): boolean {
    return dir === path.parse(dir).root || dir === normalize(os.homedir());
}

function contains(folder: string, target: string): boolean {
    return target === folder || target.startsWith(folder.endsWith(path.sep) ? folder : folder + path.sep);
}

const NO_MATCH = -1;

// Returns the windows that best match `dir`, with no fallback:
//   1. windows whose folder contains dir, keeping only the deepest folder,
//   2. otherwise windows with a folder inside dir.
// An empty result means no match; more than one means it is ambiguous.
export function matchWindows(entries: readonly RegistryEntry[], dir: string): RegistryEntry[] {
    const target = normalize(dir);
    const folders = entries.map(e => e.folders.map(normalize));
    const depths = folders.map(fs => Math.max(NO_MATCH, ...fs.filter(f => contains(f, target)).map(f => f.length)));
    const deepest = Math.max(NO_MATCH, ...depths);

    if (deepest !== NO_MATCH) {
        return entries.filter((_, i) => depths[i] === deepest);
    }
    // Every project is "inside" / or the home folder, which says nothing
    // about which one is meant (e.g. an agent started with cwd "/").
    if (isTooBroad(target)) {
        return [];
    }
    return entries.filter((_, i) => folders[i].some(f => contains(target, f)));
}

// Resolves a user-supplied window reference: an exact id, then a path,
// then a folder name (case-insensitive). `app` narrows it to one editor.
export function findWindows(entries: readonly RegistryEntry[], ref: string, app?: string): RegistryEntry[] {
    const scoped = app ? entries.filter(e => e.app.name.toLowerCase().includes(app.toLowerCase())) : entries;
    const byId = scoped.filter(e => e.id === ref);
    if (byId.length > 0) {
        return byId;
    }
    if (path.isAbsolute(ref)) {
        return matchWindows(scoped, ref);
    }
    const name = ref.toLowerCase();
    return scoped.filter(e => e.folders.some(f => path.basename(f).toLowerCase() === name));
}
