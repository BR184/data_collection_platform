<script setup lang="ts">
import type { Component } from 'vue';
import type { StatisticCellData, StatisticColumnGroup, StatisticColumnLeaf, StatisticRowData } from '../../types/api';
import type { SortDirection } from '../statistic-board-sorting';

defineProps<{
  group: StatisticColumnGroup;
  parentGroupKey: string;
  rootGroupKey: string;
  draggableGroupHeader?: boolean;
  isGroupDragging?: (parentGroupKey: string, groupKey: string) => boolean;
  onGroupDragStart?: (parentGroupKey: string, groupKey: string) => void;
  onGroupDrop?: (parentGroupKey: string, groupKey: string) => void;
  sortDirectionForColumn: (columnKey: string) => SortDirection;
  sortStateLabel: (direction: SortDirection) => string;
  sortIconForDirection: (direction: SortDirection) => Component;
  toggleColumnSort: (columnKey: string) => void;
  cellForColumn: (row: StatisticRowData, columnKey: string) => StatisticCellData | undefined;
  openDetail: (row: StatisticRowData, cell: StatisticCellData) => void | Promise<void>;
  columnMinWidth: (column: StatisticColumnLeaf) => number;
  columnResizable: (column: StatisticColumnLeaf) => boolean;
  isColumnDragging: (groupKey: string, columnKey: string) => boolean;
  onColumnDragStart: (groupKey: string, columnKey: string) => void;
  onColumnDrop: (groupKey: string, columnKey: string) => void;
  clearDragState: () => void;
}>();
</script>

<template>
  <el-table-column align="center">
    <template #header>
      <div
        v-if="draggableGroupHeader"
        class="stat-group-header"
        :class="{ dragging: isGroupDragging?.(parentGroupKey, group.key) }"
        draggable="true"
        @dragstart="onGroupDragStart?.(parentGroupKey, group.key)"
        @dragover.prevent
        @drop.prevent="onGroupDrop?.(parentGroupKey, group.key)"
        @dragend="clearDragState"
      >
        <span class="stat-header-zone stat-header-zone-left" aria-hidden="true">
          <span class="drag-handle group">
            <span></span>
            <span></span>
            <span></span>
            <span></span>
            <span></span>
            <span></span>
          </span>
        </span>
        <span class="stat-group-header-label" :title="group.label">{{ group.label }}</span>
        <span class="stat-header-zone stat-header-zone-right stat-header-zone-placeholder" aria-hidden="true"></span>
      </div>
      <span v-else class="stat-group-header-label" :title="group.label">{{ group.label }}</span>
    </template>

    <StatisticTableColumnGroup
      v-for="child in group.children ?? []"
      :key="child.key"
      :group="child"
      :parent-group-key="group.key"
      :root-group-key="rootGroupKey"
      :draggable-group-header="true"
      :is-group-dragging="isGroupDragging"
      :on-group-drag-start="onGroupDragStart"
      :on-group-drop="onGroupDrop"
      :sort-direction-for-column="sortDirectionForColumn"
      :sort-state-label="sortStateLabel"
      :sort-icon-for-direction="sortIconForDirection"
      :toggle-column-sort="toggleColumnSort"
      :cell-for-column="cellForColumn"
      :open-detail="openDetail"
      :column-min-width="columnMinWidth"
      :column-resizable="columnResizable"
      :is-column-dragging="isColumnDragging"
      :on-column-drag-start="onColumnDragStart"
      :on-column-drop="onColumnDrop"
      :clear-drag-state="clearDragState"
    />

    <el-table-column
      v-for="column in group.columns ?? []"
      :key="column.key"
      align="center"
      :min-width="columnMinWidth(column)"
      :resizable="columnResizable(column)"
    >
      <template #header>
        <div
          class="stat-column-header"
          :class="{
            dragging: isColumnDragging(rootGroupKey, column.key),
            sorting: sortDirectionForColumn(column.key) !== 'default',
          }"
          draggable="true"
          @dragstart="onColumnDragStart(rootGroupKey, column.key)"
          @dragover.prevent
          @drop.prevent="onColumnDrop(rootGroupKey, column.key)"
          @dragend="clearDragState"
        >
          <span class="stat-header-zone stat-header-zone-left" aria-hidden="true">
            <span class="drag-handle subtle">
              <span></span>
              <span></span>
              <span></span>
              <span></span>
              <span></span>
              <span></span>
            </span>
          </span>
          <span class="stat-column-header-label" :title="column.label">{{ column.label }}</span>
          <span class="stat-header-zone stat-header-zone-right">
            <button
              class="sort-trigger"
              :class="`is-${sortDirectionForColumn(column.key)}`"
              type="button"
              :title="sortStateLabel(sortDirectionForColumn(column.key))"
              @click.stop="toggleColumnSort(column.key)"
            >
              <el-icon class="sort-trigger-icon">
                <component :is="sortIconForDirection(sortDirectionForColumn(column.key))" />
              </el-icon>
              <span class="sort-trigger-state">
                {{ sortDirectionForColumn(column.key) === 'asc' ? '升序' : sortDirectionForColumn(column.key) === 'desc' ? '降序' : '排序' }}
              </span>
            </button>
          </span>
        </div>
      </template>

      <template #default="{ row }">
        <button
          v-if="cellForColumn(row, column.key)?.drilldown"
          class="stat-cell drilldown"
          @click="openDetail(row, cellForColumn(row, column.key)!)"
        >
          {{ cellForColumn(row, column.key)?.displayValue || '-' }}
        </button>
        <span v-else class="stat-cell">
          {{ cellForColumn(row, column.key)?.displayValue || '-' }}
        </span>
      </template>
    </el-table-column>
  </el-table-column>
</template>
