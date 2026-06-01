export interface CollectFormDetailResponse {
  id: number;
  gitlabBaseUrl: string;
  projectId: number;
  requestIid?: number | null;
  resourceType: string;
  resourceId: string;
  templateCode: string;
  formTitle: string;
  reviewer: string;
  reviewDurationMinutes: number;
  specificationScore: number;
  logicScore: number;
  performanceScore: number;
  designScore: number;
  otherScore: number;
  remark?: string | null;
  deleted: boolean;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface CollectFormNotificationPayloadResponse {
  sourceAddress: string;
  projectId: number;
  requestIid: number;
  resourceType: string;
}
