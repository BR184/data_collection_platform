import type { MirrorPurgeResult } from '../types/api';

export function buildPurgeSummaryHtml(result: MirrorPurgeResult) {
  const scopeText =
    result.scope === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
      ? '已删除白名单外镜像数据'
      : '已删除全部镜像数据';
  const syncTimeResetText = result.syncTimestampsReset ? '已重置' : '未重置';
  return [
    `<strong>${scopeText}</strong>`,
    `删除镜像表：${result.droppedMirrorTables}`,
    `清空数据表：${result.truncatedTables}`,
    `同步时间：${syncTimeResetText}`,
  ].join('<br />');
}
