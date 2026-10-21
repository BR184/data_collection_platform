import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { useAcceptedPageView } from '../../../composables/useAcceptedPageView';
import { authState } from '../../../composables/auth-state';
import { guestUser } from '../../../api-client/auth-api';
import type { ReadViewMetadata, ReadViewTransport } from '../../../types/api/read-view';
import { biPageDownloadKey } from '../data/page-download';
import type { BiChart } from '../charts/BiChart';

const exports = vi.hoisted(() => ({ png: vi.fn(), excel: vi.fn(), blob: vi.fn(), error: vi.fn() }));
vi.mock('../charts/export-chart', () => ({ exportBiChartPng: exports.png, exportBiChartExcel: exports.excel }));
vi.mock('../../../utils/csv-download', () => ({ downloadBlob: exports.blob }));
vi.mock('../../../element-plus-services', () => ({ ElMessage: { success: vi.fn(), error: exports.error } }));
import BiChartPanel from './BiChartPanel.vue';

const metadata = (id: string): ReadViewMetadata => ({ readContextId: id, pageKey: 'bi:coding', scopeKey: 'scope',
  sourceVersion: id, businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z',
  leaseExpiresAt: '2099-01-01T00:00:00Z', pendingUpdates: 0, sourceState: 'CURRENT', escalated: false });
afterEach(() => { vi.restoreAllMocks(); vi.clearAllMocks(); authState.currentUser = { ...guestUser }; });

describe('actual BI panel download acceptance', () => {
  it.each(['png', 'excel'] as const)('discards %s created after its displayed page has changed', async format => {
    authState.currentUser = { ...guestUser, username: 'reader', authenticated: true, roleCodes: ['ADMIN'], permissions: ['bi.dashboard.coding.download'] };
    let sequence = 0;
    const page = useAcceptedPageView({ pageKey: 'bi:coding',
      views: { acquire: async () => metadata(`complete-${++sequence}`), renew: async id => metadata(id), release: async () => undefined },
      load: async (_scope, read) => { read.onReadView(metadata(read.readContextId)); return 'chart'; } });
    await page.prepare({});
    let finish!: (file: unknown) => void;
    exports[format].mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const wrapper = mount(BiChartPanel, { props: { title: '完整图表', data: [1],
      chart: { hasData: () => true } as unknown as BiChart<never>,
      downloadContext: { pageKey: 'coding', chartInstanceId: 'chart', sourceVersion: 'complete-1',
        scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 10 } } },
      global: { provide: { [biPageDownloadKey as symbol]: {
        run: <R>(reader: (read: ReadViewTransport) => Promise<R>, commit: (result: R) => void) =>
          page.readAccepted((_data, read) => reader(read), commit),
      } }, stubs: { BiChartCanvas: true, ElTooltip: { inheritAttrs: false, template: '<slot />' },
        ElDropdown: defineComponent({ emits: ['command'], template: '<button @click="$emit(\'command\',\'png\')">PNG</button><button @click="$emit(\'command\',\'excel\')">Excel</button>' }) } } });
    try {
      await wrapper.findAll('button').find(button => button.text() === (format === 'png' ? 'PNG' : 'Excel'))!.trigger('click');
      await flushPromises();
      expect(exports[format]).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ readContextId: 'complete-1' }));
      await page.prepare({});
      finish(format === 'png' ? { url: 'data:image/png;base64,AAAA', filename: 'chart.png' }
        : { blob: new Blob(['bytes']), filename: 'chart.xlsx' });
      await flushPromises();
      expect(click).not.toHaveBeenCalled(); expect(exports.blob).not.toHaveBeenCalled();
      expect(exports.error).toHaveBeenCalledWith(expect.stringContaining('页面已换版'));
    } finally { wrapper.unmount(); page.dispose(); }
  });
});
