// Spike: fake "renderer" (main side) for VS Code's node extension host.
// Built from VS Code's own sources (PersistentProtocol, RPCProtocol, extHost.protocol identifiers),
// so it is also a prototype of "route 2b: thin JS adapter in the guest that bridges to Kotlin".
import * as net from 'net';
import * as cp from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { VSBuffer } from '../vscode/src/vs/base/common/buffer.js';
import { URI } from '../vscode/src/vs/base/common/uri.js';
import { PersistentProtocol } from '../vscode/src/vs/base/parts/ipc/common/ipc.net.js';
import { NodeSocket } from '../vscode/src/vs/base/parts/ipc/node/ipc.net.js';
import { RPCProtocol, RequestInitiator } from '../vscode/src/vs/workbench/services/extensions/common/rpcProtocol.js';
import { MainContext, ExtHostContext } from '../vscode/src/vs/workbench/api/common/extHost.protocol.js';
import { createMessageOfType, isMessageOfType, MessageType, UIKind } from '../vscode/src/vs/workbench/services/extensions/common/extensionHostProtocol.js';
import { ActivationKind } from '../vscode/src/vs/workbench/services/extensions/common/extensions.js';

const SP = process.env.SP!;
const EXTHOST = process.argv[2] || `${SP}/spike/out/exthost.mjs`;
const EXT_DIR = process.env.SPIKE_EXT || `${SP}/spike/hello`;
const REAL = !!process.env.SPIKE_EXT;
const WORKSPACE = `${SP}/spike/ws`;
const LOGDIR = `${SP}/spike/logs`;
fs.mkdirSync(WORKSPACE, { recursive: true });
fs.mkdirSync(LOGDIR, { recursive: true });

const t0 = Date.now();
const stamp = () => `+${String(Date.now() - t0).padStart(5)}ms`;
const calls: { t: number; actor: string; method: string; arg0?: any }[] = [];
const verbose = !!process.env.SPIKE_VERBOSE;

// ---- main-thread actors: one logging Proxy per MainContext identifier ------------------------
// Defaults for the few calls whose return value the ext host depends on during startup.
const answers: Record<string, (...a: any[]) => any> = {
	'MainThreadMessageService.$showMessage': (_sev: number, msg: string, _opts: any, commands: { title: string; handle: number }[]) => {
		console.log(`${stamp()} UI  showMessage "${msg}" buttons=${JSON.stringify(commands.map(c => c.title))} -> picking first`);
		return commands[0]?.handle;
	},
	'MainThreadCommands.$executeCommand': (id: string, args: any) => {
		console.log(`${stamp()} main executeCommand ${id}`);
		return undefined;
	},
	'MainThreadStatusBar.$setEntry': (...a: any[]) => { console.log(`${stamp()} UI  statusbar ${JSON.stringify(a[5] ?? a).slice(0, 80)}`); },
	'MainThreadOutputService.$register': (label: string) => { console.log(`${stamp()} UI  output channel "${label}"`); return 'spike-output-1'; },
	'MainThreadStorage.$initializeExtensionStorage': () => undefined,
	'MainThreadTelemetry.$publicLog': () => undefined,
	'MainThreadWindow.$getInitialState': () => ({ isFocused: true, isActive: true }),
	'MainThreadLanguageModelTools.$getTools': () => [],
	'MainThreadCommands.$getCommands': () => [],
	'MainThreadWorkspace.$startFileSearch': () => [],
	'MainThreadExtensionService.$onExtensionActivationError': (id: any, err: any, missing: any) => { console.log(`${stamp()} ACTIVATION ERROR ${JSON.stringify(err).slice(0, 400)} missing=${JSON.stringify(missing)}`); setTimeout(finish, 500); },
	'MainThreadExtensionService.$onDidActivateExtension': () => { console.log(`${stamp()} EXTENSION ACTIVATED`); if (REAL) { setTimeout(finish, 3000); } },
};

function makeActor(name: string) {
	return new Proxy(Object.create(null), {
		get(target, prop) {
			if (typeof prop !== 'string' || !prop.startsWith('$')) { return undefined; }
			return (...args: any[]) => {
				calls.push({ t: Date.now() - t0, actor: name, method: prop, arg0: typeof args[0] === 'string' ? args[0] : undefined });
				const key = `${name}.${prop}`;
				if (verbose) { console.log(`${stamp()} <- ${key}(${safe(args)})`); }
				const f = answers[key];
				return f ? f(...args) : undefined;
			};
		}
	});
}
function safe(a: any) { try { return JSON.stringify(a).slice(0, 160); } catch { return '<unserialisable>'; } }

// ---- init data (IExtensionHostInitData) -----------------------------------------------------
const pkg = JSON.parse(fs.readFileSync(`${EXT_DIR}/package.json`, 'utf8'));
const extDesc = {
	...pkg,
	identifier: { value: `${pkg.publisher}.${pkg.name}`, _lower: `${pkg.publisher}.${pkg.name}`.toLowerCase() },
	extensionLocation: URI.file(EXT_DIR).toJSON(),
	isBuiltin: false, isUserBuiltin: false, isUnderDevelopment: false, targetPlatform: 'undefined', preRelease: false,
};
const initData = {
	version: '1.200.0', quality: undefined, commit: undefined, parentPid: 0,
	environment: {
		isExtensionDevelopmentDebug: false, appName: 'easyIDE-spike', appHost: 'desktop', appLanguage: 'en', isExtensionTelemetryLoggingOnly: true,
		appUriScheme: 'easyide', appRoot: URI.file(SP + '/spike').toJSON(),
		globalStorageHome: URI.file(`${SP}/spike/state/global`).toJSON(), workspaceStorageHome: URI.file(`${SP}/spike/state/ws`).toJSON(),
	},
	workspace: { id: 'spike-ws', name: 'ws', configuration: null, isUntitled: false },
	extensions: { versionId: 1, allExtensions: [extDesc], activationEvents: { [extDesc.identifier.value]: pkg.activationEvents }, myExtensions: [extDesc.identifier] },
	telemetryInfo: { sessionId: 's', machineId: 'm', sqmId: '', devDeviceId: 'd', firstSessionDate: new Date().toUTCString() },
	logLevel: 3 /* Info */, loggers: [], logsLocation: URI.file(LOGDIR).toJSON(), autoStart: true,
	remote: { isRemote: false, authority: undefined, connectionData: null },
	consoleForward: { includeStack: false, logNative: false },
	uiKind: UIKind.Desktop,
};

const emptyModel = { contents: {}, keys: [], overrides: [] };
const configData = {
	defaults: { contents: { editor: { tabSize: 4 } }, keys: ['editor.tabSize'], overrides: [] },
	policy: emptyModel, application: emptyModel, userLocal: emptyModel, userRemote: emptyModel, workspace: emptyModel, folders: [], configurationScopes: [],
};

// ---- transport: named pipe + PersistentProtocol (VSCODE_EXTHOST_IPC_HOOK) ---------------------
const pipe = path.join(os.tmpdir(), `spike-exthost-${process.pid}.sock`);
try { fs.unlinkSync(pipe); } catch { }

const server = net.createServer(sock => {
	console.log(`${stamp()} ext host connected to pipe`);
	const protocol = new PersistentProtocol({ socket: new NodeSocket(sock, 'renderer') });
	const hs = protocol.onMessage(msg => {
		if (isMessageOfType(msg, MessageType.Ready)) {
			console.log(`${stamp()} <- Ready; sending IExtensionHostInitData (${JSON.stringify(initData).length} bytes)`);
			protocol.send(VSBuffer.fromString(JSON.stringify(initData)));
		} else if (isMessageOfType(msg, MessageType.Initialized)) {
			console.log(`${stamp()} <- Initialized`);
			hs.dispose();
			startRpc(protocol);
		} else {
			console.log(`${stamp()} unexpected handshake message ${msg.byteLength}b`);
		}
	});
});

let msgIn = 0, msgOut = 0, bytesIn = 0, bytesOut = 0;
function startRpc(protocol: PersistentProtocol) {
	const logger = {
		logIncoming(len: number, _req: number, _i: RequestInitiator, _s: string) { msgIn++; bytesIn += len; },
		logOutgoing(len: number, _req: number, _i: RequestInitiator, _s: string) { msgOut++; bytesOut += len; },
	};
	const rpc = new RPCProtocol(protocol, logger);
	for (const [name, id] of Object.entries(MainContext)) { rpc.set(id as any, makeActor(name)); }
	const ws = rpc.getProxy(ExtHostContext.ExtHostWorkspace);
	const conf = rpc.getProxy(ExtHostContext.ExtHostConfiguration);
	const ext = rpc.getProxy(ExtHostContext.ExtHostExtensionService);
	const cmds = rpc.getProxy(ExtHostContext.ExtHostCommands);
	conf.$initializeConfiguration(configData as any);
	ws.$initializeWorkspace({ id: 'spike-ws', name: 'ws', folders: [{ uri: URI.file(WORKSPACE).toJSON(), name: 'ws', index: 0 }] } as any, true);
	ext.$activateByEvent('*', ActivationKind.Normal).then(() => console.log(`${stamp()} $activateByEvent('*') resolved`));
	if (REAL) {
		ext.$activateByEvent('onStartupFinished', ActivationKind.Normal);
		ext.$activate(extDesc.identifier as any, { startup: false, extensionId: extDesc.identifier as any, activationEvent: 'spike' } as any).then(r => console.log(`${stamp()} $activate -> ${r}`), e => console.log(`${stamp()} $activate failed ${e}`));
		setTimeout(finish, 20000);
	}
	const poll = setInterval(async () => {
		// wait until the extension has registered its command, then invoke it from the main side
		if (calls.some(c => c.actor === 'MainThreadCommands' && c.method === '$registerCommand' && c.arg0 === 'hello.say')) {
			clearInterval(poll);
			try {
				const r = await cmds.$executeContributedCommand('hello.say', ['main-side']);
				console.log(`${stamp()} main -> $executeContributedCommand(hello.say) = ${JSON.stringify(r)}`);
			} catch (e) { console.log(`${stamp()} main -> $executeContributedCommand failed: ${e}`); }
			const lat: number[] = [];
			for (let i = 0; i < 2000; i++) { const t = process.hrtime.bigint(); await ext.$test_latency(i); lat.push(Number(process.hrtime.bigint() - t) / 1e6); }
			lat.sort((a, b) => a - b);
			console.log(`LATENCY round-trip ms p50=${lat[1000].toFixed(3)} p90=${lat[1800].toFixed(3)} p99=${lat[1980].toFixed(3)}`);
			setTimeout(finish, 1500);
		}
	}, 20);
}

let child: cp.ChildProcess;
let finished = false;
function finish() {
	if (finished) { return; } finished = true;
	const byActor: Record<string, Record<string, number>> = {};
	for (const c of calls) { ((byActor[c.actor] ??= {})[c.method] ??= 0); byActor[c.actor][c.method]++; }
	const summary = { totalMainThreadCalls: calls.length, distinctMethods: new Set(calls.map(c => c.actor + '.' + c.method)).size, msgIn, msgOut, bytesIn, bytesOut, byActor };
	fs.writeFileSync(`${SP}/spike/logs/calls-${path.basename(path.dirname(EXT_DIR))}.json`, JSON.stringify({ summary, calls }, null, 1));
	console.log(`SUMMARY ${JSON.stringify(summary)}`);
	child.send?.('rss');
	setTimeout(() => { child.kill(); server.close(); process.exit(0); }, 300);
}

server.listen(pipe, () => {
	const execArgv = (process.env.SPIKE_EXECARGV || '').split(' ').filter(Boolean);
	child = cp.spawn(process.execPath, [...execArgv, `${SP}/spike/rss-probe.mjs`, EXTHOST], {
		env: { ...process.env, VSCODE_EXTHOST_IPC_HOOK: pipe, VSCODE_HANDLES_UNCAUGHT_ERRORS: 'true', SPIKE_T0: String(t0) },
		stdio: ['ignore', 'inherit', 'inherit', 'ipc'],
	});
	console.log(`${stamp()} spawned ext host pid ${child.pid}: ${EXTHOST}`);
	child.on('message', (m: any) => { if (m?.rss) { console.log(`RSS ${JSON.stringify(m)}`); } });
	child.on('exit', (c, s) => { console.log(`${stamp()} ext host exited code=${c} signal=${s}`); setTimeout(() => process.exit(c ? 1 : 0), 100); });
});
