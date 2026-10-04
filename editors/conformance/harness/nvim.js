// Starts a headless Neovim with blackbox.nvim and nvim-dap, the way a user's
// Neovim would run it, for the conformance suite.
//
//   NVIM_DAP_PATH  where nvim-dap is checked out (default: lazy.nvim's folder)

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PLUGIN = path.resolve(__dirname, '../../neovim');
const GO_FIXTURE = fs.realpathSync(path.resolve(__dirname, '../fixtures/go'));
const NVIM_DAP = process.env.NVIM_DAP_PATH || path.join(os.homedir(), '.local/share/nvim/lazy/nvim-dap');

function initScript(workspace) {
    return `
vim.opt.rtp:prepend(${JSON.stringify(PLUGIN)})
vim.opt.rtp:prepend(${JSON.stringify(NVIM_DAP)})
vim.cmd.cd(${JSON.stringify(workspace)})
local dap = require('dap')
-- Delve, as nvim-dap-go would configure it.
dap.adapters.go = {
  type = 'server', port = '\${port}',
  executable = { command = 'dlv', args = { 'dap', '-l', '127.0.0.1:\${port}' } },
}
-- An adapter that starts but never answers, for the stalled-start check.
dap.adapters['blackbox-stall'] = {
  type = 'executable', command = ${JSON.stringify(process.execPath)}, args = { '-e', 'setInterval(function () {}, 1000)' },
}
require('blackbox').setup()
`;
}

async function startNvim({ ipcDir, workspace = GO_FIXTURE }) {
    const init = path.join(ipcDir, 'init.lua');
    fs.writeFileSync(init, initScript(workspace));
    const nvim = spawn('nvim', ['--headless', '-u', init], {
        cwd: workspace,
        env: { ...process.env, BLACKBOX_IPC_DIR: ipcDir },
        stdio: ['ignore', 'ignore', 'pipe'],
    });
    let stderr = '';
    nvim.stderr.on('data', chunk => { stderr += chunk; });

    // Ready once its registry entry exists.
    const registry = path.join(ipcDir, `${nvim.pid}.json`);
    for (let i = 0; i < 100 && !fs.existsSync(registry); i++) {
        await new Promise(r => setTimeout(r, 100));
    }
    if (!fs.existsSync(registry)) {
        nvim.kill();
        throw new Error(`Neovim did not register within 10s.\n${stderr}`);
    }
    return {
        name: 'Neovim',
        workspace,
        file: path.join(workspace, 'app.go'),
        functionName: 'main.add',
        skip: [],
        // Resolves at once if Neovim already exited (e.g. crashed during a test).
        stop: () => new Promise(resolve => {
            if (nvim.exitCode !== null || nvim.signalCode !== null) { return resolve(); }
            nvim.once('exit', resolve);
            nvim.kill('SIGTERM');
        }),
        stderr: () => stderr,
    };
}

module.exports = { startNvim };
