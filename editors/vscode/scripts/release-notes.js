// Prints the CHANGELOG.md notes for a version, for its GitHub Release.
//
//   node scripts/release-notes.js 0.5.0               -> the notes (markdown)
//   node scripts/release-notes.js 0.5.0 --prerelease  -> "true" or "false"
//
// CHANGELOG=<file> reads another editor's changelog (default: this one).
//
// Exits with an error if the version has no entry or the entry is empty, so
// nothing is released without release notes. Headings look like:
//   ## 0.5.0 (Pre-release) — 2026-10-01

const fs = require('fs');
const path = require('path');

const [version, flag] = process.argv.slice(2);
if (!version) {
    console.error('usage: release-notes.js <version> [--prerelease]');
    process.exit(2);
}

const changelog = fs.readFileSync(process.env.CHANGELOG || path.resolve(__dirname, '../CHANGELOG.md'), 'utf8');
const sections = changelog.split(/^## /m).slice(1);
const section = sections.find(s => s.split(/[\s(—]/)[0] === version);
if (!section) {
    console.error(`CHANGELOG.md has no "## ${version}" entry`);
    process.exit(1);
}

const [heading, ...body] = section.split('\n');
const notes = body.join('\n').trim();
if (!notes) {
    console.error(`CHANGELOG.md entry for ${version} is empty`);
    process.exit(1);
}

if (flag === '--prerelease') {
    console.log(/pre-release/i.test(heading) ? 'true' : 'false');
} else {
    console.log(notes);
}
