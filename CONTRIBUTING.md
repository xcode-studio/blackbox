# Contributing to Blackbox

Thank you for your interest in contributing to Blackbox! We welcome contributions from everyone.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/yourusername/blackbox.git`
3. Create a branch: `git checkout -b feature/your-feature`
4. Make your changes
5. Test your changes thoroughly
6. Commit with clear messages: `git commit -am 'Add feature: description'`
7. Push to your fork: `git push origin feature/your-feature`
8. Create a Pull Request

## Code Guidelines

- Follow the existing code style and conventions
- Write clear, descriptive commit messages
- Include tests for new features
- Update documentation as needed
- Ensure all tests pass before submitting a PR

## Pull Request Process

1. Ensure your PR description clearly describes the changes
2. Reference related issues if applicable
3. Ensure CI/CD checks pass
4. Request review from maintainers
5. Address any feedback or changes requested

## Releasing (maintainers)

Each VS Code extension version is a tag `vscode-v<version>`, a [GitHub Release](https://github.com/xcode-studio/blackbox/releases) and an entry in [`editors/vscode/CHANGELOG.md`](editors/vscode/CHANGELOG.md).

1. Bump `version` in `editors/vscode/package.json` (`npm version <x.y.z> --no-git-tag-version`).
2. In `editors/vscode/CHANGELOG.md`, rename `## Unreleased` (where changes collect, one bullet per user-visible change) to `## <x.y.z> (Pre-release) — <YYYY-MM-DD>`. Leave out `(Pre-release)` for a stable release. Check it with `node scripts/release-notes.js <x.y.z>`.
3. Merge to `main`, then tag that commit and push the tag:
   ```sh
   git tag vscode-v<x.y.z> && git push origin vscode-v<x.y.z>
   ```
4. The **Release VS Code Extension** workflow does the rest:
   - checks the tag, `package.json` and changelog agree;
   - runs the unit and VS Code tests;
   - packages the `.vsix` once;
   - creates the GitHub Release with the changelog notes and the `.vsix` attached;
   - publishes that same `.vsix` to the VS Code Marketplace and Open VSX.

   If a check fails, nothing is released: fix it, delete the tag, and tag again.

To try the pipeline on a branch without releasing anything, run the workflow by hand from the Actions tab: it builds, tests and packages only.

## Reporting Issues

- Use the GitHub issue tracker
- Provide a clear description of the problem
- Include steps to reproduce
- Share relevant environment details

## Questions?

Feel free to open a discussion or issue if you have questions about contributing.

Thank you for helping improve Blackbox!
