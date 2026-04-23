import { reactive } from 'vue';

export const routerState = reactive({
  routeLoading: false,
  routeError: '',
});

const ROUTE_LOADING_DELAY_MS = 160;

let routeLoadingTimer: number | null = null;

export function beginRouteLoading(immediate = false) {
  clearRouteLoading();
  if (immediate) {
    routerState.routeLoading = true;
    return;
  }
  routeLoadingTimer = window.setTimeout(() => {
    routerState.routeLoading = true;
    routeLoadingTimer = null;
  }, ROUTE_LOADING_DELAY_MS);
}

export function endRouteLoading() {
  clearRouteLoading();
  routerState.routeLoading = false;
}

export function clearRouteError() {
  routerState.routeError = '';
}

export function setRouteError(message: string) {
  routerState.routeError = message;
}

function clearRouteLoading() {
  if (routeLoadingTimer != null) {
    window.clearTimeout(routeLoadingTimer);
    routeLoadingTimer = null;
  }
}
