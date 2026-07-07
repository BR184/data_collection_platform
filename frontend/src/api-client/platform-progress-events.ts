export type PlatformProgressProfile =
  | 'route'
  | 'table'
  | 'statistic'
  | 'filter'
  | 'refresh'
  | 'export'
  | 'heavyExport'
  | 'template'
  | 'background';

export interface PlatformProgressOptions {
  label?: string;
  rowCount?: number;
  workload?: number;
  profile?: PlatformProgressProfile;
  endpointKey?: string;
  showDelayMs?: number;
  learnDuration?: boolean;
}

export interface PlatformProgressEventDetail {
  id: string;
  source: string;
  options?: PlatformProgressOptions;
  errorMessage?: string;
}

export const PLATFORM_PROGRESS_BEGIN_EVENT = 'platform-progress-begin';
export const PLATFORM_PROGRESS_FINISH_EVENT = 'platform-progress-finish';
export const PLATFORM_PROGRESS_FAIL_EVENT = 'platform-progress-fail';

let nextPlatformProgressId = 1;

export function beginPlatformProgress(source: string, options?: PlatformProgressOptions | false) {
  if (options === false || typeof window === 'undefined') {
    return '';
  }
  const id = `${options?.profile ?? 'task'}-${Date.now()}-${nextPlatformProgressId++}`;
  dispatchPlatformProgressEvent(PLATFORM_PROGRESS_BEGIN_EVENT, { id, source, options });
  return id;
}

export function finishPlatformProgress(
  id: string,
  source: string,
  options?: PlatformProgressOptions | false,
) {
  if (!id || options === false) {
    return;
  }
  dispatchPlatformProgressEvent(PLATFORM_PROGRESS_FINISH_EVENT, { id, source, options });
}

export function failPlatformProgress(
  id: string,
  source: string,
  options: PlatformProgressOptions | false | undefined,
  error: unknown,
) {
  if (!id || options === false) {
    return;
  }
  dispatchPlatformProgressEvent(PLATFORM_PROGRESS_FAIL_EVENT, {
    id,
    source,
    options,
    errorMessage: error instanceof Error ? error.message : '任务失败',
  });
}

function dispatchPlatformProgressEvent(type: string, detail: PlatformProgressEventDetail) {
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(new CustomEvent<PlatformProgressEventDetail>(type, { detail }));
}
