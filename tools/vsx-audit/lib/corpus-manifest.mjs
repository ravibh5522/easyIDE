// corpus-manifest.mjs - static analysis of an extension's package.json (contributes, activation, when-clause keys).
//
// Implicit activation events: since VS Code 1.74 the workbench derives activation events from contributions.
// The mapping below mirrors every `activationEventsGenerator` in microsoft/vscode main (commit 0b16cb97, 2026-09-25):
//   commands -> onCommand            src/vs/workbench/services/actions/common/menusExtensionPoint.ts:914
//   views -> onView                  src/vs/workbench/api/browser/viewsExtensionPoint.ts:262
//   languages -> onLanguage          src/vs/workbench/services/language/common/languageService.ts:115
//   customEditors -> onCustomEditor  src/vs/workbench/contrib/customEditor/common/extensionPoint.ts:124
//   authentication -> onAuthenticationRequest  src/vs/workbench/services/authentication/browser/authenticationService.ts:84
//   walkthroughs -> onWalkthrough    src/vs/workbench/contrib/welcomeGettingStarted/browser/gettingStartedExtensionPoint.ts:221
//   notebooks -> onNotebookSerializer, notebookRenderer -> onRenderer  src/vs/workbench/contrib/notebook/browser/notebookExtensionPoint.ts:248,260
//   terminal.profiles -> onTerminalProfile  src/vs/workbench/contrib/terminal/common/terminal.ts:668
//   taskDefinitions -> onTaskType    src/vs/workbench/contrib/tasks/common/taskDefinitionRegistry.ts:87
//   terminalQuickFixes -> onTerminalQuickFixRequest, debugVisualizers -> onDebugVisualizer,
//   chatParticipants -> onChatParticipant, chatContext -> onChatContextProvider, chatSessions -> onChatSession,
//   chatOutputRenderers -> onChatOutputRenderer, languageModelChatProviders -> onLanguageModelChatProvider,
//   languageModelTools -> onLanguageModelTool, mcpServerDefinitionProviders -> onMcpCollection,
//   linkPresentationProviders -> onLinkPresentation
const arr = (x) => (Array.isArray(x) ? x : x ? [x] : []);

export const IMPLICIT = {
  commands: (c) => arr(c).map((x) => x?.command && `onCommand:${x.command}`),
  views: (c) => Object.values(c || {}).flatMap((vs) => arr(vs).map((v) => v?.id && `onView:${v.id}`)),
  languages: (c) => arr(c).map((x) => x?.id && `onLanguage:${x.id}`),
  customEditors: (c) => arr(c).map((x) => x?.viewType && `onCustomEditor:${x.viewType}`),
  authentication: (c) => arr(c).map((x) => x?.id && `onAuthenticationRequest:${x.id}`),
  walkthroughs: (c) => arr(c).map((x) => x?.id && `onWalkthrough:${x.id}`),
  notebooks: (c) => arr(c).map((x) => x?.type && `onNotebookSerializer:${x.type}`),
  notebookRenderer: (c) => arr(c).map((x) => x?.id && `onRenderer:${x.id}`),
  terminal: (c) => arr(c?.profiles).map((x) => x?.id && `onTerminalProfile:${x.id}`),
  taskDefinitions: (c) => arr(c).map((x) => x?.type && `onTaskType:${x.type}`),
  terminalQuickFixes: (c) => arr(c).map((x) => x?.id && `onTerminalQuickFixRequest:${x.id}`),
  debugVisualizers: (c) => arr(c).map((x) => x?.id && `onDebugVisualizer:${x.id}`),
  chatParticipants: (c) => arr(c).map((x) => x?.id && `onChatParticipant:${x.id}`),
  chatContext: (c) => arr(c).map((x) => x?.id && `onChatContextProvider:${x.id}`),
  chatSessions: (c) => arr(c).map((x) => x?.type && `onChatSession:${x.type}`),
  chatOutputRenderers: (c) => arr(c).map((x) => x?.viewType && `onChatOutputRenderer:${x.viewType}`),
  languageModelChatProviders: (c) => arr(c).map((x) => x?.vendor && `onLanguageModelChatProvider:${x.vendor}`),
  languageModelTools: (c) => arr(c).map((x) => x?.name && `onLanguageModelTool:${x.name}`),
  mcpServerDefinitionProviders: (c) => arr(c).map((x) => x?.id && `onMcpCollection:${x.id}`),
  linkPresentationProviders: (c) => arr(c).map((x) => x?.id && `onLinkPresentation:${x.id}`),
};

/** Count of a contribution point: settings for configuration, array length, sum over object-of-arrays, else #keys. */
function pointCount(point, v) {
  if (point === 'configuration') return configurationProps(v); // settings, not sections
  if (Array.isArray(v)) return v.length;
  if (v && typeof v === 'object') {
    const vals = Object.values(v);
    if (vals.length && vals.every(Array.isArray)) return vals.reduce((a, x) => a + x.length, 0);
    return Object.keys(v).length || 1;
  }
  return 1;
}

function configurationProps(cfg) {
  return arr(cfg).reduce((a, c) => a + Object.keys(c?.properties || {}).length, 0);
}

// ---- when-clause key extraction -------------------------------------------------------------------------------
const TOKEN = /\s*(?:(&&|\|\||==|!=|=~|>=|<=|<|>|!|\(|\)|,)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|`(?:[^`\\]|\\.)*`)|([^\s()!=<>&|'",`~]+))/y;
const REGEX_LIT = /\s*\/((?:\\.|\[(?:\\.|[^\]\\])*\]|[^/\\])*)\/[a-z]*/y;
const VALUE_OPS = new Set(['==', '!=', '=~', '>=', '<=', '<', '>']);

/** Returns the context keys referenced by a when/enablement expression (identifiers only: no literals, no operators, no
 *  right-hand-side values of comparison operators, `in`/`not in` operands both kept since the RHS is a context key). */
export function whenKeys(expr) {
  const keys = [];
  if (typeof expr !== 'string') return keys;
  let i = 0;
  let prevOp = null;
  while (i < expr.length) {
    if (prevOp === '=~') {
      REGEX_LIT.lastIndex = i;
      const m = REGEX_LIT.exec(expr);
      if (m) { i = REGEX_LIT.lastIndex; prevOp = null; continue; }
    }
    TOKEN.lastIndex = i;
    const m = TOKEN.exec(expr);
    if (!m || TOKEN.lastIndex === i) { i++; continue; }
    i = TOKEN.lastIndex;
    if (m[1]) { prevOp = m[1]; continue; }
    if (m[2]) { prevOp = null; continue; }
    const w = m[3];
    if (w === 'in' || w === 'not') { prevOp = 'in'; continue; }
    if (VALUE_OPS.has(prevOp)) { prevOp = null; continue; }
    prevOp = null;
    if (w === 'true' || w === 'false' || /^-?\d/.test(w)) continue;
    keys.push(w);
  }
  return keys;
}

function collectWhen(node, out) {
  if (Array.isArray(node)) { for (const x of node) collectWhen(x, out); return; }
  if (!node || typeof node !== 'object') return;
  for (const [k, v] of Object.entries(node)) {
    if ((k === 'when' || k === 'enablement') && typeof v === 'string') for (const key of whenKeys(v)) out.add(key);
    else if (typeof v === 'object') collectWhen(v, out);
  }
}

export function analyseManifest(pkg) {
  const c = pkg.contributes || {};
  const contributes = {};
  for (const [k, v] of Object.entries(c)) contributes[k] = pointCount(k, v);
  const menus = {};
  for (const [loc, items] of Object.entries(c.menus || {})) menus[loc] = arr(items).length;
  const views = {};
  let webviewViews = 0;
  for (const [cont, vs] of Object.entries(c.views || {})) {
    views[cont] = arr(vs).length;
    webviewViews += arr(vs).filter((v) => v?.type === 'webview').length;
  }
  views.webviewViews = webviewViews;
  const viewsContainers = {};
  for (const [loc, vs] of Object.entries(c.viewsContainers || {})) viewsContainers[loc] = arr(vs).length;
  const implicit = new Set();
  for (const [point, gen] of Object.entries(IMPLICIT)) if (c[point]) for (const e of gen(c[point])) if (e) implicit.add(e);
  const wk = new Set();
  collectWhen(c, wk);
  const n = (k) => (c[k] ? pointCount(k, c[k]) : 0);
  return {
    engines: pkg.engines || {},
    main: pkg.main ?? null,
    browser: pkg.browser ?? null,
    extensionKind: pkg.extensionKind ?? null,
    activationEvents: arr(pkg.activationEvents),
    implicitActivationFromContributes: [...implicit],
    extensionDependencies: arr(pkg.extensionDependencies),
    extensionPack: arr(pkg.extensionPack),
    enabledApiProposals: arr(pkg.enabledApiProposals),
    contributes,
    commandsCount: arr(c.commands).length,
    menus,
    submenus: arr(c.submenus).length,
    viewsWelcome: arr(c.viewsWelcome).length,
    whenKeys: [...wk].sort(),
    viewsContainers,
    views,
    configurationProps: c.configuration ? configurationProps(c.configuration) : 0,
    keybindings: n('keybindings'),
    languages: n('languages'),
    grammars: n('grammars'),
    snippets: n('snippets'),
    themes: n('themes'),
    iconThemes: n('iconThemes'),
    productIconThemes: n('productIconThemes'),
    customEditors: n('customEditors'),
    walkthroughs: n('walkthroughs'),
    taskDefinitions: n('taskDefinitions'),
    debuggers: n('debuggers'),
    notebooks: n('notebooks'),
    terminal: { profiles: arr(c.terminal?.profiles).length },
    colors: n('colors'),
    icons: c.icons ? Object.keys(c.icons).length : 0,
    jsonValidation: n('jsonValidation'),
  };
}
