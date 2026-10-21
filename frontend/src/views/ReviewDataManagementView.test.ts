import { flushPromises, shallowMount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createEmptyReviewDataFilterOptions } from './review-data/useReviewDataRecords';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata, ReadViewTransport } from '../types/api/read-view';

function metadata(id: string): ReadViewMetadata {
  return { readContextId: id, pageKey: 'review-data-records', scopeKey: 'fixed', sourceVersion: id,
    businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z', leaseExpiresAt: '2099-01-01T00:00:00Z',
    pendingUpdates: 0, sourceState: 'CURRENT', escalated: false };
}

const mocks = vi.hoisted(() => ({
  options: vi.fn(),
  rows: vi.fn(),
  create: vi.fn(),
  items: vi.fn(),
  detail: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('../api', () => ({
  api: {
    getReviewDataFilterOptions: async (read?: ReadViewTransport) => {
      const result = await mocks.options(); read?.onReadView(metadata(read.readContextId)); return result;
    },
    getReviewDataRecords: async (params: { read?: ReadViewTransport }) => {
      const result = await mocks.rows(params); params.read?.onReadView(metadata(params.read.readContextId)); return result;
    },
    createReviewDataRecord: mocks.create,
    getReviewDataProblemItems: async (id: number, read?: ReadViewTransport) => {
      const result = await mocks.items(id); read?.onReadView(metadata(read.readContextId)); return result;
    },
    getReviewDataRecordDetail: async (id: number, read?: ReadViewTransport) => {
      const result = await mocks.detail(id); read?.onReadView(metadata(read.readContextId)); return result;
    },
  },
}));
vi.mock('../element-plus-services', () => ({
  ElMessage: { success: mocks.success, error: mocks.error },
  ElMessageBox: { confirm: vi.fn() },
}));
vi.mock('../composables/auth-state', () => ({
  authState: {
    currentUser: {
      authenticated: true,
      username: 'review-test',
      roleCodes: [],
      permissions: ['review.record.create'],
    },
  },
}));

import ReviewDataManagementView from './ReviewDataManagementView.vue';
import ReviewRecordFormDialog from './review-data/ReviewRecordFormDialog.vue';
import ReviewProblemItemFormDialog from './review-data/ReviewProblemItemFormDialog.vue';
import ReviewProblemItemsDialog from './review-data/ReviewProblemItemsDialog.vue';
import ReviewDataRowActions from './review-data/ReviewDataRowActions.vue';
import ReviewDataDetailDrawer from './review-data/ReviewDataDetailDrawer.vue';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

const createdDetail = {
  record: {
    id: 7,
    title: 'created',
    reviewOwner: 'owner',
    reviewExpertsSummary: '',
    projectName: 'project',
  },
  reviewExperts: [],
  problemItems: [],
  descriptions: [],
  contents: [],
};

const TableStub = defineComponent({
  props: ['rows', 'loading'],
  template: '<div><slot name="toolbar-prefix"/><slot name="primary-actions"/><section v-for="row in rows" :key="row.id"><p>{{ row.title }}</p><slot name="row-actions" :row="row"/></section><i>{{ loading }}</i></div>',
});
const ButtonStub = defineComponent({
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot/></button>',
});

let wrapper: ReturnType<typeof shallowMount> | undefined;

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  vi.spyOn(readViewsApi, 'acquire').mockImplementation(async () => metadata(crypto.randomUUID()));
  vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
  mocks.options.mockResolvedValue(createEmptyReviewDataFilterOptions());
  mocks.rows.mockResolvedValue({ records: [], total: 0, summary: null });
  mocks.create.mockResolvedValue(createdDetail);
  mocks.items.mockResolvedValue([]);
  mocks.detail.mockResolvedValue(createdDetail);
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  vi.useRealTimers();
  vi.restoreAllMocks();
});

async function startPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/review', component: ReviewDataManagementView }],
  });
  await router.push('/review');
  wrapper = shallowMount(ReviewDataManagementView, {
    global: { plugins: [router], stubs: { BaseRecordTable: TableStub, ElButton: ButtonStub } },
  });
  await flushPromises();
  await vi.advanceTimersByTimeAsync(250);
  return wrapper;
}

/** 新增一条评审记录并让问题清单的加载保持在途，返回该清单请求。 */
async function createRecordWithPendingProblemList() {
  const pending = deferred<never[]>();
  mocks.items.mockReturnValue(pending.promise);
  const view = await startPage();
  await view.findAll('button').find((button) => button.text().includes('新增评审'))!.trigger('click');
  const form = view.findComponent(ReviewRecordFormDialog);
  form.vm.$emit('submit', {
    sessionId: form.props('sessionId'),
    payload: { reviewExperts: [], title: 'created' },
  });
  await flushPromises();
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();
  expect(mocks.items).toHaveBeenCalledWith(7);
  return { view, pending };
}

describe('ReviewDataManagementView 刷新契约', () => {
  it('新详情准备完成前保留旧列表和打开的详情，失败后也不清空', async () => {
    const oldRecord = { ...createdDetail.record, title: '完整旧评审' };
    mocks.rows.mockResolvedValue({ records: [oldRecord], total: 1, summary: null });
    mocks.detail.mockResolvedValue({ ...createdDetail, record: oldRecord });
    const view = await startPage();
    const actions = view.findComponent(ReviewDataRowActions);
    await actions.props('onOpenDetail')({ __raw: oldRecord }); await flushPromises();
    expect(view.findComponent(ReviewDataDetailDrawer).props('detailData').record.title).toBe('完整旧评审');
    const pending = deferred<typeof createdDetail>();
    mocks.detail.mockReturnValue(pending.promise);
    mocks.rows.mockResolvedValue({ records: [{ ...oldRecord, title: '未接受的新评审' }], total: 1, summary: null });
    await view.findAll('button').find(button => button.text().trim() === '刷新')!.trigger('click');
    await flushPromises();
    expect(view.text()).toContain('完整旧评审');
    expect(view.text()).not.toContain('未接受的新评审');
    expect(view.findComponent(ReviewDataDetailDrawer).props('detailData').record.title).toBe('完整旧评审');
    pending.resolve({ ...createdDetail, record: { ...oldRecord, title: '完整新评审' } });
    await flushPromises(); await vi.advanceTimersByTimeAsync(250);
    expect(view.text()).toContain('未接受的新评审');
    expect(view.findComponent(ReviewDataDetailDrawer).props('detailData').record.title).toBe('完整新评审');
    mocks.rows.mockRejectedValue(new Error('后台完整发布尚未完成'));
    await view.findAll('button').find(button => button.text().trim() === '刷新')!.trigger('click');
    await flushPromises();
    expect(view.text()).toContain('未接受的新评审');
    expect(view.findComponent(ReviewDataDetailDrawer).props('detailData').record.title).toBe('完整新评审');
  });

  it('列表加载失败时不报告刷新成功', async () => {
    const view = await startPage();
    mocks.rows.mockRejectedValue({ status: 500 });

    view.findAll('button').find((button) => button.text().trim() === '刷新')!.trigger('click');
    await flushPromises();
    await vi.advanceTimersByTimeAsync(250);
    await flushPromises();

    expect(mocks.error).toHaveBeenCalledWith('评审数据加载失败，已保留当前完整数据');
    expect(mocks.success).not.toHaveBeenCalledWith('评审数据列表已刷新');
  });
});

describe('ReviewDataManagementView 新增后的后续编排', () => {
  it('问题清单关闭后不再打开问题编辑器', async () => {
    const { view, pending } = await createRecordWithPendingProblemList();
    view.findComponent(ReviewProblemItemsDialog).vm.$emit('update:visible', false);
    await flushPromises();

    pending.resolve([]);
    await flushPromises();

    expect(view.findComponent(ReviewProblemItemFormDialog).props('visible')).toBe(false);
  });

  it('整页销毁后不再发起详情请求', async () => {
    const { view, pending } = await createRecordWithPendingProblemList();
    view.unmount();
    wrapper = undefined;

    pending.resolve([]);
    await flushPromises();

    expect(mocks.detail).not.toHaveBeenCalled();
  });

  it('问题清单保持打开时仍然打开问题编辑器', async () => {
    const { view, pending } = await createRecordWithPendingProblemList();

    pending.resolve([]);
    await flushPromises();

    expect(view.findComponent(ReviewProblemItemFormDialog).props('visible')).toBe(true);
    expect(mocks.detail).toHaveBeenCalledWith(7);
  });
});
