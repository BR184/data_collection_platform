import type { Component } from 'vue';

export interface StatisticBoardToolbarAction {
  key: string;
  label: string;
  icon?: Component;
  loading?: boolean;
  plain?: boolean;
  disabled?: boolean;
}

export interface StatisticBoardUiHooks {
  rootClass?: string;
  cardClass?: string;
  toolbarClass?: string;
  toolbarMainClass?: string;
  toolbarActionsClass?: string;
  tableClass?: string;
  detailTableClass?: string;
  settingsPanelClass?: string;
}
