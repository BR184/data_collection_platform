export type TableHeaderLines = readonly [string, string] | readonly [string];

const MANUAL_HEADER_LINES: Record<string, TableHeaderLines> = {
  '评审缺陷密度(个/页)': ['评审缺陷', '密度(个/页)'],
  '评审缺陷密度（个/页）': ['评审缺陷', '密度（个/页）'],
  '需求评审缺陷密度': ['需求评审', '缺陷密度'],
  '设计评审缺陷密度': ['设计评审', '缺陷密度'],
  '代码走查缺陷密度': ['代码走查', '缺陷密度'],
  '代码走查缺陷密度(CC)': ['代码走查', '缺陷密度(CC)'],
  '代码走查缺陷密度(DGM)': ['代码走查', '缺陷密度(DGM)'],
  '系统测试未关闭占比': ['系统测试', '未关闭占比'],
  '问题合计(个)': ['问题', '合计(个)'],
  '问题合计（个）': ['问题', '合计（个）'],
  '缺陷密度(个/页)': ['缺陷密度', '(个/页)'],
  '缺陷密度（个/页）': ['缺陷密度', '（个/页）'],
  '评审效率(个/小时)': ['评审效率', '(个/小时)'],
  '评审效率（个/小时）': ['评审效率', '（个/小时）'],
  '评审速率(页/小时)': ['评审速率', '(页/小时)'],
  '评审速率（页/小时）': ['评审速率', '（页/小时）'],
  '独立评审工作量合计(小时)': ['独立评审工作量', '合计(小时)'],
  '会议评审工作量合计(小时)': ['会议评审工作量', '合计(小时)'],
  '会议评审工作量(小时)': ['会议评审工作量', '(小时)'],
  '会议评审工作量(小时）': ['会议评审工作量', '(小时）'],
  '会议评审工作量（小时）': ['会议评审工作量', '（小时）'],
  '清单评审工作量合计(小时)': ['清单评审工作量', '合计(小时)'],
  '有效独立评审问题数合计(个)': ['有效独立评审问题数', '合计(个)'],
  '有效的独立评审问题数合计(个)': ['有效独立评审问题数', '合计(个)'],
  '有效会议评审问题数合计(个)': ['有效会议评审问题数', '合计(个)'],
  '有效的会议评审问题数合计(个)': ['有效会议评审问题数', '合计(个)'],
  '合并请求编号': ['合并请求', '编号'],
  '合并请求内容': ['合并请求', '内容'],
  '合并目标分支': ['合并目标', '分支'],
  '新增走查代码行数（LOC）': ['新增走查代码行数', '（LOC）'],
  '新增代码行数（行）': ['新增代码行数', '（行）'],
  '代码注释比例（%）': ['代码注释比例', '（%）'],
  '代码走查速率（LOC/H）': ['代码走查速率', '（LOC/H）'],
  '静态扫描问题未关闭': ['静态扫描问题', '未关闭'],
  '注释率分析工具 Clang 分析错误': ['注释率分析工具', 'Clang 分析错误'],
  '测试阶段名称': ['测试阶段', '名称'],
  '评审工作量(小时)': ['评审工作量', '(小时)'],
  '评审工作量（小时）': ['评审工作量', '（小时）'],
  '在文档中的位置': ['在文档中的', '位置'],
};

const PHRASE_BOUNDARIES = [
  '合并请求',
  '合并目标',
  '代码走查',
  '静态扫描问题',
  '注释率分析工具',
  '测试阶段',
  '议题提交',
  '议题处理',
  '议题创建',
  '议题更新',
  '客户问题',
  '系统测试',
  '独立评审',
  '会议评审',
  '清单评审',
  '有效独立',
  '有效会议',
];

const SUFFIX_BOUNDARIES = [
  '密度',
  '占比',
  '比例',
  '效率',
  '速率',
  '数量',
  '编号',
  '内容',
  '状态',
  '类型',
  '类别',
  '分支',
  '名称',
  '时间',
  '日期',
  '原因',
  '合计',
];

export function visualTextUnits(text: string) {
  return Array.from(text).reduce((total, character) => total + (/[\u0000-\u00ff]/.test(character) ? 1 : 2), 0);
}

export function normalizeTableHeaderLines(label: string, explicitLines?: readonly string[] | null): TableHeaderLines {
  const normalizedLabel = normalizeLabel(label);
  const manualLines = normalizeExplicitLines(explicitLines);
  if (manualLines) {
    return manualLines;
  }
  if (!normalizedLabel) {
    return [''];
  }
  if (MANUAL_HEADER_LINES[normalizedLabel]) {
    return MANUAL_HEADER_LINES[normalizedLabel];
  }
  const unitLines = splitUnitSuffix(normalizedLabel);
  if (unitLines) {
    return unitLines;
  }
  const phraseLines = splitByPhraseBoundary(normalizedLabel);
  if (phraseLines) {
    return phraseLines;
  }
  const suffixLines = splitBySuffixBoundary(normalizedLabel);
  if (suffixLines) {
    return suffixLines;
  }
  return [normalizedLabel];
}

export function tableHeaderLongestLineUnits(label: string, explicitLines?: readonly string[] | null) {
  return Math.max(...normalizeTableHeaderLines(label, explicitLines).map(visualTextUnits));
}

export function tableHeaderMinimumWidth(label: string, reservePx: number, explicitLines?: readonly string[] | null) {
  return Math.max(76, tableHeaderLongestLineUnits(label, explicitLines) * 7 + reservePx);
}

function normalizeLabel(label: string) {
  return String(label ?? '').replace(/\s+/g, ' ').trim();
}

function normalizeExplicitLines(explicitLines?: readonly string[] | null): TableHeaderLines | null {
  if (!explicitLines?.length) {
    return null;
  }
  const lines = explicitLines.map((line) => normalizeLabel(line)).filter(Boolean);
  if (!lines.length) {
    return null;
  }
  if (lines.length === 1) {
    return [lines[0]];
  }
  return [lines[0], lines.slice(1).join(' ')];
}

function splitUnitSuffix(label: string): TableHeaderLines | null {
  if (visualTextUnits(label) <= 14) {
    return null;
  }
  const match = label.match(/^(.+?)([（(][^（）()]+[）)])$/);
  if (!match) {
    return null;
  }
  const [, prefix, suffix] = match;
  if (visualTextUnits(prefix) < 8 || visualTextUnits(suffix) > visualTextUnits(prefix) + 6) {
    return null;
  }
  return [prefix, suffix];
}

function splitByPhraseBoundary(label: string): TableHeaderLines | null {
  if (visualTextUnits(label) <= 12) {
    return null;
  }
  for (const phrase of PHRASE_BOUNDARIES) {
    if (!label.startsWith(phrase) || label.length <= phrase.length + 1) {
      continue;
    }
    const tail = label.slice(phrase.length);
    if (visualTextUnits(tail) < 4 || visualTextUnits(tail) > 18) {
      continue;
    }
    return [phrase, tail];
  }
  return null;
}

function splitBySuffixBoundary(label: string): TableHeaderLines | null {
  if (visualTextUnits(label) <= 14) {
    return null;
  }
  for (const suffix of SUFFIX_BOUNDARIES) {
    const index = label.lastIndexOf(suffix);
    if (index <= 0 || index >= label.length) {
      continue;
    }
    const prefix = label.slice(0, index);
    const tail = label.slice(index);
    if (visualTextUnits(prefix) < 6 || visualTextUnits(tail) < 4 || visualTextUnits(tail) > 16) {
      continue;
    }
    return [prefix, tail];
  }
  return null;
}
