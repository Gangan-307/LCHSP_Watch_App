/* Offline subset of Lucide icons used by the prototype. https://lucide.dev */
const ICONS = {
  home: '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/><polyline points="9 22 9 12 15 12 15 22"/>',
  watch: '<circle cx="12" cy="12" r="6"/><polyline points="12 10 12 12 13.5 13.5"/><path d="m16.13 7.66-.81-4.05A2 2 0 0 0 13.36 2h-2.72a2 2 0 0 0-1.96 1.61l-.81 4.05M7.88 16.34l.8 4.05A2 2 0 0 0 10.64 22h2.72a2 2 0 0 0 1.96-1.61l.8-4.05"/>',
  'refresh-cw': '<path d="M21 12a9 9 0 0 1-15.54 6.18L3 16"/><path d="M3 21v-5h5"/><path d="M3 12A9 9 0 0 1 18.54 5.82L21 8"/><path d="M21 3v5h-5"/>',
  bell: '<path d="M10.27 21a2 2 0 0 0 3.46 0"/><path d="M3.26 15.33A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.67C19.41 13.96 18 12.5 18 8A6 6 0 0 0 6 8c0 4.5-1.41 5.96-2.74 7.33"/>',
  'settings-2': '<path d="M20 7h-9M14 17H5"/><circle cx="17" cy="17" r="3"/><circle cx="7" cy="7" r="3"/>',
  check: '<path d="m20 6-11 11-5-5"/>',
  'battery-charging': '<path d="M15 7h1a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-2M6 7H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h1M22 11v2"/><path d="m11 7-3 5h4l-3 5"/>',
  'map-pin': '<path d="M20 10c0 5-8 12-8 12S4 15 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="3"/>',
  activity: '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
  footprints: '<path d="M4 16v-2.38C4 11.62 5.5 10 7.35 10h.3C9.5 10 11 11.62 11 13.62V16a4 4 0 0 1-7 0ZM13 8V5.62C13 3.62 14.5 2 16.35 2h.3C18.5 2 20 3.62 20 5.62V8a4 4 0 0 1-7 0Z"/><path d="M4 16c0 3 1.5 6 4 6s3-3 3-6M13 8c0 3 1.5 6 4 6s3-3 3-6"/>',
  flame: '<path d="M12 22c4.42 0 8-3.58 8-8 0-3-1.5-5-3-7 .09 2-1 3-2 3-1.5 0-2-1-2-3 0-2.5-1.5-4.5-3-5 0 3-2 5-3 6.5C6 10 4 12 4 15a8 8 0 0 0 8 7Z"/><path d="M9 18c0 2 1.34 4 3 4s3-2 3-4c0-1.5-1-2.5-2-3.5 0 1.5-.5 2.5-1.5 2.5S10 16 10 15c-1 1-1 2-1 3Z"/>',
  route: '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>',
  'locate-fixed': '<line x1="2" x2="5" y1="12" y2="12"/><line x1="19" x2="22" y1="12" y2="12"/><line x1="12" x2="12" y1="2" y2="5"/><line x1="12" x2="12" y1="19" y2="22"/><circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="3"/>',
  'cloud-sun': '<path d="M12 2v2M5.22 5.22l1.42 1.42M20 12h2M17.36 6.64l1.42-1.42"/><path d="M17.5 17H9a5 5 0 1 1 4.9-6H15a4 4 0 0 1 2.5 7Z"/>',
  clock: '<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15 14"/>',
  'music-2': '<circle cx="8" cy="18" r="3"/><circle cx="18" cy="16" r="3"/><path d="M11 18V5l10-2v13M11 9l10-2"/>',
  image: '<rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.09-3.09a2 2 0 0 0-2.83 0L6 21"/>',
  'message-square': '<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z"/>',
  'shield-check': '<path d="M20 13c0 5-3.5 7.5-8 9-4.5-1.5-8-4-8-9V5l8-3 8 3Z"/><path d="m9 12 2 2 4-4"/>',
  bluetooth: '<path d="m6.5 6.5 11 11L12 22V2l5.5 4.5-11 11"/>',
  radio: '<path d="M4.9 19.1a10 10 0 0 1 0-14.2M7.76 16.24a6 6 0 0 1 0-8.49M19.1 4.9a10 10 0 0 1 0 14.2M16.24 7.76a6 6 0 0 1 0 8.49"/><circle cx="12" cy="12" r="2"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.07.07l2-2a5 5 0 0 0-7.07-7.07l-1.15 1.15"/><path d="M14 11a5 5 0 0 0-7.07-.07l-2 2A5 5 0 0 0 12 20l1.15-1.15"/>',
  server: '<rect width="20" height="8" x="2" y="2" rx="2" ry="2"/><rect width="20" height="8" x="2" y="14" rx="2" ry="2"/><line x1="6" x2="6.01" y1="6" y2="6"/><line x1="6" x2="6.01" y1="18" y2="18"/>',
  power: '<path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/>',
  info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>',
  'chevron-right': '<path d="m9 18 6-6-6-6"/>',
  'external-link': '<path d="M15 3h6v6M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
  smartphone: '<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><path d="M12 18h.01"/>',
  bug: '<path d="m8 2 1.88 1.88M14.12 3.88 16 2M9 7.13v-1a3 3 0 0 1 6 0v1"/><path d="M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6ZM12 20v-9M6.53 9C4.6 8.8 3 7.1 3 5M6 13H2M3 21c0-2.1 1.7-3.9 3.8-4M17.47 9C19.4 8.8 21 7.1 21 5M18 13h4M21 21c0-2.1-1.7-3.9-3.8-4"/>',
  cpu: '<rect width="16" height="16" x="4" y="4" rx="2"/><rect width="6" height="6" x="9" y="9" rx="1"/><path d="M9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3"/>',
  'scan-line': '<path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2M7 12h10"/>',
  navigation: '<polygon points="3 11 22 2 13 21 11 13 3 11"/>',
  wifi: '<path d="M5 13a10 10 0 0 1 14 0M8.5 16.5a5 5 0 0 1 7 0M2 9.5a15 15 0 0 1 20 0M12 20h.01"/>',
  'circle-alert': '<circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/>',
  'lock-keyhole': '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4M12 15v3"/>',
  headphones: '<path d="M4 14a8 8 0 0 1 16 0"/><path d="M18 19c0 1.7-1.3 3-3 3v-8h3a2 2 0 0 1 2 2v1a2 2 0 0 1-2 2ZM6 19c0 1.7 1.3 3 3 3v-8H6a2 2 0 0 0-2 2v1a2 2 0 0 0 2 2Z"/>',
  database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 1.7 4 3 9 3s9-1.3 9-3V5M3 12c0 1.7 4 3 9 3s9-1.3 9-3"/>'
};

const NAV_ITEMS = [
  { id: 'home', href: 'index.html', label: '首页', icon: 'home' },
  { id: 'device', href: 'device.html', label: '设备', icon: 'watch' },
  { id: 'sync', href: 'sync.html', label: '同步', icon: 'refresh-cw' },
  { id: 'notifications', href: 'notifications.html', label: '通知', icon: 'bell' },
  { id: 'settings', href: 'settings.html', label: '更多', icon: 'settings-2' }
];

const STORAGE_KEY = 'hsp-watch-design-state';
const DEFAULT_STATE = {
  service: true,
  bluetooth: true,
  notificationAccess: true,
  permissions: true,
  lastSync: '刚刚'
};

let toastTimer;

function readState() {
  try {
    return { ...DEFAULT_STATE, ...JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') };
  } catch (_) {
    return { ...DEFAULT_STATE };
  }
}

function writeState(nextState) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(nextState));
  } catch (_) {
    // file:// storage can be disabled; the prototype still works for the current page.
  }
}

function navLinks(currentPage, compact = false) {
  return NAV_ITEMS.map((item) => {
    const current = item.id === currentPage ? ' aria-current="page"' : '';
    return `<a class="nav-link" href="${item.href}"${current}>
      <span class="icon" data-icon="${item.icon}" aria-hidden="true"></span>
      <span>${item.label}</span>
    </a>`;
  }).join('');
}

function mountNavigation() {
  const currentPage = document.body.dataset.page || 'home';
  const sideHost = document.querySelector('[data-side-nav]');
  const bottomHost = document.querySelector('[data-bottom-nav]');

  if (sideHost) {
    sideHost.outerHTML = `<aside class="side-nav" aria-label="主导航">
      <a class="brand" href="index.html" aria-label="HSP Watch 首页">
        <span class="brand-mark"><img src="../docs/icon.png" alt=""></span>
        <span class="brand-copy"><strong>HSP Watch</strong><span>Companion</span></span>
      </a>
      <nav class="nav-list">${navLinks(currentPage)}</nav>
      <div class="side-status">
        <div class="side-status-row"><span class="status-dot" data-connection-dot></span><span data-connected-copy>手表已连接</span></div>
      </div>
    </aside>`;
  }

  if (bottomHost) {
    bottomHost.outerHTML = `<nav class="bottom-nav" aria-label="主导航">${navLinks(currentPage, true)}</nav>`;
  }
}

function mountIcons(root = document) {
  root.querySelectorAll('[data-icon]').forEach((host) => {
    const paths = ICONS[host.dataset.icon];
    if (!paths || host.dataset.iconMounted === 'true') return;
    host.dataset.iconMounted = 'true';
    host.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${paths}</svg>`;
  });
}

function setText(selector, value) {
  document.querySelectorAll(selector).forEach((element) => {
    element.textContent = value;
  });
}

function setIndicator(selector, active) {
  document.querySelectorAll(selector).forEach((element) => {
    element.classList.toggle('good', active);
    element.classList.toggle('good-text', active);
    element.classList.toggle('warning', !active);
  });
}

function showToast(message, icon = 'check') {
  const toast = document.querySelector('[data-toast]');
  if (!toast) return;
  toast.innerHTML = `<span class="icon icon-sm" data-icon="${icon}" aria-hidden="true"></span><span>${message}</span>`;
  mountIcons(toast);
  toast.classList.add('is-visible');
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

function updateConnectionUi(state) {
  const connected = state.service && state.bluetooth;
  setText('[data-service-state]', state.service ? '运行中' : '已停止');
  setText('[data-bluetooth-state]', state.bluetooth ? '已开启' : '已关闭');
  setText('[data-notification-access]', state.notificationAccess ? '已开启' : '未开启');
  setText('[data-permission-state]', state.permissions ? '已允许' : '待授权');
  setText('[data-sync-age]', state.lastSync);
  setText('[data-connected-copy]', connected ? '手表已连接' : state.bluetooth ? '服务未启动' : '蓝牙已关闭');
  setText('[data-connection-value]', connected ? '已连接' : '未连接');
  setText('[data-command-channel]', connected ? '已就绪' : '不可用');
  setText('[data-status-channel]', connected ? '已订阅' : '不可用');
  setText('[data-sync-channel]', connected ? '已就绪' : '不可用');
  setIndicator('[data-service-state]', state.service);
  setIndicator('[data-bluetooth-state]', state.bluetooth);
  setIndicator('[data-permission-state]', state.permissions);
  setIndicator('[data-notification-access], [data-notification-badge]', state.notificationAccess);
  setIndicator('[data-connection-value], [data-command-channel], [data-status-channel], [data-sync-channel]', connected);

  document.querySelectorAll('[data-connection-dot]').forEach((dot) => {
    dot.style.background = connected ? 'var(--success)' : 'var(--danger)';
    dot.style.boxShadow = connected
      ? '0 0 0 4px var(--success-soft)'
      : '0 0 0 4px var(--danger-soft)';
  });

  document.querySelectorAll('[data-service-toggle]').forEach((toggle) => {
    toggle.checked = state.service;
  });

  document.querySelectorAll('[data-find-label]').forEach((label) => {
    label.textContent = connected ? '查找手表' : '启动并查找手表';
  });

}

function handleFind(button, state) {
  if (!state.bluetooth) {
    showToast('请先开启手机蓝牙', 'circle-alert');
    return;
  }
  if (!state.service) {
    state.service = true;
    writeState(state);
    updateConnectionUi(state);
  }

  const label = button.querySelector('[data-find-label]');
  const original = label?.textContent || '查找手表';
  button.disabled = true;
  if (label) label.textContent = '正在发送';
  const icon = button.querySelector('.icon');
  icon?.classList.add('spinner');

  window.setTimeout(() => {
    icon?.classList.remove('spinner');
    if (label) label.textContent = '命令已发送';
    showToast('手表查找命令已发送');
  }, 850);

  window.setTimeout(() => {
    button.disabled = false;
    if (label) label.textContent = original.includes('启动') ? '查找手表' : original;
  }, 2200);
}

function handleSync(button, state) {
  if (!state.bluetooth) {
    showToast('请先开启手机蓝牙', 'circle-alert');
    return;
  }
  if (!state.service) state.service = true;
  writeState(state);
  updateConnectionUi(state);

  const label = button.querySelector('[data-sync-label]');
  const icon = button.querySelector('.icon');
  const progress = document.querySelector('[data-sync-progress]');
  button.disabled = true;
  if (label) label.textContent = '正在同步';
  icon?.classList.add('spinner');
  progress?.classList.add('is-running');
  setText('[data-sync-result]', '正在同步手机数据');

  window.setTimeout(() => {
    state.lastSync = '刚刚';
    writeState(state);
    updateConnectionUi(state);
    setText('[data-sync-result]', '时间、位置与天气已同步');
    if (label) label.textContent = '立即同步';
    icon?.classList.remove('spinner');
    progress?.classList.remove('is-running');
    button.disabled = false;
    showToast('手机数据同步完成');
  }, 1600);
}

function bindActions(state) {
  document.querySelectorAll('[data-action="find-watch"]').forEach((button) => {
    button.addEventListener('click', () => handleFind(button, state));
  });

  document.querySelectorAll('[data-action="sync-now"]').forEach((button) => {
    button.addEventListener('click', () => handleSync(button, state));
  });

  document.querySelectorAll('[data-service-toggle]').forEach((toggle) => {
    toggle.addEventListener('change', () => {
      state.service = toggle.checked;
      writeState(state);
      updateConnectionUi(state);
      showToast(state.service ? '后台连接服务已启动' : '后台连接服务已停止', state.service ? 'check' : 'power');
    });
  });

  document.querySelectorAll('[data-action="manage-notifications"]').forEach((button) => {
    button.addEventListener('click', () => {
      state.notificationAccess = true;
      writeState(state);
      updateConnectionUi(state);
      setText('[data-notification-action-label]', '管理通知读取');
      showToast('通知使用权已开启', 'shield-check');
    });
  });

  document.querySelectorAll('[data-action="enable-bluetooth"]').forEach((button) => {
    button.addEventListener('click', () => {
      state.bluetooth = true;
      writeState(state);
      updateConnectionUi(state);
      showToast('蓝牙已开启', 'bluetooth');
    });
  });

  document.querySelectorAll('[data-action="grant-permissions"]').forEach((button) => {
    button.addEventListener('click', () => {
      state.permissions = true;
      writeState(state);
      updateConnectionUi(state);
      showToast('所需权限已允许', 'shield-check');
    });
  });
}

function updateClock() {
  const now = new Date();
  const formatter = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  });
  setText('[data-current-time]', formatter.format(now));
}

function init() {
  mountNavigation();
  mountIcons();
  const state = readState();
  updateConnectionUi(state);
  bindActions(state);
  updateClock();
  window.setInterval(updateClock, 30_000);
}

document.addEventListener('DOMContentLoaded', init);
