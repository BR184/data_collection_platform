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
  '所属项目': ['所属', '项目'],
  '非法类型': ['非法', '类型'],
  '静态扫描问题未关闭': ['静态扫描问题', '未关闭'],
  '注释率分析工具 Clang 分析错误': ['注释率分析工具', 'Clang 分析错误'],
  '测试阶段名称': ['测试阶段', '名称'],
  '评审工作量(小时)': ['评审工作量', '(小时)'],
  '评审工作量（小时）': ['评审工作量', '（小时）'],
  '在文档中的位置': ['在文档中的', '位置'],
  '是否达标': ['是否', '达标'],
  '是否达标?': ['是否', '达标?'],
  '是否达标？': ['是否', '达标？'],
  '已修复/未更新': ['已修复', '未更新'],
  '模块总缺陷数(个)': ['模块总', '缺陷数(个)'],
  '模块总缺陷数（个）': ['模块总', '缺陷数(个)'],
  '未关闭缺陷数(个)': ['未关闭', '缺陷数(个)'],
  '未关闭缺陷数（个）': ['未关闭', '缺陷数(个)'],
  '复测未通过缺陷数(个)': ['复测未通过', '缺陷数(个)'],
  '复测未通过缺陷数（个）': ['复测未通过', '缺陷数(个)'],
};

const COMPACT_HEADER_LINES: Record<string, TableHeaderLines> = {
  '评审缺陷密度(个/页)': ['评审', '密度(个/页)'],
  '评审缺陷密度（个/页）': ['评审', '密度（个/页）'],
  '问题合计(个)': ['问题', '合计(个)'],
  '问题合计（个）': ['问题', '合计（个）'],
  '缺陷密度(个/页)': ['缺陷', '(个/页)'],
  '缺陷密度（个/页）': ['缺陷', '（个/页）'],
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
  '未关闭数量',
  '复测未通过数量',
  '申请延期数量',
  '新发议题数量',
  '遗留缺陷数量',
  '已修复数量',
  '修复数量',
  '关闭率',
  '修复率',
  '遗留率',
  '延期占比',
  '未关闭占比',
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

const METRIC_TAILS = [
  '复测未通过缺陷数',
  '未关闭缺陷数',
  '模块总缺陷数',
  '代码走查缺陷合计',
  '建议类缺陷',
  '复测未通过数量',
  '申请延期数量',
  '新发议题数量',
  '遗留缺陷数量',
  '已修复数量',
  '修复数量',
  '缺陷数量',
  '缺陷合计',
  '缺陷数',
  '总缺陷数',
  '延期占比',
  '未关闭占比',
  '修复率',
  '关闭率',
  '遗留率',
  '密度',
  '占比',
  '比例',
  '效率',
  '速率',
  '数量',
  '缺陷',
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
  const metricLines = splitByMetricTail(normalizedLabel);
  if (metricLines) {
    return metricLines;
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

export function compactTableHeaderLines(label: string, explicitLines?: readonly string[] | null): TableHeaderLines {
  const normalizedLabel = normalizeLabel(label);
  if (COMPACT_HEADER_LINES[normalizedLabel]) {
    return COMPACT_HEADER_LINES[normalizedLabel];
  }
  const lines = normalizeTableHeaderLines(label, explicitLines);
  if (lines.length === 1) {
    return lines;
  }
  return [compactLeadingLine(lines[0]), lines[1]];
}

export function tableHeaderLongestLineUnits(label: string, explicitLines?: readonly string[] | null) {
  return Math.max(...normalizeTableHeaderLines(label, explicitLines).map(visualTextUnits));
}

export function tableHeaderMinimumWidth(label: string, reservePx: number, explicitLines?: readonly string[] | null) {
  const units = tableHeaderLongestLineUnits(label, explicitLines);
  const floor = units <= 4 ? 60 : units <= 6 ? 68 : units <= 10 ? 78 : 88;
  return Math.ceil(Math.max(floor, units * 7.2 + reservePx));
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

function compactLeadingLine(line: string) {
  const trailingWords = ['缺陷', '问题', '数量', '合计', '工作量', '数据', '内容'];
  for (const word of trailingWords) {
    if (line.endsWith(word) && visualTextUnits(line.slice(0, -word.length)) >= 4) {
      return line.slice(0, -word.length);
    }
  }
  return line;
}

function splitUnitSuffix(label: string): TableHeaderLines | null {
  const match = label.match(/^(.+?)([（(][^（）()]+[）)])$/);
  if (!match) {
    return null;
  }
  const [, prefix, suffix] = match;
  const normalizedSuffix = normalizeUnitSuffix(suffix);
  const metricLines = splitByMetricTail(prefix, normalizedSuffix);
  if (metricLines) {
    return metricLines;
  }
  const suffixLines = splitBySuffixBoundary(prefix);
  if (suffixLines) {
    return [suffixLines[0], `${suffixLines[1]}${normalizedSuffix}`];
  }
  if (visualTextUnits(prefix) < 8 || visualTextUnits(suffix) > visualTextUnits(prefix) + 6) {
    return null;
  }
  return [prefix, normalizedSuffix];
}

function splitByMetricTail(label: string, unitSuffix = ''): TableHeaderLines | null {
  const normalizedLabel = normalizeLabel(label);
  if (!normalizedLabel) {
    return null;
  }
  const standaloneLines = splitStandaloneMetric(normalizedLabel, unitSuffix);
  if (standaloneLines) {
    return standaloneLines;
  }
  for (const tail of METRIC_TAILS) {
    if (!normalizedLabel.endsWith(tail) || normalizedLabel.length <= tail.length) {
      continue;
    }
    const subject = normalizedLabel.slice(0, -tail.length);
    if (!isGoodMetricSubject(subject, tail)) {
      continue;
    }
    const line2 = `${tail}${unitSuffix}`;
    if (tail === '缺陷' && /^[一二三]级$/.test(subject)) {
      return [subject, line2];
    }
    return [subject, line2];
  }
  return null;
}

function isGoodMetricSubject(subject: string, tail: string) {
  if (visualTextUnits(subject) < 3 || visualTextUnits(subject) > 18) {
    return false;
  }
  if (tail === '缺陷') {
    return /^[一二三]级$/.test(subject) || /^P[123](级别)?$/.test(subject) || subject.endsWith('类');
  }
  return (
    /缺陷$/.test(subject)
    || /^P[123](级别)?$/.test(subject)
    || /^P[123](级别)?缺陷$/.test(subject)
    || /议题$/.test(subject)
    || /测试$/.test(subject)
    || /评审$/.test(subject)
  );
}

function splitStandaloneMetric(label: string, unitSuffix: string): TableHeaderLines | null {
  const suffix = unitSuffix || '';
  const standalonePatterns: Array<[RegExp, (match: RegExpMatchArray) => TableHeaderLines]> = [
    [/^(模块总)(缺陷数)$/, (match) => [match[1], `${match[2]}${suffix}`]],
    [/^(未关闭)(缺陷数)$/, (match) => [match[1], `${match[2]}${suffix}`]],
    [/^(复测未通过)(缺陷数)$/, (match) => [match[1], `${match[2]}${suffix}`]],
    [/^(申请延期)$/, (match) => [match[1], suffix || match[1]]],
    [/^(建议类)(缺陷)$/, (match) => [match[1], `${match[2]}${suffix}`]],
    [/^(规范类|逻辑类|设计类|性能类|其他类)(缺陷数)$/, (match) => [match[1], `${match[2]}${suffix}`]],
    [/^(代码走查)(缺陷合计)$/, (match) => [match[1], `${match[2]}${suffix}`]],
    [/^(代码走查)(行数)$/, (match) => [match[1], `${match[2]}${suffix}`]],
  ];
  for (const [pattern, build] of standalonePatterns) {
    const match = label.match(pattern);
    if (match) {
      const lines = build(match);
      if (lines[0] === lines[1]) {
        return [lines[0]];
      }
      return lines;
    }
  }
  return null;
}

function normalizeUnitSuffix(suffix: string) {
  return suffix.replace(/^（/, '(').replace(/）$/, ')');
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
