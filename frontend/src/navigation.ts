import {
  Bell,
  DataAnalysis,
  Document,
  Histogram,
  Monitor,
  Operation,
  Setting,
} from '@element-plus/icons-vue';

export type ModuleKey =
  | 'quality-board'
  | 'review-data'
  | 'code-review'
  | 'integration-test'
  | 'question-metrics'
  | 'customer-issues'
  | 'system-settings';

export type PageKey =
  | 'quality-board-home'
  | 'quality-board-rd-quality-board'
  | 'quality-board-other-board'
  | 'review-data-home'
  | 'code-review-home'
  | 'code-review-illegal-records'
  | 'code-review-multi-board'
  | 'integration-test-home'
  | 'question-metrics-home'
  | 'question-metrics-multi-board'
  | 'question-metrics-delay-analysis'
  | 'question-metrics-illegal-records'
  | 'question-metrics-defect-cause'
  | 'question-metrics-phase-statistics'
  | 'question-metrics-issue-search'
  | 'customer-issues-home'
  | 'customer-issues-illegal-records'
  | 'customer-issues-defect-cause'
  | 'customer-issues-cc-product-issues'
  | 'customer-issues-delay-issues'
  | 'customer-issues-response-efficiency'
  | 'customer-issues-issue-by-function'
  | 'mirror-settings'
  | 'database-browser'
  | 'testing-phase-definition';

export interface ShellPage {
  key: PageKey;
  label: string;
  description: string;
  path: string;
}

export interface ShellModule {
  key: ModuleKey;
  label: string;
  icon: unknown;
  title: string;
  description: string;
  pages: ShellPage[];
}

export const modules: ShellModule[] = [
  {
    key: 'quality-board',
    label: '统计分析',
    icon: Histogram,
    title: '统计分析',
    description: '统一承载汇总统计表、质量看板以及下钻明细页面。',
    pages: [
      {
        key: 'quality-board-home',
        label: '镜像表基础统计',
        description: '基于当前 GitLab 镜像数据展示汇总统计与明细下钻。',
        path: '/quality-board/home',
      },
      {
        key: 'quality-board-rd-quality-board',
        label: '研发质量看板',
        description: '对齐老平台研发质量看板入口，当前先保留统一看板壳子。',
        path: '/quality-board/rd-quality-board',
      },
      {
        key: 'quality-board-other-board',
        label: '其他看板',
        description: '对齐老平台其他看板入口，当前先保留轻量占位页。',
        path: '/quality-board/other-board',
      },
    ],
  },
  {
    key: 'review-data',
    label: '评审数据',
    icon: Document,
    title: '评审数据',
    description: '承载评审记录查看、维护以及问题清单管理的记录类页面。',
    pages: [
      {
        key: 'review-data-home',
        label: '评审数据管理',
        description: '展示评审主记录、评审专家以及问题清单明细。',
        path: '/review-data/home',
      },
    ],
  },
  {
    key: 'code-review',
    label: '代码走查',
    icon: Operation,
    title: '代码走查',
    description: '承载代码走查相关的记录页与统计看板入口。',
    pages: [
      {
        key: 'code-review-illegal-records',
        label: '代码走查非法数据',
        description: '展示代码走查场景下的非法记录明细列表。',
        path: '/code-review/illegal-records',
      },
      {
        key: 'code-review-multi-board',
        label: '代码走查多元看板',
        description: '对齐老平台代码走查多元看板入口，当前先保留统计壳子。',
        path: '/code-review/multi-board',
      },
    ],
  },
  {
    key: 'integration-test',
    label: '集成测试',
    icon: Monitor,
    title: '集成测试',
    description: '承载集成测试数据分析相关页面。',
    pages: [
      {
        key: 'integration-test-home',
        label: '集成测试数据分析',
        description: '对齐老平台集成测试数据分析入口，当前先提供模块级壳子。',
        path: '/integration-test/home',
      },
    ],
  },
  {
    key: 'question-metrics',
    label: '系统测试',
    icon: DataAnalysis,
    title: '系统测试',
    description: '统一承载系统测试议题的汇总、分析、非法数据和查询入口。',
    pages: [
      {
        key: 'question-metrics-home',
        label: '系统测试缺陷汇总',
        description: '展示系统测试缺陷的多级统计表头与模块维度汇总。',
        path: '/question-metrics/home',
      },
      {
        key: 'question-metrics-multi-board',
        label: '议题多元看板',
        description: '对齐老平台议题多元看板入口，当前先保留统一看板壳子。',
        path: '/question-metrics/multi-board',
      },
      {
        key: 'question-metrics-delay-analysis',
        label: '申请延期缺陷分析',
        description: '展示系统测试延期原因维度下的一二三级、建议类与总计缺陷统计，并支持下钻明细。',
        path: '/question-metrics/delay-analysis',
      },
      {
        key: 'question-metrics-illegal-records',
        label: '系统测试非法数据',
        description: '对齐老平台系统测试非法数据入口，当前先保留记录页占位壳子。',
        path: '/question-metrics/illegal-records',
      },
      {
        key: 'question-metrics-defect-cause',
        label: '缺陷原因分析',
        description: '展示系统测试缺陷在模块维度下的原因归类统计，并支持按原因下钻明细。',
        path: '/question-metrics/defect-cause',
      },
      {
        key: 'question-metrics-phase-statistics',
        label: '议题阶段统计',
        description: '展示系统测试轮次维度的一级、二级、三级、建议类及总量统计。',
        path: '/question-metrics/phase-statistics',
      },
      {
        key: 'question-metrics-issue-search',
        label: '议题查询',
        description: '对齐老平台议题查询入口，当前先补齐查询类副模块骨架。',
        path: '/question-metrics/issue-search',
      },
    ],
  },
  {
    key: 'customer-issues',
    label: '客户问题',
    icon: Bell,
    title: '客户问题',
    description: '统一承载客户问题统计、缺陷分析以及专题查询入口。',
    pages: [
      {
        key: 'customer-issues-home',
        label: '缺陷汇总',
        description: '展示客户问题范围下的多级统计表头与模块维度缺陷汇总。',
        path: '/customer-issues/home',
      },
      {
        key: 'customer-issues-illegal-records',
        label: '缺陷非法数据',
        description: '对齐老平台客户问题统计下的缺陷非法数据入口。',
        path: '/customer-issues/illegal-records',
      },
      {
        key: 'customer-issues-defect-cause',
        label: '缺陷原因分析',
        description: '展示客户问题在模块维度下的缺陷原因归类统计，并支持按原因下钻明细。',
        path: '/customer-issues/defect-cause',
      },
      {
        key: 'customer-issues-cc-product-issues',
        label: 'CC_PRODUCT议题',
        description: '对齐老平台客户问题统计下的 CC_PRODUCT 议题入口。',
        path: '/customer-issues/cc-product-issues',
      },
      {
        key: 'customer-issues-delay-issues',
        label: '延期问题',
        description: '对齐老平台客户问题统计下的延期问题入口。',
        path: '/customer-issues/delay-issues',
      },
      {
        key: 'customer-issues-response-efficiency',
        label: '缺陷响应效率',
        description: '对齐老平台客户问题统计下的缺陷响应效率入口。',
        path: '/customer-issues/response-efficiency',
      },
      {
        key: 'customer-issues-issue-by-function',
        label: '按功能展示缺陷数量',
        description: '对齐老平台客户问题统计下的按功能展示缺陷数量入口。',
        path: '/customer-issues/issue-by-function',
      },
    ],
  },
  {
    key: 'system-settings',
    label: '系统设置',
    icon: Setting,
    title: '系统设置',
    description: '维护 GitLab 数据镜像、数据库查看与系统配置。',
    pages: [
      {
        key: 'mirror-settings',
        label: '数据镜像设置',
        description: '管理 GitLab 数据镜像的连接、同步和日志。',
        path: '/system-settings/mirror-settings',
      },
      {
        key: 'database-browser',
        label: '数据库查看',
        description: '快速浏览本地平台数据库中的核心业务表数据。',
        path: '/system-settings/database-browser',
      },
      {
        key: 'testing-phase-definition',
        label: '议题测试阶段定义',
        description: '对齐老平台系统设置中的议题测试阶段定义入口。',
        path: '/system-settings/testing-phase-definition',
      },
    ],
  },
];

export const moduleByKey = new Map(modules.map((item) => [item.key, item] as const));
export const pageByKey = new Map(modules.flatMap((item) => item.pages.map((page) => [page.key, page] as const)));
