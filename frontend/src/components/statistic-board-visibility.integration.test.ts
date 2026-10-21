import { defineComponent, ref } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { Sort } from '@element-plus/icons-vue';
import type { StatisticBoardResponse } from '../types/api';
import StatisticTableColumnGroup from './base/StatisticTableColumnGroup.vue';
import { useStatisticBoardViewPrefs } from '../composables/useStatisticBoardViewPrefs';
import { useStatisticBoardTableState } from '../composables/useStatisticBoardTableState';
import { useStatisticBoardSortControls } from '../composables/useStatisticBoardSortControls';
import { useStatisticBoardTableAdapters } from '../composables/useStatisticBoardTableAdapters';

const boardKey = 'customer-issue-customer-statistics';
type Dimension = '' | 'moduleName' | 'functionName';

function response(dimension: Dimension): StatisticBoardResponse {
  const label = dimension === 'moduleName' ? '模块' : '功能';
  return {
    definition: {
      boardKey, title: '客户问题统计', description: '', queryTitle: '', queryDescription: '', rowHeaderLabel: '客户名称', filters: [], detailColumns: [], defaultPageSize: 20,
      columnGroups: [
        ...(dimension ? [{ key: 'row-dimension', label, columns: [{ key: dimension, label, metricType: 'text', drilldown: false }] }] : []),
        { key: 'overall', label: '整体', columns: [
          { key: 'defect_total', label: '缺陷数', metricType: 'count', drilldown: true },
          { key: 'defect_fixed', label: '已修复', metricType: 'count', drilldown: true },
        ] },
      ],
    },
    appliedFilters: {}, rows: [{ rowKey: 'customer-a', rowLabel: '客户甲', cells: [
      ...(dimension ? [{ columnKey: dimension, numericValue: 0, displayValue: dimension === 'moduleName' ? '草图' : '倒角', drilldown: false, detailParams: {} }] : []),
      { columnKey: 'defect_total', numericValue: 3, displayValue: '3', drilldown: true, detailParams: {} },
      { columnKey: 'defect_fixed', numericValue: 1, displayValue: '1', drilldown: true, detailParams: {} },
    ] }], meta: { generatedAt: '2026-10-20T00:00:00', queryDurationMs: 1, rowCount: 1, columnCount: dimension ? 3 : 2, drilldownColumnCount: 2 },
  };
}

const Harness = defineComponent({
  components: { StatisticTableColumnGroup },
  setup() {
    const board = ref(response(''));
    const prefs = useStatisticBoardViewPrefs({ boardKey: () => boardKey, routeQuery: () => ({}), replaceRouteQuery: vi.fn(), notifySuccess: vi.fn(), notifyWarning: vi.fn() });
    prefs.applyStoredViewPrefs(board.value.definition);
    const table = useStatisticBoardTableState({ board, boardViewPrefs: prefs.boardViewPrefs, tableCurrentPage: ref(1), tablePageSize: ref(20), boardKey: () => boardKey });
    const sorting = useStatisticBoardSortControls({ board, boardViewPrefs: prefs.boardViewPrefs, persistViewPrefs: prefs.persistViewPrefs, replaceRouteQuery: vi.fn() });
    const adapters = useStatisticBoardTableAdapters({ board, boardViewPrefs: prefs.boardViewPrefs, openStatisticDetail: vi.fn() });
    function setDimension(dimension: Dimension) {
      board.value = response(dimension);
      prefs.applyStoredViewPrefs(board.value.definition);
    }
    const columnProps = {
      parentGroupKey: '__root__', ...sorting, ...adapters,
      sortIconForDirection: () => Sort,
      isColumnDragging: () => false, onColumnDragStart: () => undefined, onColumnDrop: () => undefined, clearDragState: () => undefined,
    };
    return { board, ...table, columnProps, setDimension, sort: () => sorting.toggleColumnSort('defect_total') };
  },
  template: `<div>
    <button data-test="customer" @click="setDimension('')">按客户</button>
    <button data-test="module" @click="setDimension('moduleName')">客户×模块</button>
    <button data-test="function" @click="setDimension('functionName')">客户×功能</button>
    <button data-test="sort" @click="sort">排序</button>
    <el-table :key="tableRenderKey" :data="paginatedRows">
      <el-table-column prop="rowLabel" label="客户名称" />
      <StatisticTableColumnGroup v-for="group in orderedColumnGroups" :key="group.key" :group="group" :root-group-key="group.key" v-bind="columnProps" />
    </el-table>
  </div>`,
});

describe('customer statistic dimension visibility through actual table rendering', () => {
  let wrapper: VueWrapper | undefined;
  beforeEach(() => window.localStorage.clear());
  afterEach(() => { wrapper?.unmount(); wrapper = undefined; });

  it('renders module and function identity cells after sorting and repeated dimension changes with legacy preferences', async () => {
    window.localStorage.setItem(`stat-board-view:${boardKey}`, JSON.stringify({
      visibleColumnKeys: ['defect_total'], groupOrder: ['overall'], columnOrderByGroup: { overall: ['defect_total', 'defect_fixed'] },
    }));
    wrapper = mount(Harness, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.get('[data-test="sort"]').trigger('click');
    for (const [mode, label, value] of [['module', '模块', '草图'], ['function', '功能', '倒角'], ['customer', '', ''], ['module', '模块', '草图']]) {
      await wrapper.get(`[data-test="${mode}"]`).trigger('click');
      await flushPromises();
      const headers = wrapper.find('.el-table__header-wrapper').text();
      expect(headers).toContain('客户名称');
      expect(headers).toContain('缺陷数');
      expect(headers).not.toContain('已修复');
      if (label) { expect(headers).toContain(label); }
      const body = wrapper.find('.el-table__body-wrapper').text();
      expect(body).toContain('客户甲');
      expect(body).toContain('3');
      if (value) { expect(body).toContain(value); }
    }
  });
});
