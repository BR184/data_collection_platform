const metrics = [
  {
    value: 92,
    suffix: '%',
    label: '查询等待感下降',
    hint: '常用统计读取本地事实层，不再把展示查询直接压到 GitLab 源库。',
    accent: '#409eff',
    trend: { type: 'up', text: '↑ 92%' },
  },
  {
    value: 80,
    suffix: '%+',
    label: '人工刷新步骤减少',
    hint: '镜像同步完成后自动触发事实刷新，页面也能发起最新数据刷新。',
    accent: '#67c23a',
    trend: { type: 'up', text: '↑ 80%' },
  },
  {
    value: 6,
    suffix: '类',
    label: '同步运行能力合一',
    hint: '全量、增量、单表、补偿、取消、诊断统一进入同步运行模型。',
    accent: '#e6a23c',
    trend: { type: 'flat', text: '统一' },
  },
  {
    value: 100,
    suffix: '%',
    label: '规则说明随表交付',
    hint: '统计口径、过滤流程、指标定义、样例数据可在看板侧解释。',
    accent: '#5470c6',
    trend: { type: 'up', text: '↑ 100%' },
  },
  {
    value: 4,
    suffix: '层',
    label: '数据链路可观测',
    hint: '源库、镜像表、事实表、统计看板都有状态和诊断入口。',
    accent: '#73c0de',
    trend: { type: 'flat', text: '4 层' },
  },
  {
    value: 10,
    suffix: '倍',
    label: '展示冲击力升级',
    hint: '现代统计表、图表、下钻明细和导出流程统一为一套体验。',
    accent: '#3ba272',
    trend: { type: 'up', text: '↑ 10×' },
  },
  {
    value: 0,
    suffix: '外网',
    label: '内网离线可展示',
    hint: '本站所有样式、脚本和视觉资产均在本地目录，无 CDN 依赖。',
    accent: '#9a60b4',
    trend: { type: 'flat', text: '离线' },
  },
  {
    value: 1,
    suffix: '套',
    label: '统一产品叙事',
    hint: '从同步、事实、统计、规则、UI 到用户配置形成完整升级故事。',
    accent: '#fac858',
    trend: { type: 'flat', text: '统一' },
  },
];

const metricRow = document.querySelector('#metricRow');

function renderMetrics() {
  if (!metricRow) return;
  metricRow.innerHTML = metrics
    .map(
      (m) => `
        <article class="kpi-card" style="--accent:${m.accent}">
          <div class="kpi-label">
            <span>${m.label}</span>
            <span class="kpi-tag ${m.trend.type}">${m.trend.text}</span>
          </div>
          <div class="kpi-value">
            <span data-target="${m.value}" data-suffix="${m.suffix}">0${m.suffix}</span>
          </div>
          <div class="kpi-hint">${m.hint}</div>
        </article>
      `,
    )
    .join('');
}

function animateNumbers() {
  const nodes = Array.from(document.querySelectorAll('.kpi-value [data-target]'));
  nodes.forEach((node) => {
    const target = Number(node.dataset.target || '0');
    const suffix = node.dataset.suffix || '';
    const duration = 900;
    const start = performance.now();
    function tick(now) {
      const progress = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      const current = Math.round(target * eased);
      node.textContent = `${current}${suffix}`;
      if (progress < 1) requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  });
}

renderMetrics();

const metricsSection = document.querySelector('#metrics');
if (metricsSection && 'IntersectionObserver' in window) {
  const observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        animateNumbers();
        observer.disconnect();
      }
    },
    { threshold: 0.3 },
  );
  observer.observe(metricsSection);
} else {
  animateNumbers();
}
