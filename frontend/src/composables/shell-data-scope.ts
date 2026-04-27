import { reactive } from 'vue';
import type { DataScopeOption, DataScopeProvider } from '../types/data-scope';

export interface ShellDataScopeRegistration {
  provider: DataScopeProvider;
  options: DataScopeOption[];
  modelValue: string;
  summary?: string;
  loading?: boolean;
  onChange: (value: string) => void | Promise<void>;
}

export const shellDataScopeState = reactive<{
  token: string;
  registration: ShellDataScopeRegistration | null;
}>({
  token: '',
  registration: null,
});

export function registerShellDataScope(token: string, registration: ShellDataScopeRegistration) {
  shellDataScopeState.token = token;
  shellDataScopeState.registration = registration;
}

export function clearShellDataScope(token: string) {
  if (shellDataScopeState.token !== token) {
    return;
  }
  shellDataScopeState.token = '';
  shellDataScopeState.registration = null;
}
