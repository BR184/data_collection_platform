import { computed, ref } from 'vue';
import { describe, expect, it } from 'vitest';
import { useStatisticViewSettings } from './useStatisticViewSettings';
import type { StatisticColumnGroup } from '../types/api';

const columnGroups: StatisticColumnGroup[] = [
  {
    key: 'quality',
    label: '质量',
    columns: [
      { key: 'blocked', label: '阻塞数', drilldown: true, metricType: 'count' },
      { key: 'resolved', label: '已解决', drilldown: true, metricType: 'count' },
    ],
  },
  {
    key: 'efficiency',
    label: '效率',
    columns: [{ key: 'lead-time', label: '交付周期', drilldown: false, metricType: 'duration' }],
  },
];

describe('useStatisticViewSettings', () => {
  it('syncs drafts, toggles columns, and owns expanded group updates', () => {
    const visibleColumnKeys = ref(['blocked']);
    const settings = useStatisticViewSettings(computed(() => columnGroups), computed(() => visibleColumnKeys.value));

    settings.openSettings();

    expect(settings.settingsVisible.value).toBe(true);
    expect(settings.draftVisibleColumnKeys.value).toEqual(['blocked']);

    settings.toggleGroupColumns(columnGroups[0], true);
    expect(settings.draftVisibleColumnKeys.value).toEqual(['blocked', 'resolved']);
    expect(settings.groupCheckAllStates.value.quality).toBe(true);

    settings.handleExpandedViewSettingGroupsChange(['quality']);
    expect(settings.expandedViewSettingGroups.value).toEqual(['quality']);

    settings.closeSettings();
    expect(settings.settingsVisible.value).toBe(false);
  });
});
