import { defineComponent } from 'vue';
import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import StatisticBoardMemberFilters from './StatisticBoardMemberFilters.vue';
import type { RecordTableFilterField } from '../types/record-table';

const fields: RecordTableFilterField[] = [
  { key: 'customer', label: '客户', type: 'select', options: [{ label: '客户甲', value: 'VALUE:客户甲' }] },
  { key: 'module', label: '模块', type: 'select', options: [{ label: '模块甲', value: 'VALUE:模块甲' }] },
];

const FieldsStub = defineComponent({
  name: 'RecordTableFilterFields',
  props: ['filters', 'filterValues', 'inputDrafts', 'disabledKeys'],
  emits: ['filter-change'],
  template: '<div data-testid="member-fields"><span data-testid="disabled-keys">{{ (disabledKeys || []).join(",") }}</span></div>',
});

const ButtonStub = defineComponent({
  name: 'ElButton',
  props: ['loading', 'plain', 'size'],
  emits: ['click'],
  template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
});

function mountFilters(overrides: Record<string, unknown> = {}) {
  return mount(StatisticBoardMemberFilters, {
    props: {
      filters: fields,
      values: { customer: 'VALUE:客户甲', module: '' },
      ...overrides,
    },
    global: { stubs: { RecordTableFilterFields: FieldsStub, ElButton: ButtonStub } },
  });
}

describe('StatisticBoardMemberFilters', () => {
  it('renders the member fields through the shared quick-filter renderer', () => {
    const wrapper = mountFilters();

    expect(wrapper.find('[data-testid="member-fields"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="member-candidates-state"]').exists()).toBe(false);
  });

  it('disables every member selector while the candidates are still loading', () => {
    const wrapper = mountFilters({ status: 'loading' });

    expect(wrapper.get('[data-testid="disabled-keys"]').text()).toBe('customer,module');
    expect(wrapper.get('[data-testid="member-candidates-state"]').text()).toBe('正在加载成员候选…');
    expect(wrapper.find('[data-testid="member-candidates-retry"]').exists()).toBe(false);
  });

  it('reports a failed batch preparation with the reason and a retry action', async () => {
    const retry = vi.fn();
    const wrapper = mountFilters({
      status: 'loading',
      failed: true,
      candidateMessage: '页面尚无完整产出',
      onRetry: retry,
    });

    expect(wrapper.get('[data-testid="member-candidates-state"]').text()).toBe('页面尚无完整产出');
    await wrapper.get('[data-testid="member-candidates-retry"]').trigger('click');
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('offers retry when the scope is not readable instead of pretending there are no members', () => {
    const wrapper = mountFilters({ status: 'unavailable', candidateMessage: '来源尚未结算' });

    expect(wrapper.get('[data-testid="member-candidates-state"]').text()).toBe('来源尚未结算');
    expect(wrapper.find('[data-testid="member-candidates-retry"]').exists()).toBe(true);
  });

  it('separates a genuinely empty candidate set from a load failure', () => {
    const wrapper = mountFilters({ status: 'ready', emptyNotice: '当前范围没有可选的成员' });

    expect(wrapper.get('[data-testid="member-candidates-state"]').text()).toBe('当前范围没有可选的成员');
    expect(wrapper.find('[data-testid="member-candidates-retry"]').exists()).toBe(false);
    expect(wrapper.get('[data-testid="disabled-keys"]').text()).toBe('');
  });

  it('keeps the selected member visible and usable once candidates are ready', () => {
    const wrapper = mountFilters({ status: 'ready' });

    expect(wrapper.get('[data-testid="disabled-keys"]').text()).toBe('');
    expect(wrapper.find('[data-testid="member-candidates-state"]').exists()).toBe(false);
  });

  it('surfaces invalid member URL parameters without turning them into an all-members choice', () => {
    const wrapper = mountFilters({ status: 'ready', selectionError: '客户成员筛选参数无效：customerKind/customer' });

    expect(wrapper.get('[data-testid="member-selection-error"]').text()).toContain('customerKind/customer');
  });

  it('forwards member changes with their control key', async () => {
    const filterChange = vi.fn();
    const wrapper = mountFilters({ status: 'ready', onFilterChange: filterChange });

    wrapper.findComponent(FieldsStub).vm.$emit('filter-change', 'module', 'MISSING');

    expect(filterChange).toHaveBeenCalledWith({ key: 'module', value: 'MISSING' });
  });
});
