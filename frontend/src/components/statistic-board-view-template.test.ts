import { describe, expect, it } from 'vitest';
import { compileTemplate, parse } from 'vue/compiler-sfc';
import controlBarSource from './StatisticBoardControlBar.vue?raw';
import viewSource from './StatisticBoardView.vue?raw';

/**
 * 统计板外壳的模板编译护栏。
 *
 * <p>`StatisticBoardView.vue` 是所有统计板的渲染入口：模板级错误（例如重复绑定同一属性）不会让
 * 组件级单测或 typecheck 失败，却会让每个看板页面在浏览器里整体加载失败。这里在单元层直接编译模板，
 * 用最低成本覆盖该缺陷类。
 */
const SOURCES: Array<[string, string]> = [
  ['StatisticBoardView.vue', viewSource],
  ['StatisticBoardControlBar.vue', controlBarSource],
];

function templateErrors(file: string, source: string): string[] {
  const descriptor = parse(source, { filename: file });
  const template = descriptor.descriptor.template;
  if (!template) {
    return ['缺少 template 块'];
  }
  const compiled = compileTemplate({
    id: file,
    filename: file,
    source: template.content,
    compilerOptions: { expressionPlugins: ['typescript'] },
  });
  return compiled.errors.map((error) =>
    typeof error === 'string' ? error : `${error.message} @${error.loc?.start.line ?? '?'}`,
  );
}

describe('统计板外壳模板', () => {
  it.each(SOURCES)('%s 的模板可编译', (file, source) => {
    expect(templateErrors(file, source)).toEqual([]);
  });
});
