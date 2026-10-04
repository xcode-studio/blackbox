// IPC protocol between the MCP stdio server (child process) and the
// VS Code extension host. Communication is over a Unix socket using
// newline-delimited JSON.
//
// Every VS Code window runs its own extension host, so each one listens on
// its own socket and advertises itself with a registry file next to it:
//
//   <ipcDir>/<id>.sock   the socket
//   <ipcDir>/<id>.json   RegistryEntry describing the window
//
// The MCP server reads the registry to pick the window that owns its
// working directory (see registry.ts).

import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

// Bumped when the registry entry or internal tools change shape.
// 1 = 0.1.3-0.2.0 (no editor info, no window_status in 0.1.3).
export const PROTOCOL_VERSION = 2;

export interface IPCRequest {
    id: string;
    tool: string;
    args: Record<string, unknown>;
    token?: string; // TCP sockets only, see parseTcpSocket.
}

export interface IPCResponse {
    id: string;
    result?: string;
    error?: string;
}

// The editor a window belongs to: VS Code, Cursor, Antigravity, ...
export interface AppInfo {
    name: string;    // vscode.env.appName, e.g. "Cursor"
    scheme: string;  // vscode.env.uriScheme, e.g. "cursor"
    version: string; // vscode.version
}

export const UNKNOWN_APP: AppInfo = { name: 'unknown (older extension)', scheme: '', version: '' };

export interface RegistryEntry {
    id: string;
    pid: number;
    socket: string;
    folders: string[];
    startedAt: number;
    focusedAt: number;
    app: AppInfo;
    appPid: number;           // The editor's main process; parent of the extension host.
    extensionVersion: string;
    protocol: number;
}

// Live state of a window, returned by the internal STATUS_TOOL handler.
export interface WindowStatus {
    folders: string[];
    focused: boolean;
    breakpoints: number;
    debug: null | {
        name: string;
        type: string;
        state: 'stopped' | 'running';
        stoppedAt?: { file: string; line: number };
    };
}

// Internal IPC tool used by the MCP server to describe windows. Not exposed
// as an MCP tool itself.
export const STATUS_TOOL = 'window_status';

// A fixed, per-user directory rather than os.tmpdir(): the extension host
// and the MCP server are started by different parents and often see
// different TMPDIRs. Per-user so users on one machine never share it.
export function ipcDir(): string {
    if (process.env.BLACKBOX_IPC_DIR) {
        return process.env.BLACKBOX_IPC_DIR;
    }
    if (process.platform === 'win32') {
        return path.join(os.tmpdir(), 'blackbox'); // %TEMP% is already per-user.
    }
    return `/tmp/blackbox-${os.userInfo().uid}`;
}

// Throws unless dir is a real directory owned by this user and closed to
// everyone else, so no other user can plant registry entries or sockets.
export function assertPrivateDir(dir: string): void {
    if (process.platform === 'win32') {
        return;
    }
    const stat = fs.lstatSync(dir);
    if (!stat.isDirectory() || stat.isSymbolicLink()) {
        throw new Error(`${dir} is not a directory`);
    }
    if (stat.uid !== os.userInfo().uid) {
        throw new Error(`${dir} is owned by another user`);
    }
    if ((stat.mode & 0o077) !== 0) {
        throw new Error(`${dir} is accessible to other users (mode ${(stat.mode & 0o777).toString(8)})`);
    }
}

export function isPrivateDir(dir: string): boolean {
    try {
        assertPrivateDir(dir);
        return true;
    } catch {
        return false;
    }
}

// Windows named pipes share one namespace across all users, so the name
// gets an unguessable part: only the private registry file reveals it, and
// no other user can create it ahead of a window.
export function socketPath(id: string): string {
    if (process.platform === 'win32') {
        return `\\\\.\\pipe\\blackbox-${id}-${crypto.randomBytes(12).toString('hex')}`;
    }
    return path.join(ipcDir(), `${id}.sock`);
}

// Windows where Node can't reach the editor's Unix socket (JetBrains IDEs on
// Windows: Java has no named pipes) listen on TCP loopback instead. Their
// registry entry's socket is "tcp://127.0.0.1:<port>/<token>", and every
// request carries the token; the registry folder is private, so only this
// user can read it.
const TCP_SOCKET = /^tcp:\/\/(127\.0\.0\.1|localhost):(\d+)\/([0-9a-f]+)$/;

export function isTcpSocket(socket: string): boolean {
    return socket.startsWith('tcp://');
}

export function parseTcpSocket(socket: string): { host: string; port: number; token: string } | undefined {
    const match = TCP_SOCKET.exec(socket);
    return match ? { host: match[1], port: Number(match[2]), token: match[3] } : undefined;
}

export function registryPath(id: string): string {
    return path.join(ipcDir(), `${id}.json`);
}
