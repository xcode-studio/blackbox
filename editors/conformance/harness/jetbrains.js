// Starts an IntelliJ-platform IDE (IntelliJ IDEA Community, through Gradle's
// runIdeForConformance task) with the Blackbox plugin, on a copy of the Java
// fixture, for the conformance suite.
//
// The fixture is a prebuilt jar run by a "JAR Application" configuration, so
// the IDE needs no JDK set up and no build: javac and java come from
// JAVA_HOME (or PATH). Linux CI runs this under xvfb-run.

const { spawn, spawnSync, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const PLUGIN = path.resolve(__dirname, '../../jetbrains');
const FIXTURE = path.resolve(__dirname, '../fixtures/java');
const isWindows = process.platform === 'win32';
// The first start builds the plugin and may download the IDE.
const START_TIMEOUT_MS = 10 * 60_000;

function javaHome() {
    if (process.env.JAVA_HOME) { return process.env.JAVA_HOME; }
    const { stderr } = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' });
    const match = /java\.home = (.+)/.exec(stderr || '');
    if (!match) { throw new Error('set JAVA_HOME to a JDK: the fixture is built with javac and jar'); }
    return match[1].trim();
}

const xml = s => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

function runConfiguration(name, jar, jre) {
    return `<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="${xml(name)}" type="JarApplication">
    <option name="JAR_PATH" value="${xml(jar)}" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="true" />
    <option name="ALTERNATIVE_JRE_PATH" value="${xml(jre)}" />
    <method v="2" />
  </configuration>
</component>
`;
}

// A project around the fixture: one module with the source, the compiled jar,
// and the run configurations the tests start by name.
function prepareWorkspace(dir) {
    const workspace = path.join(dir, 'fixture');
    fs.mkdirSync(path.join(workspace, '.idea', 'runConfigurations'), { recursive: true });
    fs.copyFileSync(path.join(FIXTURE, 'App.java'), path.join(workspace, 'App.java'));

    const jdk = javaHome();
    const bin = name => path.join(jdk, 'bin', isWindows ? `${name}.exe` : name);
    const classes = path.join(dir, 'classes');
    execFileSync(bin('javac'), ['-g', '--release', '17', '-d', classes, path.join(workspace, 'App.java')]);
    execFileSync(bin('jar'), ['--create', '--file', path.join(workspace, 'app.jar'), '--main-class', 'App', '-C', classes, '.']);

    const idea = path.join(workspace, '.idea');
    fs.writeFileSync(path.join(idea, 'modules.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="file://$PROJECT_DIR$/fixture.iml" filepath="$PROJECT_DIR$/fixture.iml" />
    </modules>
  </component>
</project>
`);
    fs.writeFileSync(path.join(workspace, 'fixture.iml'), `<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$" isTestSource="false" />
    </content>
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
`);
    const configs = path.join(idea, 'runConfigurations');
    fs.writeFileSync(path.join(configs, 'Fixture.xml'), `<!-- Used by the Blackbox conformance suite. -->\n` +
        runConfiguration('Fixture', '$PROJECT_DIR$/app.jar', jdk));
    // The IDE refuses it (no such JRE), to check that the error is reported.
    fs.writeFileSync(path.join(configs, 'Fixture_broken.xml'),
        runConfiguration('Fixture (broken)', '$PROJECT_DIR$/app.jar', path.join(dir, 'no-such-jre')));
    return fs.realpathSync(workspace);
}

async function startJetBrains({ ipcDir }) {
    const workspace = prepareWorkspace(ipcDir);
    // Where the plugin installs its MCP launcher (not the user's ~/.blackbox).
    const home = path.join(ipcDir, 'home');
    const gradlew = path.join(PLUGIN, isWindows ? 'gradlew.bat' : 'gradlew');
    const gradle = spawn(gradlew, ['--console=plain', 'runIdeForConformance', `-PblackboxProject=${workspace}`], {
        cwd: PLUGIN,
        env: { ...process.env, BLACKBOX_IPC_DIR: ipcDir, BLACKBOX_HOME: home },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    let log = '';
    gradle.stdout.on('data', chunk => { log += chunk; });
    gradle.stderr.on('data', chunk => { log += chunk; });

    // Ready once the project's registry entry exists.
    const started = Date.now();
    let entry;
    while (!entry && Date.now() - started < START_TIMEOUT_MS && gradle.exitCode === null) {
        const file = fs.readdirSync(ipcDir).find(f => f.endsWith('.json'));
        if (file) {
            try { entry = JSON.parse(fs.readFileSync(path.join(ipcDir, file), 'utf8')); } catch { /* being written */ }
        }
        if (!entry) { await new Promise(r => setTimeout(r, 500)); }
    }
    if (!entry) {
        gradle.kill();
        throw new Error(`the IDE did not register within ${START_TIMEOUT_MS / 1000}s.\n${log.slice(-5000)}`);
    }

    return {
        name: entry.app.name,
        workspace,
        file: path.join(workspace, 'App.java'),
        functionName: 'App.add',
        // The plugin ships the MCP server and installs ~/.blackbox/blackbox-mcp.js.
        home,
        // Its run configurations can't stall the way a debug adapter can.
        skip: ['stalled start'],
        stop: () => new Promise(resolve => {
            if (gradle.exitCode !== null || gradle.signalCode !== null) { return resolve(); }
            gradle.once('exit', resolve);
            // Ends the IDE (Gradle's task then finishes); Gradle itself as a fallback.
            try { process.kill(entry.pid, 'SIGTERM'); } catch { gradle.kill(); }
            setTimeout(() => gradle.kill(), 30_000).unref();
        }),
        stderr: () => log.slice(-20000),
    };
}

module.exports = { startJetBrains };
