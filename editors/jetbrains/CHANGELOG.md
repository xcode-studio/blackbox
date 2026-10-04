# Changelog

All notable changes to the Blackbox plugin for JetBrains IDEs. Each version is also a [GitHub Release](https://github.com/xcode-studio/blackbox/releases) (tag `jetbrains-v<version>`) with these notes and the plugin zip.

Releases are published by pushing a `jetbrains-v<version>` tag; CI refuses to release a version without an entry here. Changes collect under `## Unreleased`; at release time that heading becomes `## <version> (Pre-release) — <date>` (leave out `(Pre-release)` for a stable release).

## Unreleased

- First version: all Blackbox tools in every IntelliJ-based IDE (2024.3 or later), on the IDE's debugger platform. Each open project is a Blackbox window; AI tools started in the IDE's terminal use their project.
- Ships the MCP server and installs `~/.blackbox/blackbox-mcp.js`, so it works without the VS Code extension (Node.js is still needed). **Tools | Blackbox: Copy MCP Config** copies the MCP client entry.
- Java: method breakpoints by name, and caught/uncaught exception filters.
