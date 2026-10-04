# Blackbox for JetBrains IDEs

> **Status:** working, not yet published. Build it from this repository (below).

Lets AI tools (Claude Code, Cursor, any MCP client) drive the IDE's debugger: set breakpoints, start run configurations by name, wait for pauses, step, inspect and change variables, read logpoint output. It is built on the IDE's own debugger platform, so it works in every IntelliJ-based IDE (PhpStorm, IntelliJ IDEA, GoLand, PyCharm, WebStorm, …) with whatever that IDE debugs.

It speaks the same protocol as the [VS Code extension](../vscode/) and [blackbox.nvim](../neovim/), so one Blackbox MCP server sees JetBrains projects next to VS Code, Cursor, Antigravity and Neovim windows.

## Requirements
- A JetBrains IDE 2024.3 or later
- [Node.js](https://nodejs.org/) 18 or later, to run the MCP server (the plugin ships the server itself)

## Install
```sh
(cd ../vscode && npm ci)   # the plugin bundles the MCP server from there
./gradlew buildPlugin
```
Then in the IDE: **Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick `build/distributions/blackbox-jetbrains-<version>.zip`.

## Connect your AI tool
Run **Tools | Blackbox: Copy MCP Config** and add the copied entry under `mcpServers` in your MCP client's config (for Claude Code: `~/.claude.json`), then reconnect it. The entry is:
```json
{ "blackbox": { "command": "/path/to/node", "args": ["/Users/<you>/.blackbox/blackbox-mcp.js"] } }
```
The plugin installs that launcher (unless a newer one from another editor is there already), so the same entry works with every editor that has Blackbox.

## How a session finds this IDE
Each open project is its own window for Blackbox.
- **`claude` (or another AI tool) started in the IDE's terminal:** it uses that project automatically (`BLACKBOX_WINDOW` is set there).
- **Elsewhere:** the project whose folder contains the AI tool's folder. If several windows match, the AI calls `ide_list_windows` and `ide_select_window`, where projects show with the IDE's name, e.g. `"app": "PhpStorm"`.

## Launch configurations
`debug_start {configName}` starts the **run configuration** of that name in the debugger, as **Run | Debug '…'** does (before-launch tasks such as a build included). `debug_get_launch_configs` lists them. Creating a session from `type`/`request` arguments, as in VS Code, isn't possible here: make a run configuration instead.

For PHP, create the run configuration you use in PhpStorm (e.g. a *PHP Web Page* or *PHP Remote Debug* configuration) and start it by name.

## How it differs from VS Code
- **Logpoints and hit conditions** are applied by Blackbox: such a breakpoint pauses the program, Blackbox logs the message or counts the hit, and resumes it at once. The IDE's own UI may flicker for a moment. They show as normal breakpoints in the IDE.
- **Function breakpoints** use the language's method breakpoints, where the IDE has them (Java and other JVM languages: `"App.add"` or `"com.example.App.add"`).
- **Exception filters** are the IDE's exception breakpoints, so they stay set for later sessions (Java: `java-caught`, `java-uncaught`).
- **Threads** are listed only while the program is paused; thread ids stay the same for a session.
- **Program output** is whatever the run configuration's process prints, plus logpoint messages.

## Development
- **Unit tests:** `./gradlew test`
- **Run an IDE with the plugin:** `./gradlew runIde`
- **Conformance:** the same checks as the other editors, through the real MCP server, against a Java fixture in IntelliJ IDEA Community (downloaded by Gradle; javac and jar from `JAVA_HOME`):
  ```sh
  (cd ../vscode && npm ci && npm run compile) && (cd ../conformance && npm ci && npm run test:jetbrains)
  ```
