export type ExportProgressProfile =
  | 'record'
  | 'illegal'
  | 'codeReview'
  | 'review'
  | 'statistic'
  | 'heavyStatistic'
  | 'template';

export interface ExportProgressOptions {
  label?: string;
  rowCount?: number;
  workload?: number;
  profile?: ExportProgressProfile;
  endpointKey?: string;
}

export interface ExportProgressEventDetail {
  id: string;
  url: string;
  options?: ExportProgressOptions;
  errorMessage?: string;
}

export const EXPORT_PROGRESS_BEGIN_EVENT = 'platform-export-progress-begin';
export const EXPORT_PROGRESS_FINISH_EVENT = 'platform-export-progress-finish';
export const EXPORT_PROGRESS_FAIL_EVENT = 'platform-export-progress-fail';

let nextExportProgressId = 1;

export function beginExportProgress(url: string, options?: ExportProgressOptions | false) {
  if (options === false || typeof window === 'undefined') {
    return '';
  }
  const id = `export-${Date.now()}-${nextExportProgressId++}`;
  dispatchExportProgressEvent(EXPORT_PROGRESS_BEGIN_EVENT, { id, url, options });
  return id;
}

export function finishExportProgress(id: string, url: string, options?: ExportProgressOptions | false) {
  if (!id || options === false) {
    return;
  }
  dispatchExportProgressEvent(EXPORT_PROGRESS_FINISH_EVENT, { id, url, options });
}

export function failExportProgress(
  id: string,
  url: string,
  options: ExportProgressOptions | false | undefined,
  error: unknown,
) {
  if (!id || options === false) {
    return;
  }
  dispatchExportProgressEvent(EXPORT_PROGRESS_FAIL_EVENT, {
    id,
    url,
    options,
    errorMessage: error instanceof Error ? error.message : '导出失败',
  });
}

function dispatchExportProgressEvent(type: string, detail: ExportProgressEventDetail) {
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(new CustomEvent<ExportProgressEventDetail>(type, { detail }));
}
