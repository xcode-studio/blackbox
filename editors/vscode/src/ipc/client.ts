// IPC transport used by the MCP stdio server: sends one request to one
// window's socket. Choosing the window is BridgeSession's job
// (mcp/session.ts).

import * as net from 'net';
import { IPCRequest, IPCResponse, isTcpSocket, parseTcpSocket } from './protocol';

const RETRY_DELAYS_MS = [250, 500, 750];
// Only errors where the request never reached VS Code, so a retry cannot
// run a tool (e.g. a step) twice. NO_WINDOWS covers a window still starting.
const RETRYABLE = new Set(['ENOENT', 'ECONNREFUSED', 'NO_WINDOWS']);

let requestId = 0;

export class IPCError extends Error {
    constructor(message: string, readonly code?: string) {
        super(message);
    }
}

export interface ToolCall {
    readonly tool: string;
    readonly args: Record<string, unknown>;
    readonly timeoutMs: number;
}

export type Send = (socket: string, call: ToolCall) => Promise<IPCResponse>;

function isRetryable(err: unknown): boolean {
    const code = (err as { code?: unknown } | null)?.code;
    return typeof code === 'string' && RETRYABLE.has(code);
}

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

export async function withRetry<T>(fn: () => Promise<T>): Promise<T> {
    for (let attempt = 0; ; attempt++) {
        try {
            return await fn();
        } catch (err: unknown) {
            if (attempt >= RETRY_DELAYS_MS.length || !isRetryable(err)) {
                throw err;
            }
            await sleep(RETRY_DELAYS_MS[attempt]);
        }
    }
}

export const callExtension: Send = (socket, { tool, args, timeoutMs }) => {
    const tcp = parseTcpSocket(socket);
    if (isTcpSocket(socket) && !tcp) {
        return Promise.reject(new IPCError(`Unsupported window address ${socket.replace(/\/[0-9a-f]+$/, '/…')}; update Blackbox.`));
    }
    const req: IPCRequest = { id: String(++requestId), tool, args, ...(tcp && { token: tcp.token }) };
    return new Promise((resolve, reject) => {
        const target: net.NetConnectOpts = tcp ? { host: tcp.host, port: tcp.port } : { path: socket };
        const client = net.createConnection(target, () => {
            client.write(JSON.stringify(req) + '\n');
        });
        const timer = setTimeout(() => {
            client.destroy();
            reject(new IPCError(`VS Code did not answer ${tool} within ${timeoutMs / 1000}s.`, 'ETIMEDOUT'));
        }, timeoutMs);

        let buffer = '';
        client.on('data', (chunk: Buffer) => {
            buffer += chunk.toString();
            const nl = buffer.indexOf('\n');
            if (nl < 0) {
                return;
            }
            clearTimeout(timer);
            client.end();
            try {
                resolve(JSON.parse(buffer.slice(0, nl)));
            } catch {
                reject(new IPCError('Malformed response from VS Code.'));
            }
        });
        client.on('error', (err: NodeJS.ErrnoException) => {
            clearTimeout(timer);
            reject(new IPCError(err.message, err.code));
        });
    });
};
