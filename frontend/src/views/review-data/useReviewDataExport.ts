import { ref } from 'vue';

export interface ReviewDataExportDependencies {
  exportReviewRecords: () => Promise<Blob>;
  exportProblemDetails: () => Promise<Blob>;
  downloadTemplate: () => Promise<Blob>;
  downloadWorkbook: (blob: Blob, filename: string) => void;
  now?: () => Date;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useReviewDataExport(deps: ReviewDataExportDependencies) {
  const recordExportLoading = ref(false);
  const problemExportLoading = ref(false);
  const templateDownloadLoading = ref(false);

  async function exportReviewRecords() {
    recordExportLoading.value = true;
    try {
      const blob = await deps.exportReviewRecords();
      deps.downloadWorkbook(blob, `评审数据${(deps.now?.() ?? new Date()).toLocaleString()}.xlsx`);
      deps.notifySuccess('已导出评审列表');
    } catch (error) {
      deps.notifyError(error instanceof Error ? error.message : '评审列表导出失败');
    } finally {
      recordExportLoading.value = false;
    }
  }

  async function exportProblemDetails() {
    problemExportLoading.value = true;
    try {
      const blob = await deps.exportProblemDetails();
      deps.downloadWorkbook(blob, '评审问题详情.xls');
      deps.notifySuccess('已导出问题列表');
    } catch (error) {
      deps.notifyError(error instanceof Error ? error.message : '评审问题详情导出失败');
    } finally {
      problemExportLoading.value = false;
    }
  }

  async function downloadTemplate() {
    templateDownloadLoading.value = true;
    try {
      const blob = await deps.downloadTemplate();
      deps.downloadWorkbook(blob, '模板文件.xls');
      deps.notifySuccess('已下载评审模板');
    } catch (error) {
      deps.notifyError(error instanceof Error ? error.message : '评审模板下载失败');
    } finally {
      templateDownloadLoading.value = false;
    }
  }

  return {
    recordExportLoading,
    problemExportLoading,
    templateDownloadLoading,
    exportReviewRecords,
    exportProblemDetails,
    downloadTemplate,
  };
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
