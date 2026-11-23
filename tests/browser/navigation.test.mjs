#!/usr/bin/env node
// Real Chrome DOM/network tests using its built-in CDP pipe; no npm packages or downloads.
import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';

const executable = process.env.CHROME_BIN || (process.platform === 'darwin'
    ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
    : process.platform === 'win32' ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' : 'google-chrome');
const bridge = await readFile(fileURLToPath(new URL('../../src/main/resources/navigator-bridge.js', import.meta.url)), 'utf8');
const temporary = await mkdtemp(join(tmpdir(), 'navigator-browser-'));
const connections = new Set();
const blockedExternalRequests = new Set();
let scenario, checks = 0, chrome, http, pageText;
const json = (response, data, status = 200) => {
  response.writeHead(status, {'Content-Type': 'application/json'});
  response.end(JSON.stringify(data));
};
const fixture = sourceMap => {
  const svg = `<svg width="500" height="180"><g class="node" id="classId-Valid-0">
  <text id="class-valid" x="10" y="30">Valid</text><g class="methods-group">
  <text id="method-valid" x="10" y="60">+call(...)</text></g></g>
  <g class="node" id="classId-Second-1"><text id="class-second" x="10" y="100">Second</text></g></svg>`;
  return `<!doctype html><html><head><meta charset="utf-8"></head><body>
    <div class="controls"></div><script id="source-map" type="application/json">${sourceMap}</script>
    ${scenario.tabs ? `<div id="tabs"><button role="tab" aria-selected="false">First</button><button role="tab" aria-selected="true">Second</button></div>` : ''}
    <div id="canvas">${scenario.flow ? `<svg width="300" height="180">
      <g class="node" data-source-symbol="Line"><rect id="flow-valid" x="10" y="10" width="80" height="30"/><text x="12" y="30">Step</text></g>
      <g class="node" data-source-symbol="Invalid"><rect id="flow-invalid" x="10" y="60" width="80" height="30"/></g>
      </svg>` : svg + `<svg><text x="10" y="20"><a id="sequence-method" data-source-symbol="Valid" data-source-method="+call(...)">call</a></text><text><a id="sequence-invalid" data-source-symbol="Missing">bad</a></text></svg>`}</div>
    ${scenario.tabs ? `<script>document.querySelectorAll('[role="tab"]').forEach(tab => tab.onclick = () => {
      document.querySelectorAll('[role="tab"]').forEach(other => other.setAttribute('aria-selected', String(other === tab)));
      document.querySelector('#canvas').innerHTML = ${JSON.stringify(svg)};
    });</script>` : ''}
    <script src="/navigator-bridge.js" defer></script></body></html>`;
};
const validMap = {Valid: {className: 'demo.Valid', methods: {
      '+call(...)': {methodName: 'call', parameterTypes: ['java.lang.String']}
    }}, Second: {className: 'demo.Second'}};
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, description, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await delay(20);
  }
  throw new Error('Timed out: ' + description + '\nScenario: ' + JSON.stringify(scenario) + '\nNotice: ' + (await pageText?.()));
}

try {
  if (process.env.FLOWCHART_RENDERER) {
    const source = join(temporary, 'generated.md');
    await writeFile(source, `# Flows

## First
\`\`\`mermaid
flowchart TB
  %% source-map: {"Step":{"className":"demo.Worker","line":22}}
  Step["Execute"] --> Done["Done"]
\`\`\`

## Second
\`\`\`mermaid
flowchart TB
  %% source-map: {"Step":{"className":"demo.Worker","line":42}}
  Step["Retry"] --> Done["Done"]
\`\`\`
`);
    execFileSync('python3', [process.env.FLOWCHART_RENDERER, source]);
  }
  http = createServer(async (request, response) => {
    try {
      const path = new URL(request.url, 'http://localhost').pathname;
      if (path === '/fixture') {
        response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
        response.end(fixture(scenario.map ?? JSON.stringify(validMap))); return;
      }
      if (path === '/generated.html' && scenario.generated) {
        const page = await readFile(join(temporary, 'generated.html'), 'utf8');
        response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
        response.end(page.replace('</head>', '<script src="/navigator-bridge.js" defer></script></head>')); return;
      }
      if (scenario.generated && /^\/flowcharts\/generated\.assets\/[\w.-]+\.js$/.test(path)) {
        const asset = await readFile(join(temporary, path.slice(1)));
        response.writeHead(200, {'Content-Type': 'text/javascript; charset=utf-8'});
        response.end(asset); return;
      }
      if (path === '/navigator-bridge.js') {
        response.writeHead(200, {'Content-Type': 'text/javascript; charset=utf-8'}); response.end(bridge); return;
      }
      if (path.startsWith('/api/')) {
        assert.equal(request.headers.authorization, 'Bearer browser-test-token');
        if (path === '/api/symbol-map') {
          json(response, scenario.symbols ?? {Valid: 'demo.Valid', Second: 'demo.Second'}, scenario.unavailable ? 403 : 200); return;
        }
        // Chrome may retry idempotent DELETE requests; count cancelled task IDs.
        if (request.method === 'DELETE') { if (!scenario.cancelled.includes(path)) scenario.cancelled.push(path); json(response, {status: 'cancelled'}); return; }
        if (path === '/api/navigate') {
          let body = ''; for await (const chunk of request) body += chunk;
          scenario.requests.push(JSON.parse(body));
          if (scenario.mode === 'post-stall') return;
          assert.match(request.headers['x-navigator-request-id'], /^[a-f0-9-]{36}$/);
          json(response, {requestId: request.headers['x-navigator-request-id']}, 202); return;
        }
        if (path.startsWith('/api/result/')) {
          if (scenario.mode === 'body-stall') { response.writeHead(200, {'Content-Type': 'application/json'}); response.flushHeaders(); return; }
          if (scenario.mode === 'ambiguous' && scenario.requests.length === 1) {
            if (scenario.flow) {
              json(response, {status: 'ambiguous', candidates: [{label: 'demo.Worker:22',
                  target: {className: 'demo.Worker', line: 22, fileUrl: 'file:///demo/Worker.java'}}]}); return;
            }
            json(response, {status: 'ambiguous', candidates: [{label: 'demo.Valid#call(java.lang.String)',
                target: {className: 'demo.Valid', methodName: 'call', parameterTypes: ['java.lang.String'], fileUrl: 'jar://dependency!/demo/Valid.class'}}]}); return;
          }
          json(response, {status: scenario.mode === 'pending' ? 'pending' : scenario.mode === 'closed' ? 'closed' : 'opened'}); return;
        }
      }
      response.writeHead(404); response.end();
    } catch (error) { response.destroy(error); }
  });
  http.on('connection', socket => { connections.add(socket); socket.on('close', () => connections.delete(socket)); });
  await new Promise((resolve, reject) => { http.once('error', reject); http.listen(0, '127.0.0.1', resolve); });
  const origin = 'http://127.0.0.1:' + http.address().port;
  chrome = spawn(executable, ['--headless=new', '--no-first-run', '--no-default-browser-check',
        '--disable-background-networking', '--disable-component-update', '--disable-sync', '--metrics-recording-only',
        '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--password-store=basic', '--user-data-dir=' + join(temporary, 'profile'), '--remote-debugging-pipe', 'about:blank'],
      {stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe']});
  let serial = 0, buffer = '', diagnostics = '';
  const pending = new Map(), errors = new Map();
  chrome.stderr.on('data', data => { diagnostics = (diagnostics + data).slice(-3000); });
  chrome.on('error', error => { for (const entry of pending.values()) entry.reject(error); });
  chrome.on('exit', code => { for (const entry of pending.values()) entry.reject(new Error(`Chrome exited (${code}): ${diagnostics}`)); });
  chrome.stdio[4].on('data', chunk => {
    buffer += chunk.toString();
    let boundary;
    while ((boundary = buffer.indexOf('\0')) >= 0) {
      const message = JSON.parse(buffer.slice(0, boundary)); buffer = buffer.slice(boundary + 1);
      if (message.id && pending.has(message.id)) {
        const entry = pending.get(message.id); pending.delete(message.id);
        if (message.error) entry.reject(new Error(JSON.stringify(message.error))); else entry.resolve(message.result);
      } else if (message.method === 'Fetch.requestPaused') {
        const {requestId, request} = message.params;
        const local = new URL(request.url).origin === origin;
        if (!local) blockedExternalRequests.add(request.url);
        void cdp(local ? 'Fetch.continueRequest' : 'Fetch.failRequest',
            local ? {requestId} : {requestId, errorReason: 'InternetDisconnected'}, message.sessionId)
            .catch(() => {}); // Closing a test target also cancels its paused requests.
      } else if (message.method === 'Runtime.exceptionThrown') {
        const list = errors.get(message.sessionId);
        list?.push(message.params.exceptionDetails.text + ': ' + message.params.exceptionDetails.exception?.description);
      }
    }
  });
  function cdp(method, params = {}, sessionId) {
    return new Promise((resolve, reject) => {
      const id = ++serial;
      const timeout = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method + '\n' + diagnostics)); }, 10000);
      pending.set(id, {resolve: value => { clearTimeout(timeout); resolve(value); }, reject: error => { clearTimeout(timeout); reject(error); }});
      chrome.stdio[3].write(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}) + '\0');
    });
  }
  await cdp('Browser.getVersion');
  await cdp('Browser.setDownloadBehavior', {behavior: 'allow', downloadPath: temporary});
  let target;
  async function open(options = {}) {
    if (target) await cdp('Target.closeTarget', {targetId: target});
    scenario = {requests: [], cancelled: [], mode: 'opened', ...options};
    target = (await cdp('Target.createTarget', {url: 'about:blank'})).targetId;
    const session = (await cdp('Target.attachToTarget', {targetId: target, flatten: true})).sessionId;
    errors.set(session, []);
    await cdp('Page.enable', {}, session);
    await cdp('Runtime.enable', {}, session);
    // Block every non-fixture URL before DNS or network access.
    await cdp('Fetch.enable', {patterns: [{urlPattern: '*'}]}, session);
    await cdp('Page.addScriptToEvaluateOnNewDocument', {source: `(() => {
      const original = window.setTimeout;
      window.setTimeout = (callback, milliseconds, ...args) => original(callback,
        milliseconds === 10000 ? ${['body-stall', 'post-stall'].includes(options.mode) ? 1500 : 10000} : milliseconds === 90000 ? ${options.fastDeadline ? 1500 : 90000} : milliseconds === 100 ? 20 : milliseconds, ...args);
    })();`}, session);
    await cdp('Page.navigate', {url: origin + (options.generated ? '/generated.html' : '/fixture') + '#navigator-token=browser-test-token'}, session);
    const evaluate = async expression => {
      const result = await cdp('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true}, session);
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
      return result.result.value;
    };
    const text = () => evaluate("document.querySelector('#navigator-notice')?.textContent || ''");
    pageText = text;
    await until(() => evaluate(options.unavailable ? "!!document.querySelector('#navigator-notice')" : "document.documentElement?.dataset.ideaBridgeReady === 'true'"), 'bridge initialization');
    const click = async selector => {
      const point = await evaluate(`(() => {const r = document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
      await cdp('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1}, session);
      await cdp('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1}, session);
    };
    return {session, evaluate, text, click};
  }
  async function check(name, work) {
    await work(); checks++; console.log('PASS ' + name);
  }

  await check('class click, explicit method mapping, keyboard access, and fragment removal', async () => {
    const page = await open();
    assert.equal(await page.evaluate('location.hash'), '');
    assert.equal(await page.evaluate("fetch('https://offline-probe.invalid/').then(() => false, () => true)"), true);
    assert.equal(blockedExternalRequests.has('https://offline-probe.invalid/'), true);
    await page.click('#class-valid');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'class navigation');
    assert.deepEqual(scenario.requests[0], {className: 'demo.Valid'});
    await page.evaluate("document.querySelector('#method-valid').focus()");
    await cdp('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13}, page.session);
    await cdp('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13}, page.session);
    await until(() => scenario.requests.length === 2, 'keyboard navigation');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'keyboard completion');
    assert.deepEqual(scenario.requests[1], {className: 'demo.Valid', methodName: 'call', parameterTypes: ['java.lang.String']});
    assert.deepEqual(errors.get(page.session), []);
  });
  await check('sequence links use validated symbol keys and exact method signatures', async () => {
    const page = await open();
    await page.click('#sequence-method');
    await until(() => scenario.requests.length === 1, 'sequence method navigation');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'sequence completion');
    assert.deepEqual(scenario.requests[0], {className: 'demo.Valid', methodName: 'call', parameterTypes: ['java.lang.String']});
    await page.click('#sequence-invalid');
    assert.equal(scenario.requests.length, 1);
  });
  await check('legacy class diagrams retain declaration targets alongside copied file locations', async () => {
    const page = await open({map: JSON.stringify({
      Valid: {...validMap.Valid, file: 'sources/Valid.java', line: 10},
      Second: {file: 'sources/Second.java', line: 12}
    })});
    await page.click('#class-second');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'legacy class completion');
    await page.click('#method-valid');
    await until(() => scenario.requests.length === 2, 'legacy method navigation');
    assert.deepEqual(scenario.requests, [
      {className: 'demo.Second'},
      {className: 'demo.Valid', methodName: 'call', parameterTypes: ['java.lang.String']}
    ]);
  });
  await check('flowchart shape clicks keep the line when the user selects a candidate', async () => {
    const page = await open({flow: true, mode: 'ambiguous', map: JSON.stringify({
      Line: {className: 'demo.Worker', line: 22}, Invalid: {className: 'demo.Worker', line: '25'}
    })});
    assert.match(await page.text(), /1 项无效/);
    await page.click('#flow-invalid');
    assert.equal(scenario.requests.length, 0);
    await page.click('#flow-valid');
    await until(async () => (await page.text()).includes('请选择声明'), 'line candidate chooser');
    await page.click('#navigator-notice button');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'line candidate completion');
    assert.deepEqual(scenario.requests, [
      {className: 'demo.Worker', line: 22},
      {className: 'demo.Worker', line: 22, fileUrl: 'file:///demo/Worker.java'}
    ]);
  });
  if (process.env.FLOWCHART_RENDERER) {
    await check('generated Mermaid diagrams navigate repeated node IDs by click, keyboard and audit', async () => {
      const page = await open({generated: true});
      const first = '[data-source-symbol="1:Step"]';
      const second = '[data-source-symbol="2:Step"]';
      await until(() => page.evaluate(`!!document.querySelector('${first}.navigator-clickable')`), 'first rendered link');
      await cdp('Emulation.setDeviceMetricsOverride', {width: 600, height: 400, deviceScaleFactor: 1, mobile: false}, page.session);
      await page.evaluate("for (let zoom = 0; zoom < 16; zoom++) document.querySelector('#zoom-in').click()");
      await until(() => page.evaluate("document.querySelector('#viewport').hasAttribute('data-pannable')"), 'flowchart can pan at higher zoom');
      await page.evaluate(`document.querySelector('${first}').scrollIntoView({block: 'center', inline: 'center'})`);
      await page.click(first);
      await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'generated click completion');
      assert.deepEqual(scenario.requests[0], {className: 'demo.Worker', line: 22});
      await page.click('#fit');
      await page.click('#tabs [role=tab]:nth-child(2)');
      await until(() => page.evaluate(`!!document.querySelector('${second}[tabindex="0"]')`), 'second rendered link');
      await page.evaluate(`document.querySelector('${second}').focus()`);
      await cdp('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13}, page.session);
      await until(() => scenario.requests.length === 2, 'generated keyboard navigation');
      await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'generated keyboard completion');
      assert.deepEqual(scenario.requests[1], {className: 'demo.Worker', line: 42});
      await page.click('#navigator-audit');
      await until(async () => (await page.text()).includes('2/2 成功'), 'generated audit');
      assert.deepEqual(scenario.requests.slice(2), [{className: 'demo.Worker', line: 22}, {className: 'demo.Worker', line: 42}]);
      assert.deepEqual(errors.get(page.session), []);
    });
  }
  await check('malformed JSON is reported while server mappings remain usable', async () => {
    const page = await open({map: '{broken'});
    assert.match(await page.text(), /映射.*无效/);
    await page.click('#class-valid');
    await until(() => scenario.requests.length === 1, 'fallback mapping');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'fallback completion');
    assert.deepEqual(errors.get(page.session), []);
  });
  await check('invalid mapping entries and methods are isolated', async () => {
    const page = await open({map: JSON.stringify({...validMap, Broken: null, InvalidClass: {className: 42},
        Valid: {...validMap.Valid, methods: {...validMap.Valid.methods, '+bad()': null}}}), symbols: {Second: 'demo.Second', Invalid: null}});
    assert.match(await page.text(), /4 项无效/);
    await page.click('#method-valid');
    await until(() => scenario.requests.length === 1, 'valid neighboring method');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'mapped method completion');
    assert.deepEqual(scenario.requests[0].parameterTypes, ['java.lang.String']);
    assert.deepEqual(errors.get(page.session), []);
  });
  await check('response-body timeout cancels the task and releases busy state', async () => {
    const page = await open({mode: 'body-stall'});
    await page.click('#class-valid');
    await until(async () => (await page.text()).includes('超时'), 'body timeout');
    await until(() => scenario.cancelled.length === 1, 'task cancellation');
    scenario.mode = 'opened'; await page.click('#class-valid');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'retry after timeout');
    assert.equal(scenario.requests.length, 2);
  });
  await check('POST timeout cancels its client-assigned ID before the response arrives', async () => {
    const page = await open({mode: 'post-stall'});
    await page.click('#class-valid');
    await until(async () => (await page.text()).includes('超时'), 'POST timeout');
    await until(() => scenario.cancelled.length === 1, 'cancel unacknowledged POST');
    scenario.mode = 'opened'; await page.click('#class-valid');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'retry POST');
    assert.equal(scenario.requests.length, 2);
  });
  await check('overall navigation deadline stops an audit and cancels its current task', async () => {
    const page = await open({mode: 'pending', fastDeadline: true});
    await page.click('#navigator-audit');
    await until(async () => (await page.text()).includes('检查中断') && (await page.text()).includes('超时'), 'audit deadline');
    await until(() => scenario.cancelled.length === 1, 'deadline cancellation');
    assert.equal(scenario.requests.length, 1);
    assert.equal(await page.evaluate("document.querySelector('#navigator-audit').disabled"), false);
  });
  await check('closed service stops an audit before its next target', async () => {
    const page = await open({mode: 'closed'});
    await page.click('#navigator-audit');
    await until(async () => (await page.text()).includes('检查中断'), 'closed-service audit');
    await until(() => scenario.cancelled.length === 1, 'closed-service cancellation');
    assert.equal(scenario.requests.length, 1);
  });
  await check('leaving a page cancels its current audit through keepalive DELETE', async () => {
    const page = await open({mode: 'pending'});
    await page.click('#navigator-audit');
    await until(() => scenario.requests.length === 1, 'audit submission');
    await cdp('Page.navigate', {url: 'about:blank'}, page.session);
    await until(() => scenario.cancelled.length === 1, 'pagehide cancellation');
    assert.equal(scenario.requests.length, 1);
  });
  await check('a single SVG audit exports actual navigation results without its token', async () => {
    const page = await open();
    await page.click('#navigator-audit');
    await until(async () => (await page.text()).includes('3/3 成功'), 'single SVG audit');
    await page.click('#navigator-notice button');
    await until(async () => (await readdir(temporary)).includes('class-diagram-navigation-audit.json'), 'audit download');
    const contents = await readFile(join(temporary, 'class-diagram-navigation-audit.json'), 'utf8');
    const report = JSON.parse(contents);
    assert.equal(report.total, 3); assert.equal(report.opened, 3); assert.equal(report.diagrams, 1);
    assert.equal(contents.includes('browser-test-token'), false);
    assert.deepEqual(errors.get(page.session), []);
  });
  await check('an ambiguous declaration is selected using its exact candidate signature and location', async () => {
    const page = await open({mode: 'ambiguous'});
    await page.click('#class-valid');
    await until(async () => (await page.text()).includes('请选择声明'), 'candidate chooser');
    await page.click('#navigator-notice button');
    await until(async () => (await page.text()).includes('已在 IDEA 打开'), 'selected declaration');
    assert.deepEqual(scenario.requests[1], {className: 'demo.Valid', methodName: 'call',
      parameterTypes: ['java.lang.String'], fileUrl: 'jar://dependency!/demo/Valid.class'});
    assert.deepEqual(errors.get(page.session), []);
  });
  await check('multi-tab audit visits every diagram and restores the original tab', async () => {
    const page = await open({tabs: true});
    await page.click('#navigator-audit');
    await until(async () => (await page.text()).includes('6/6 成功'), 'two-diagram audit');
    assert.equal(scenario.requests.length, 6);
    assert.equal(await page.evaluate("document.querySelector('[role=tab][aria-selected=true]').textContent"), 'Second');
    assert.deepEqual(errors.get(page.session), []);
  });
  await check('failed initialization is visible and never submits a navigation', async () => {
    const page = await open({unavailable: true});
    assert.match(await page.text(), /403/);
    await page.click('#class-valid');
    await until(async () => (await page.text()).includes('服务不可用'), 'initialization failure');
    assert.equal(scenario.requests.length, 0);
    assert.deepEqual(errors.get(page.session), []);
  });
  assert.deepEqual([...blockedExternalRequests], ['https://offline-probe.invalid/']);
  console.log(`PASS ${checks} real-Chrome browser scenarios`);
} finally {
  if (chrome && chrome.exitCode === null && chrome.signalCode === null) {
    const exited = new Promise(resolve => chrome.once('exit', resolve));
    chrome.kill('SIGTERM');
    await Promise.race([exited, delay(2000)]);
    if (chrome.exitCode === null && chrome.signalCode === null) {
      chrome.kill('SIGKILL');
      await Promise.race([exited, delay(2000)]);
    }
  }
  for (const connection of connections) connection.destroy();
  if (http) await new Promise(resolve => http.close(resolve));
  await rm(temporary, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
}
