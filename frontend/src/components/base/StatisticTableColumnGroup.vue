<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import SmartTableHeader from './SmartTableHeader.vue';
import type { StatisticCellData, StatisticColumnGroup, StatisticColumnLeaf, StatisticRowData } from '../../types/api';
import type { SortDirection } from '../statistic-board-sorting';

const props = defineProps<{
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

function normalizedHeaderText(value: string) {
  return String(value ?? '').replace(/\s+/g, '').trim();
}

function redundantSingleLeafColumn(group: StatisticColumnGroup) {
  const columns = group.columns ?? [];
  const children = group.children ?? [];
  if (children.length || columns.length !== 1) {
    return null;
  }
  const column = columns[0];
  return normalizedHeaderText(group.label) === normalizedHeaderText(column.label) ? column : null;
}

function canOpenDetail(cell: StatisticCellData | undefined) {
  return Boolean(cell?.drilldown && Number(cell.numericValue) > 0);
}

const redundantLeafColumn = computed(() => redundantSingleLeafColumn(props.group));
</script>

<template>
  <el-table-column
    v-if="redundantLeafColumn"
    :key="redundantLeafColumn.key"
    align="center"
    :width="columnMinWidth(redundantLeafColumn)"
    :min-width="columnMinWidth(redundantLeafColumn)"
    :resizable="columnResizable(redundantLeafColumn)"
  >
    <template #header>
      <div
        class="stat-column-header"
        :class="{
          dragging: isColumnDragging(rootGroupKey, redundantLeafColumn.key),
          sorting: sortDirectionForColumn(redundantLeafColumn.key) !== 'default',
        }"
        draggable="true"
        @dragstart="onColumnDragStart(rootGroupKey, redundantLeafColumn.key)"
        @dragover.prevent
        @drop.prevent="onColumnDrop(rootGroupKey, redundantLeafColumn.key)"
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
        <span class="stat-column-header-label">
          <SmartTableHeader :label="redundantLeafColumn.label" prefer-stacked />
        </span>
        <span class="stat-header-zone stat-header-zone-right">
          <button
            class="sort-trigger"
            :class="`is-${sortDirectionForColumn(redundantLeafColumn.key)}`"
            type="button"
            :title="sortStateLabel(sortDirectionForColumn(redundantLeafColumn.key))"
            @click.stop="toggleColumnSort(redundantLeafColumn.key)"
          >
            <el-icon class="sort-trigger-icon">
              <component :is="sortIconForDirection(sortDirectionForColumn(redundantLeafColumn.key))" />
            </el-icon>
            <span class="sort-trigger-state">
              {{ sortDirectionForColumn(redundantLeafColumn.key) === 'asc' ? '升序' : sortDirectionForColumn(redundantLeafColumn.key) === 'desc' ? '降序' : '排序' }}
            </span>
          </button>
        </span>
      </div>
    </template>

    <template #default="{ row }">
      <button
        v-if="canOpenDetail(cellForColumn(row, redundantLeafColumn.key))"
        class="stat-cell drilldown"
        @click="openDetail(row, cellForColumn(row, redundantLeafColumn.key)!)"
      >
        {{ cellForColumn(row, redundantLeafColumn.key)?.displayValue || '-' }}
      </button>
      <span v-else class="stat-cell">
        {{ cellForColumn(row, redundantLeafColumn.key)?.displayValue || '-' }}
      </span>
    </template>
  </el-table-column>

  <el-table-column v-else align="center">
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
        <span class="stat-group-header-label">
          <SmartTableHeader :label="group.label" prefer-stacked />
        </span>
        <span class="stat-header-zone stat-header-zone-right stat-header-zone-placeholder" aria-hidden="true"></span>
      </div>
      <span v-else class="stat-group-header-label">
        <SmartTableHeader :label="group.label" prefer-stacked />
      </span>
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
      :width="columnMinWidth(column)"
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
          <span class="stat-column-header-label">
            <SmartTableHeader :label="column.label" prefer-stacked />
          </span>
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
          v-if="canOpenDetail(cellForColumn(row, column.key))"
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
