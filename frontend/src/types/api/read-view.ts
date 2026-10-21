/** 完整页面读取身份；生产状态只用于披露，不决定旧完整数据能否显示。 */
export interface ReadViewMetadata {
  readContextId: string;
  pageKey: string;
  scopeKey: string;
  sourceVersion: string;
  businessDate: string;
  leaseExpiresAt: string;
  dataAsOf: string;
  pendingUpdates: number;
  sourceState: 'CURRENT' | 'UPDATING' | 'NEWER_AVAILABLE' | 'ATTENTION';
  sourceStateDetail?: string | null;
  escalated: boolean;
}

/** 传输身份显式交给每个请求，不能从当前路由或全局可变状态猜测。 */
export interface ReadViewTransport {
  readContextId: string;
  onReadView: (metadata: ReadViewMetadata) => void;
  readScope: Readonly<Record<string, string>>;
  signal?: AbortSignal;
}
