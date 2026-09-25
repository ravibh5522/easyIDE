const vscode = require('vscode');
exports.activate = async function (context) {
  const t = Number(process.env.SPIKE_T0 || 0);
  console.log(`[hello] activate called, ms since renderer spawn: ${t ? Date.now() - t : '?'}; vscode.version=${vscode.version}`);
  context.subscriptions.push(vscode.commands.registerCommand('hello.say', (who) => {
    vscode.window.showInformationMessage(`Hello ${who}!`);
    return `said hello to ${who}`;
  }));
  const pick = await vscode.window.showInformationMessage('Hello from spike', 'OK', 'Cancel');
  console.log(`[hello] showInformationMessage resolved -> ${pick}`);
  const sb = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
  sb.text = '$(check) spike'; sb.show();
  const out = vscode.window.createOutputChannel('Spike');
  out.appendLine('hello output');
  const cfg = vscode.workspace.getConfiguration('editor').get('tabSize');
  console.log(`[hello] editor.tabSize=${cfg}; folders=${JSON.stringify((vscode.workspace.workspaceFolders||[]).map(f=>f.uri.toString()))}`);
  const r = await vscode.commands.executeCommand('hello.say', 'world');
  console.log(`[hello] executeCommand(hello.say) -> ${r}`);
  process.send?.({}); // noop
  global.__spikeActivated = Date.now();
  console.log('[hello] ACTIVATED');
};
