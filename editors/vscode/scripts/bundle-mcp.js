// Bundles the MCP server into one file, for editors that ship it without
// node_modules (the JetBrains plugin):
//
//   dist-mcp/mcp/server.js   the server, with its dependencies
//   dist-mcp/tools.json      the tool contract it serves (read from ../tools.json)
//   dist-mcp/launcher.js     ~/.blackbox/blackbox-mcp.js (Node built-ins only)
//   dist-mcp/version.txt     the server's version, for install records

const esbuild = require('esbuild');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const out = path.join(root, 'dist-mcp');
fs.rmSync(out, { recursive: true, force: true });

const common = { bundle: true, platform: 'node', target: 'node18', format: 'cjs', logLevel: 'warning' };
esbuild.buildSync({ ...common, entryPoints: [path.join(root, 'src/mcp/server.ts')], outfile: path.join(out, 'mcp/server.js') });
esbuild.buildSync({ ...common, entryPoints: [path.join(root, 'src/launcher/blackboxMcp.ts')], outfile: path.join(out, 'launcher.js') });
fs.copyFileSync(path.resolve(root, '../../schema/tools.json'), path.join(out, 'tools.json'));
fs.writeFileSync(path.join(out, 'version.txt'), require(path.join(root, 'package.json')).version);
