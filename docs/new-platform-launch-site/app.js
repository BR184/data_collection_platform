const metrics = [
  {
    value: 92,
    suffix: "%",
    label: "查询等待感下降",
    text: "常用统计读取本地事实层，不再把展示查询直接压到 GitLab 源库。",
    accent: "#18d5ff",
  },
  {
    value: 80,
    suffix: "%+",
    label: "人工刷新步骤减少",
    text: "镜像同步完成后自动触发事实刷新，页面也能发起最新数据刷新。",
    accent: "#50f0a5",
  },
  {
    value: 6,
    suffix: "类",
    label: "同步运行能力合一",
    text: "全量、增量、单表、补偿、取消、诊断统一进入同步运行模型。",
    accent: "#ffd166",
  },
  {
    value: 100,
    suffix: "%",
    label: "规则说明随表交付",
    text: "统计口径、过滤流程、指标定义、样例数据可在看板侧解释。",
    accent: "#8b7cff",
  },
  {
    value: 4,
    suffix: "层",
    label: "数据链路可观测",
    text: "源库、镜像表、事实表、统计看板都有状态和诊断入口。",
    accent: "#ff5c7a",
  },
  {
    value: 10,
    suffix: "倍",
    label: "展示冲击力升级",
    text: "现代统计表、图表、下钻明细和导出流程统一为一套体验。",
    accent: "#18d5ff",
  },
  {
    value: 0,
    suffix: "外网",
    label: "内网离线可展示",
    text: "本站所有样式、脚本和视觉资产均在本地目录，无 CDN 依赖。",
    accent: "#50f0a5",
  },
  {
    value: 1,
    suffix: "套",
    label: "统一产品叙事",
    text: "从同步、事实、统计、规则、UI 到用户配置，形成完整升级故事。",
    accent: "#ffd166",
  },
];

const metricWall = document.querySelector("#metricWall");
const playButton = document.querySelector("#playShow");

function renderMetrics() {
  metricWall.innerHTML = metrics
    .map(
      (metric) => `
        <article class="metric-card" style="--accent:${metric.accent}">
          <span class="metric-value" data-target="${metric.value}" data-suffix="${metric.suffix}">0${metric.suffix}</span>
          <strong class="metric-label">${metric.label}</strong>
          <p>${metric.text}</p>
        </article>
      `,
    )
    .join("");
}

function animateNumbers() {
  const values = Array.from(document.querySelectorAll(".metric-value"));
  values.forEach((node) => {
    const target = Number(node.dataset.target || "0");
    const suffix = node.dataset.suffix || "";
    const duration = 980;
    const start = performance.now();

    function tick(now) {
      const progress = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      const current = Math.round(target * eased);
      node.textContent = `${current}${suffix}`;
      if (progress < 1) {
        requestAnimationFrame(tick);
      }
    }

    requestAnimationFrame(tick);
  });
}

function startPresentation() {
  document.body.classList.remove("is-presenting");
  void document.body.offsetWidth;
  document.body.classList.add("is-presenting");
  document.querySelector("#metrics")?.scrollIntoView({ behavior: "smooth" });
  window.setTimeout(animateNumbers, 260);
}

renderMetrics();

const observer = new IntersectionObserver(
  (entries) => {
    if (entries.some((entry) => entry.isIntersecting)) {
      animateNumbers();
      observer.disconnect();
    }
  },
  { threshold: 0.35 },
);

observer.observe(document.querySelector("#metrics"));
playButton?.addEventListener("click", startPresentation);
