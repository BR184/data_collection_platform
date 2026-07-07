import { reactive } from 'vue';
import {
  beginPlatformProgress,
  failPlatformProgress,
  finishPlatformProgress,
} from './api-client/platform-progress-events';

export const routerState = reactive({
  routeLoading: false,
  routeError: '',
});

const ROUTE_LOADING_DELAY_MS = 160;

let routeLoadingTimer: number | null = null;
let routeProgressId = '';

export function beginRouteLoading(immediate = false) {
  clearRouteLoading();
  if (immediate) {
    routerState.routeLoading = true;
    beginRouteProgress();
    return;
  }
  routeLoadingTimer = window.setTimeout(() => {
    routerState.routeLoading = true;
    beginRouteProgress();
    routeLoadingTimer = null;
  }, ROUTE_LOADING_DELAY_MS);
}

export function endRouteLoading() {
  clearRouteLoading();
  routerState.routeLoading = false;
  finishRouteProgress();
}

export function clearRouteError() {
  routerState.routeError = '';
}

export function setRouteError(message: string) {
  routerState.routeError = message;
  failRouteProgress(new Error(message));
}

function clearRouteLoading() {
  if (routeLoadingTimer != null) {
    window.clearTimeout(routeLoadingTimer);
    routeLoadingTimer = null;
  }
}

function beginRouteProgress() {
  if (routeProgressId) {
    return;
  }
  routeProgressId = beginPlatformProgress('route', {
    label: '正在打开页面',
    profile: 'route',
    endpointKey: 'route',
    showDelayMs: 120,
  });
}

function finishRouteProgress() {
  if (!routeProgressId) {
    return;
  }
  finishPlatformProgress(routeProgressId, 'route', { profile: 'route' });
  routeProgressId = '';
}

function failRouteProgress(error: Error) {
  if (!routeProgressId) {
    return;
  }
  failPlatformProgress(routeProgressId, 'route', { profile: 'route' }, error);
  routeProgressId = '';
}
