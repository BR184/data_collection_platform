import type { InjectionKey } from 'vue';
import type { ReadViewTransport } from '../../../types/api/read-view';

/** 页面提供作用域内的下载边界，图表不能自行取得最新来源替换已显示版本。 */
export interface BiPageDownload {
  run<R>(reader: (read: ReadViewTransport) => Promise<R>, commit: (result: R) => void): Promise<R>;
}
export const biPageDownloadKey: InjectionKey<BiPageDownload> = Symbol('bi-page-download');
