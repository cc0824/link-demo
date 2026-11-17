'use strict';
(() => {
  const $ = (selector) => document.querySelector(selector);
  const channels = {wechat: '微信好友', moments: '朋友圈', other: '其他'};
  const statuses = {ACTIVE: '在售', OFF_SHELF: '已下架', DELETED: '已删除'};
  let token = 'demo-only-token';
  let editingId;
  let busy = false;
  const feedback = (message, error = false) => {
    $('#feedback').textContent = message;
    $('#feedback').classList.toggle('error', error);
    document.querySelectorAll('.dialog-feedback').forEach(element => element.remove());
    const dialog = document.querySelector('dialog[open]');
    if (error && dialog) {
      const alert = node('p', message, 'dialog-feedback');
      alert.setAttribute('role', 'alert');
      dialog.querySelector('form').append(alert);
    }
  };
  const node = (tag, text, className) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    if (className) element.className = className;
    return element;
  };
  function button(text, handler, className = 'quiet') {
    const element = node('button', text, className);
    element.type = 'button';
    element.addEventListener('click', handler);
    return element;
  }
  function link(text, url) {
    const element = node('a', text, 'quiet');
    element.href = url;
    element.target = '_blank';
    element.rel = 'noopener';
    return element;
  }
  async function api(path, method = 'GET', body) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12000);
    try {
      const response = await fetch(path, {
        method, headers: {Authorization: `Bearer ${token}`, ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
        body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, cache: 'no-store'
      });
      if (response.status === 204) return null;
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        if (response.status === 401 && !$('#credential-dialog').open) $('#credential-dialog').showModal();
        const retry = response.headers.get('Retry-After');
        throw new Error((data.message || `请求失败（${response.status}）`) + (retry ? `，请 ${retry} 秒后重试。` : ''));
      }
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('请求超时。操作可能已执行，请先刷新数据确认，再决定是否重试。');
      if (error instanceof TypeError) throw new Error('无法连接服务，请确认服务已启动，然后刷新。');
      throw error;
    } finally { clearTimeout(timer); }
  }
  async function run(trigger, task) {
    if (busy) { feedback('正在处理上一项操作，请稍候。'); return; }
    busy = true;
    if (trigger) { trigger.disabled = true; trigger.setAttribute('aria-busy', 'true'); }
    try { await task(); }
    catch (error) { feedback(error.message, true); }
    finally {
      busy = false;
      if (trigger) { trigger.disabled = false; trigger.removeAttribute('aria-busy'); }
    }
  }
  async function confirmAction(message) {
    const dialog = $('#confirm-dialog');
    if (dialog.open || busy) return false;
    $('#confirm-message').textContent = message;
    dialog.returnValue = 'cancel';
    dialog.showModal();
    return new Promise(resolve => dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), {once: true}));
  }
  function title(product) { return product.title?.trim() || `精选好物 · ${product.id ?? product.productId}`; }
  async function copy(text, trigger) {
    await run(trigger, async () => {
      try { await navigator.clipboard.writeText(text); feedback('链接已复制。'); }
      catch { feedback('浏览器未允许自动复制，请选中链接文本后按 Ctrl+C（Mac 使用 ⌘C）。', true); }
    });
  }
  function urlField(labelText, url) {
    const label = node('label', labelText);
    const input = node('input'); input.value = url; input.readOnly = true;
    input.addEventListener('click', () => input.select()); label.append(input);
    const copyButton = button(`复制${labelText}`, event => copy(url, event.currentTarget));
    label.append(copyButton); return label;
  }
  function renderResult(item) {
    const result = $('#link-result'); result.replaceChildren();
    result.append(node('p', 'READY TO SHARE', 'eyebrow'), node('h3', '分享入口已就绪'),
      urlField('短链', item.shortUrl), urlField('卡片链接', item.shareUrl));
    const actions = node('div', undefined, 'actions');
    actions.append(link('预览卡片 ↗', item.shareUrl), link('访问商品 ↗', item.shortUrl));
    result.append(actions, node('p', '预览卡片不计数；访问商品后，请刷新下方统计。', 'muted'));
  }
  async function loadProducts() {
    const products = await api('/api/demo/products');
    const selected = $('#product-select').value;
    $('#product-select').replaceChildren(); $('#product-list').replaceChildren();
    for (const product of products) {
      const option = node('option', `${product.id} · ${title(product)}（${statuses[product.status]}）`);
      option.value = product.id; option.disabled = product.status !== 'ACTIVE'; $('#product-select').append(option);
      const card = node('article', undefined, 'product-card');
      const top = node('div', undefined, 'product-top');
      const img = node('img'); img.src = `/media/products/${product.id}.png`; img.alt = `商品 ${product.id} 示意图`;
      const info = node('div');
      info.append(node('span', `${product.id} / ${statuses[product.status]}`, `badge ${product.status === 'DELETED' ? 'deleted' : product.status === 'OFF_SHELF' ? 'off' : ''}`), node('h3', title(product)), node('p', product.description, 'muted'));
      top.append(img, info);
      const actions = node('div', undefined, 'product-actions');
      if (product.status === 'DELETED') {
        actions.append(button('恢复商品', event => run(event.currentTarget, async () => {
          await api(`/api/demo/products/${product.id}/restore`, 'POST');
          await reloadCatalog(); feedback(`商品 ${product.id} 已恢复，原有短链与统计已保留。`);
        }), 'primary'));
      } else {
        if (product.status === 'ACTIVE') actions.append(button('选此商品分享', () => {
          $('#product-select').value = product.id; $('#sharing').scrollIntoView(); $('#product-select').focus({preventScroll: true});
          feedback(`已选择商品 ${product.id}，填写分享人和渠道后生成链接。`);
        }, 'primary'));
        actions.append(link('商品详情 ↗', `/products/${product.id}`), button('修改标题', () => {
          editingId = product.id; $('#title-input').value = product.title; $('#title-dialog').showModal();
        }), button(product.status === 'ACTIVE' ? '下架' : '上架', event => run(event.currentTarget, async () => {
          await api(`/api/demo/products/${product.id}`, 'PATCH', {status: product.status === 'ACTIVE' ? 'OFF_SHELF' : 'ACTIVE'});
          await reloadCatalog(); feedback(`商品 ${product.id} 状态已更新，可通过已有链接查看效果。`);
        })), button('删除', async event => {
          const trigger = event.currentTarget;
          if (!await confirmAction(`删除商品 ${product.id}？已有链接将展示商品不可用页面；短链和访问统计会保留，可随时恢复商品。`)) return;
          await run(trigger, async () => { await api(`/api/demo/products/${product.id}`, 'DELETE'); await reloadCatalog(); feedback(`商品 ${product.id} 已删除，可通过原短链查看降级页面。`); });
        }, 'danger'));
      }
      card.append(top, actions); $('#product-list').append(card);
    }
    const active = products.find(p => p.status === 'ACTIVE' && String(p.id) === selected) || products.find(p => p.status === 'ACTIVE');
    $('#product-select').value = active ? String(active.id) : '';
    if (!active) {
      const option = node('option', '请先上架或恢复一件商品'); option.value = ''; $('#product-select').prepend(option); $('#product-select').value = '';
    }
  }
  function emptyRow(target, text, columns = 4) {
    const row = node('tr'), cell = node('td', text, 'empty'); cell.colSpan = columns; row.append(cell); target.append(row);
  }
  async function loadLinks() {
    const items = await api('/api/demo/links');
    const history = $('#link-history'); history.replaceChildren();
    const filter = $('#stats-key'), previous = filter.value;
    filter.replaceChildren(node('option', '全部短链')); filter.firstChild.value = '';
    for (const item of items) {
      const row = node('tr'), product = node('td'), attribution = node('td'), actions = node('td');
      product.append(node('p', `${item.productId} · ${item.productTitle?.trim() || '精选好物'} / ${statuses[item.productStatus]}`), node('p', item.key, 'code'));
      attribution.append(node('p', item.sharerId), node('p', channels[item.channel], 'muted'));
      const actionGroup = node('div', undefined, 'actions');
      actionGroup.append(link('卡片 ↗', item.shareUrl), link('访问 ↗', item.shortUrl), button('链接 / 复制', () => { renderResult(item); $('#link-result').scrollIntoView({block: 'center'}); }), button('查看统计', event => run(event.currentTarget, async () => {
        filter.value = item.key; await loadStats(); $('#statistics').scrollIntoView(); feedback('已按该短链查询统计，保留当前日期和其他筛选条件。');
      })));
      actions.append(actionGroup);
      row.append(product, attribution, node('td', new Date(item.createdAt).toLocaleString('zh-CN', {timeZone: 'Asia/Hong_Kong', hour12: false})), actions); history.append(row);
      const option = node('option', `${item.productId} / ${item.sharerId} / ${item.key}`); option.value = item.key; filter.append(option);
    }
    if ([...filter.options].some(option => option.value === previous)) filter.value = previous;
    if (!items.length) emptyRow(history, '还没有分享记录。生成第一条链接，开始演示。');
  }
  async function reloadCatalog() { await loadProducts(); await loadLinks(); }
  async function loadStats() {
    $('#pv').textContent = '—'; $('#uv').textContent = '—'; $('#stats-groups').replaceChildren(); $('#stats-updated').textContent = '正在查询…';
    try {
      const values = new FormData($('#stats-form'));
      const from = values.get('from'), to = values.get('to');
      const end = new Date(`${to}T00:00:00Z`), start = new Date(`${from}T00:00:00Z`);
      const days = (end - start) / 86400000 + 1;
      if (!Number.isFinite(days) || days < 1 || days > 31) throw new Error('请选择有效日期范围：开始日不能晚于结束日，且最多包含 31 天。');
      end.setUTCDate(end.getUTCDate() + 1);
      const params = new URLSearchParams({from, to: end.toISOString().slice(0, 10)});
      for (const name of ['channel', 'sharerId', 'key', 'groupBy']) if (values.get(name)) params.set(name, values.get(name));
      const data = await api(`/api/stats?${params}`);
      $('#pv').textContent = data.total.pv; $('#uv').textContent = data.total.uv;
      $('#stats-updated').textContent = `${from} 至 ${to}（含） · 香港时间。更新于 ${new Date().toLocaleTimeString('zh-CN', {timeZone: 'Asia/Hong_Kong', hour12: false})}`;
      for (const group of data.groups) {
        const row = node('tr');
        row.append(node('td', channels[group.channel] || '全部渠道'), node('td', group.sharerId || '全部分享人'), node('td', group.pv), node('td', group.uv)); $('#stats-groups').append(row);
      }
      if (!data.groups.length) emptyRow($('#stats-groups'), data.total.pv ? '当前未选择分组，请查看上方汇总。' : '所选条件下还没有有效访问。');
    } catch (error) { $('#stats-updated').textContent = '查询未完成，请检查筛选条件或连接后重试。'; throw error; }
  }
  async function connect() { await reloadCatalog(); await loadStats(); feedback('工作台已连接。选择商品，开始一次分享。'); }
  $('#create-link-form').addEventListener('submit', event => {
    event.preventDefault();
    run(event.submitter, async () => {
      const values = new FormData(event.target);
      const item = await api('/api/links', 'POST', {productId: Number(values.get('productId')), sharerId: values.get('sharerId'), channel: values.get('channel')});
      renderResult(item); feedback(item.reused ? '已复用已有分享链接。' : '分享链接已生成。'); await loadLinks();
    });
  });
  $('#stats-form').addEventListener('submit', event => { event.preventDefault(); run(event.submitter, async () => { await loadStats(); feedback('统计已更新。'); }); });
  $('#title-form').addEventListener('submit', event => {
    event.preventDefault(); run(event.submitter, async () => {
      await api(`/api/demo/products/${editingId}`, 'PATCH', {title: $('#title-input').value});
      $('#title-dialog').close(); await reloadCatalog(); feedback(`商品 ${editingId} 标题已保存。`);
    });
  });
  $('#credential-button').addEventListener('click', () => {
    document.querySelectorAll('.dialog-feedback').forEach(element => element.remove());
    $('#credential-dialog').showModal();
  });
  $('#credential-form').addEventListener('submit', event => {
    event.preventDefault(); run(event.submitter, async () => {
      token = $('#token-input').value; $('#token-input').value = '';
      await connect(); $('#credential-dialog').close();
    });
  });
  document.querySelectorAll('[data-close-dialog]').forEach(element => element.addEventListener('click', () => document.getElementById(element.dataset.closeDialog).close()));
  $('#refresh-products').addEventListener('click', event => run(event.currentTarget, async () => { await reloadCatalog(); feedback('商品与分享记录已刷新。'); }));
  $('#refresh-links').addEventListener('click', event => run(event.currentTarget, async () => { await loadLinks(); feedback('分享记录已刷新。'); }));
  $('#reset-button').addEventListener('click', async event => {
    const trigger = event.currentTarget;
    if (!await confirmAction('确定清空全部短链与访问统计，并恢复两件商品的初始状态？此操作无法撤销。')) return;
    await run(trigger, async () => {
      await api('/api/demo/reset', 'POST');
      $('#link-result').replaceChildren(node('h3', '准备好开始新的分享'), node('p', '演示已重置，请重新生成链接。'));
      await connect(); feedback('演示已重置，原短链与统计已清空。');
    });
  });
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Hong_Kong', year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date());
  const part = type => parts.find(item => item.type === type).value;
  const today = `${part('year')}-${part('month')}-${part('day')}`;
  $('#stats-form [name=from]').value = today; $('#stats-form [name=to]').value = today;
  run(null, connect);
})();
