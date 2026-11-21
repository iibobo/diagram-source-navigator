(() => {
  'use strict';
  const token = new URLSearchParams(location.hash.slice(1)).get('navigator-token');
  history.replaceState(null, '', location.pathname + location.search);
  if (!token) return;
  const headers = {Authorization: 'Bearer ' + token, 'Content-Type': 'application/json'};
  const HTTP_TIMEOUT = 10000, NAVIGATION_TIMEOUT = 90000;
  let symbols = Object.create(null), busy = false, pageClosed = false, activeRequest = null;
  let invalidMappings = 0;
  const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const classNameValid = value => typeof value === 'string' && value.length <= 500 && /^[\p{ID_Start}_$][\p{ID_Continue}.$]*$/u.test(value);
  const methodNameValid = value => typeof value === 'string' && value.length <= 200 && /^[\p{ID_Start}_$][\p{ID_Continue}$]*$/u.test(value);
  function readSourceMap() {
    const result = Object.create(null);
    let parsed;
    try { parsed = JSON.parse(document.getElementById('source-map')?.textContent || '{}'); }
    catch { invalidMappings++; return result; }
    if (!object(parsed)) { invalidMappings++; return result; }
    for (const [name, entry] of Object.entries(parsed)) {
      if (!object(entry) || (entry.className !== undefined && !classNameValid(entry.className))) {
        invalidMappings++; continue;
      }
      const clean = {className: entry.className, methods: Object.create(null)};
      if (entry.methods !== undefined && !object(entry.methods)) invalidMappings++;
      else for (const [label, method] of Object.entries(entry.methods || {})) {
        if (!object(method) || !methodNameValid(method.methodName) || !Array.isArray(method.parameterTypes)
            || method.parameterTypes.length > 64 || method.parameterTypes.some(type => typeof type !== 'string' || type.length > 500)
            || (method.className !== undefined && !classNameValid(method.className))) {
          invalidMappings++; continue;
        }
        clean.methods[label] = {methodName: method.methodName, parameterTypes: method.parameterTypes, className: method.className};
      }
      result[name] = clean;
    }
    return result;
  }
  const sourceMap = readSourceMap();
  const normalize = value => (value.trim().match(/^[+\-#~]?\s*([\w$]+\([^)]*\))/)?.[1] || value.trim()).replace(/\s/g, '');
  const style = document.createElement('style');
  style.textContent = '.navigator-clickable{cursor:pointer}.navigator-clickable:hover{text-decoration:underline}' +
    '#navigator-notice{position:fixed;bottom:14px;left:14px;z-index:2147483647;background:white;color:#292735;' +
    'border:1px solid #8055ba;border-radius:6px;padding:10px 14px;max-width:min(700px,85vw);font:13px Arial,sans-serif;' +
    'box-shadow:0 3px 18px #0002;max-height:45vh;overflow:auto}' +
    '#navigator-notice.navigator-success{border-color:#16a34a;overflow-wrap:anywhere}' +
    '#navigator-notice .navigator-success-icon{display:inline-flex;align-items:center;justify-content:center;' +
    'width:16px;height:16px;border:1.5px solid #16a34a;border-radius:50%;color:#16a34a;' +
    'font-weight:bold;line-height:1;margin-right:7px;vertical-align:middle;background:#f0fdf4}' +
    '#navigator-notice button{display:block;margin:5px 0;padding:5px 8px;max-width:100%;overflow-wrap:anywhere;text-align:left}';
  document.head.append(style);
  const notice = document.createElement('aside');
  notice.id = 'navigator-notice'; notice.setAttribute('aria-live', 'polite');
  let timer;
  function message(text, sticky = false, success = false) {
    clearTimeout(timer); notice.classList.toggle('navigator-success', success);
    notice.replaceChildren();
    if (success) {
      const icon = document.createElement('span'); icon.className = 'navigator-success-icon';
      icon.textContent = '✓'; icon.setAttribute('aria-hidden', 'true'); notice.append(icon);
    }
    notice.append(document.createTextNode(text));
    if (!notice.isConnected) document.body.append(notice);
    if (!sticky) timer = setTimeout(() => notice.remove(), 6000);
  }
  async function api(path, options = {}) {
    const controller = new AbortController(), upstream = options.signal;
    const abort = () => controller.abort(upstream.reason);
    if (upstream?.aborted) abort();
    else upstream?.addEventListener('abort', abort, {once: true});
    const timeout = setTimeout(() => controller.abort(new Error('本地导航接口请求超时，请重试')), HTTP_TIMEOUT);
    try {
      const response = await fetch(path, {...options, headers: {...headers, ...options.headers}, signal: controller.signal});
      if (!response.ok) throw new Error('本地导航接口返回 ' + response.status + '；请从 IDEA 菜单重新打开页面');
      return await response.json();
    } finally {
      clearTimeout(timeout);
      upstream?.removeEventListener('abort', abort);
    }
  }
  function cancelRequest(operation) {
    if (operation.id && !operation.finished && !operation.cancellationSent) {
      operation.cancellationSent = true;
      void api('/api/result/' + encodeURIComponent(operation.id), {method: 'DELETE', keepalive: true}).catch(() => {});
    }
  }
  function pause(milliseconds, signal) {
    return new Promise((resolve, reject) => {
      if (signal?.aborted) { reject(signal.reason); return; }
      const abort = () => { clearTimeout(timeout); reject(signal.reason); };
      const timeout = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, milliseconds);
      signal?.addEventListener('abort', abort, {once: true});
    });
  }
  function signature(text) {
    const value = text.trim().replace(/^[+\-#~]\s*/, '');
    const match = value.match(/^([\w$]+)\((.*)\)/);
    if (!match) return null;
    let parameters = [], depth = 0, start = 0;
    for (let i = 0; i < match[2].length; i++) {
      if (match[2][i] === '<') depth++;
      if (match[2][i] === '>') depth--;
      if (match[2][i] === ',' && depth === 0) { parameters.push(match[2].slice(start, i).trim()); start = i + 1; }
    }
    if (match[2].trim()) parameters.push(match[2].slice(start).trim());
    if (parameters.some(p => p === '...' || p.includes(':'))) parameters = null;
    return {methodName: match[1], parameterTypes: parameters};
  }
  async function requestNavigation(target) {
    if (pageClosed) throw new Error('页面会话已关闭，请从 IDEA 菜单重新打开');
    const operation = {controller: new AbortController(), id: crypto.randomUUID(), finished: false, cancellationSent: false};
    activeRequest = operation;
    const timeout = setTimeout(() => operation.controller.abort(new Error('源码定位超时，请等待 IDEA 索引完成后重试')), NAVIGATION_TIMEOUT);
    const signal = operation.controller.signal;
    try {
      const accepted = await api('/api/navigate', {method: 'POST', body: JSON.stringify(target), headers: {'X-Navigator-Request-ID': operation.id}, signal});
      if (!object(accepted) || typeof accepted.requestId !== 'string' || accepted.requestId !== operation.id) throw new Error('导航接口返回了无效任务');
      operation.id = accepted.requestId;
      let result = {status: 'pending'};
      while (result.status === 'pending') {
        await pause(100, signal);
        result = await api('/api/result/' + encodeURIComponent(operation.id), {signal});
        if (!object(result) || typeof result.status !== 'string') throw new Error('导航接口返回了无效结果');
      }
      if (['timeout', 'timed_out', 'cancelled', 'closed', 'expired'].includes(result.status))
        throw new Error(result.message || '导航会话已超时或关闭，请从 IDEA 菜单重新打开');
      operation.finished = true;
      return result;
    } finally {
      clearTimeout(timeout);
      cancelRequest(operation);
      if (activeRequest === operation) activeRequest = null;
    }
  }
  async function navigate(target) {
    if (busy) return;
    busy = true;
    message('正在 IDEA 中定位…（建立索引时会等待）', true);
    try {
      if (!await ready) throw new Error('导航服务不可用，请从 IDEA 菜单重新打开');
      const result = await requestNavigation(target);
      if (result.status === 'opened') message('已在 IDEA 打开：' + (result.target || target.className).split(' — ')[0], false, true);
      else if (result.status === 'ambiguous') {
        message('请选择声明位置或完整方法签名：', true);
        for (const candidate of Array.isArray(result.candidates) ? result.candidates : []) {
          if (!object(candidate) || typeof candidate.label !== 'string' || !object(candidate.target)) continue;
          const button = document.createElement('button'); button.textContent = candidate.label;
          button.onclick = () => navigate(candidate.target); notice.append(button);
        }
        const close = document.createElement('button'); close.textContent = '取消';
        close.onclick = () => notice.remove(); notice.append(close);
      } else message(result.message || '定位未完成：' + result.status);
    } catch (error) { message(error.message); }
    finally { busy = false; }
  }
  function targetOf(element) {
    const text = element?.closest?.('text');
    const node = text?.closest('g.node');
    const match = node?.id.match(/(?:^|-)classId-(.+)-\d+$/);
    if (!match) return null;
    const name = match[1], entry = sourceMap[name];
    const className = entry?.className || symbols[name];
    if (!className) return null;
    if (text.closest('.methods-group')) {
      const label = normalize(text.textContent);
      const mapped = Object.entries(entry?.methods || {}).find(([key]) => normalize(key) === label)?.[1];
      if (mapped?.methodName && Array.isArray(mapped.parameterTypes)) return {
        className: mapped.className || className, methodName: mapped.methodName, parameterTypes: mapped.parameterTypes
      };
      const method = signature(text.textContent);
      return method && {className, ...method};
    }
    return text.textContent.trim() === name ? {className} : null;
  }

  // Capture before existing idea:// anchors; leave all viewer controls and geometry untouched.
  document.addEventListener('click', event => {
    const target = targetOf(event.target);
    if (!target) return;
    event.preventDefault(); event.stopImmediatePropagation(); navigate(target);
  }, true);
  function mark() {
    for (const text of document.querySelectorAll('svg g.node text')) {
      if (targetOf(text)) {
        text.classList.add('navigator-clickable');
        text.setAttribute('tabindex', '0'); text.setAttribute('role', 'link');
        text.setAttribute('aria-label', text.textContent + '，在 IDEA 定位');
      }
    }
  }
  document.addEventListener('keydown', event => {
    if ((event.key === 'Enter' || event.key === ' ') && targetOf(event.target)) {
      event.preventDefault(); navigate(targetOf(event.target));
    }
  });
  const auditButton = document.createElement('button');
  auditButton.id = 'navigator-audit'; auditButton.textContent = '检查全部跳转';
  auditButton.title = '逐张渲染类图，在 IDEA 中实际打开每个类和方法，并导出验证记录';
  document.querySelector('.controls')?.append(auditButton);
  auditButton.onclick = async () => {
    if (busy) return;
    busy = true; auditButton.disabled = true;
    const results = [], tabs = [...document.querySelectorAll('#tabs [role="tab"]')];
    const original = tabs.findIndex(tab => tab.getAttribute('aria-selected') === 'true');
    try {
      if (!await ready) throw new Error('导航服务不可用，请从 IDEA 菜单重新打开');
      const diagrams = tabs.length ? tabs : [...document.querySelectorAll('svg')].filter(svg => svg.querySelector('g.node'));
      if (!diagrams.length) throw new Error('没有可检查的类图');
      for (let index = 0; index < diagrams.length; index++) {
        if (pageClosed) throw new Error('页面会话已关闭');
        const previous = document.querySelector('#canvas svg');
        const current = !tabs.length || tabs[index].getAttribute('aria-selected') === 'true';
        if (tabs.length) tabs[index].click();
        const deadline = Date.now() + 30000;
        while (tabs.length && (!document.querySelector('#canvas svg') || (!current && document.querySelector('#canvas svg') === previous)) && Date.now() < deadline) {
          if (pageClosed) throw new Error('页面会话已关闭');
          await pause(100);
        }
        const label = tabs.length ? tabs[index].textContent : '类图 ' + (index + 1);
        const svg = tabs.length ? document.querySelector('#canvas svg') : diagrams[index];
        if (!svg || (!current && svg === previous)) throw new Error('类图渲染超时：' + label);
        for (const text of svg.querySelectorAll('g.node text')) {
          if (pageClosed) throw new Error('页面会话已关闭');
          const target = targetOf(text);
          const isMethod = text.closest('.methods-group');
          const nodeName = text.closest('g.node')?.id.match(/(?:^|-)classId-(.+)-\d+$/)?.[1];
          if (!isMethod && text.textContent.trim() !== nodeName) continue;
          message('正在验证第 ' + (index + 1) + ' 张图：' + text.textContent + '（已完成 ' + results.length + ' 项）', true);
          const result = target ? await requestNavigation(target) : {status: 'unmapped'};
          results.push({diagram: label, label: text.textContent, request: target, result});
        }
      }
      const report = {createdAt: new Date().toISOString(), diagrams: diagrams.length, total: results.length,
        opened: results.filter(row => row.result.status === 'opened').length, results};
      message('全部跳转检查完成：' + report.opened + '/' + report.total + ' 成功，覆盖 ' + report.diagrams + ' 张图。', true);
      const download = document.createElement('button'); download.textContent = '下载跳转检查报告';
      download.onclick = () => {
        const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], {type: 'application/json'}));
        const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'class-diagram-navigation-audit.json'; anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      };
      notice.append(download);
    } catch (error) { message('检查中断：' + error.message, true); }
    finally { if (!pageClosed && original >= 0) tabs[original].click(); busy = false; auditButton.disabled = false; }
  };
  const observer = new MutationObserver(mark);
  observer.observe(document.body, {childList: true, subtree: true});
  window.addEventListener('pagehide', () => {
    pageClosed = true;
    observer.disconnect();
    clearTimeout(timer);
    if (activeRequest) {
      activeRequest.controller.abort(new Error('页面会话已关闭'));
      cancelRequest(activeRequest);
    }
  });
  const ready = api('/api/symbol-map').then(map => {
    if (!object(map)) throw new Error('导航服务返回了无效源码映射');
    for (const [name, value] of Object.entries(map)) {
      if (classNameValid(value)) symbols[name] = value;
      else invalidMappings++;
    }
    document.documentElement.dataset.ideaBridgeReady = 'true'; mark();
    if (invalidMappings) message('源码映射有 ' + invalidMappings + ' 项无效，已跳过；其余有效映射仍可使用', true);
    return true;
  }).catch(error => { message(error.message, true); return false; });
})();
