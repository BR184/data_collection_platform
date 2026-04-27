import { ref } from 'vue';
import type { ReviewDataRecordListResponse, ReviewDataRecordRowResponse } from '../../types/api';

export interface ReviewDataExportDependencies {
  fetchRecords: (page: number, size: number) => Promise<ReviewDataRecordListResponse>;
  buildCsv: (rows: ReviewDataRecordRowResponse[]) => string;
  downloadCsv: (csv: string, filename: string) => void;
  getExpectedTotal: () => number;
  now?: () => Date;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useReviewDataExport(deps: ReviewDataExportDependencies) {
  const exportLoading = ref(false);

  async function exportExcel() {
    exportLoading.value = true;
    try {
      const exportRows: ReviewDataRecordRowResponse[] = [];
      let nextPage = 1;
      let expectedTotal = Math.max(deps.getExpectedTotal(), 0);
      do {
        const response = await deps.fetchRecords(nextPage, 100);
        exportRows.push(...response.records);
        expectedTotal = response.total;
        nextPage += 1;
      } while (exportRows.length < expectedTotal && nextPage < 1000);

      const csv = deps.buildCsv(exportRows);
      deps.downloadCsv(csv, `评审数据管理_${formatExportFileDate((deps.now ?? (() => new Date()))())}.csv`);
      deps.notifySuccess(`已导出 ${exportRows.length} 条评审记录`);
    } catch (error) {
      deps.notifyError(error instanceof Error ? error.message : '评审数据导出失败');
    } finally {
      exportLoading.value = false;
    }
  }

  return {
    exportLoading,
    exportExcel,
  };
}

export function downloadCsv(csv: string, filename: string) {
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

export function formatExportFileDate(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0');
  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate()),
    pad(date.getHours()),
    pad(date.getMinutes()),
    pad(date.getSeconds()),
  ].join('');
}
