import type { LocationQuery, LocationQueryRaw } from 'vue-router';
import {
  CC_PRODUCT_RECORD_RANGE_FILTER_KEYS,
  CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS,
  DELAY_RECORD_RANGE_FILTER_KEYS,
  type CustomerIssueRecordRangeFilterKey,
} from '../../feature-manifest/customer-issue-record-query-contract';
import type { RecordTableNumberRangeValue } from '../../types/record-table';

type RangeValue = string[] | RecordTableNumberRangeValue;

/** 返回当前客户问题记录专题支持的范围筛选键。 */
export function customerIssueRecordRangeFilterKeys(
  isDelayTopic: boolean,
): readonly CustomerIssueRecordRangeFilterKey[] {
  return isDelayTopic ? DELAY_RECORD_RANGE_FILTER_KEYS : CC_PRODUCT_RECORD_RANGE_FILTER_KEYS;
}

/** 从路由查询恢复当前专题的日期和数值范围值。 */
export function readCustomerIssueRecordRangeValues(
  query: LocationQuery,
  isDelayTopic: boolean,
): Record<CustomerIssueRecordRangeFilterKey, RangeValue> {
  const values = {} as Record<CustomerIssueRecordRangeFilterKey, RangeValue>;
  for (const key of Object.keys(CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS) as CustomerIssueRecordRangeFilterKey[]) {
    const range = CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS[key];
    const start = String(query[range.startKey] ?? '').trim();
    const end = String(query[range.endKey] ?? '').trim();
    if (!customerIssueRecordRangeFilterKeys(isDelayTopic).includes(key)) {
      values[key] = range.valueType === 'number' ? [null, null] : [];
    } else if (range.valueType === 'number') {
      values[key] = [routeNumber(start), routeNumber(end)];
    } else {
      values[key] = start && end ? [start, end] : [];
    }
  }
  return values;
}

/** 构建一个范围控件变更对应的路由补丁。 */
export function buildCustomerIssueRecordRangeQueryPatch(
  key: CustomerIssueRecordRangeFilterKey,
  value: unknown,
): LocationQueryRaw {
  const range = CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS[key];
  const [start, end] = Array.isArray(value) ? value : [];
  return {
    page: 1,
    [range.startKey]: routeScalar(start),
    [range.endKey]: routeScalar(end),
  };
}

/** 将当前专题支持的范围路由值转换为 API 查询参数。 */
export function buildCustomerIssueRecordRangeRequestParams(
  query: LocationQuery,
  isDelayTopic: boolean,
): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  for (const key of customerIssueRecordRangeFilterKeys(isDelayTopic)) {
    const range = CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS[key];
    const start = String(query[range.startKey] ?? '').trim();
    const end = String(query[range.endKey] ?? '').trim();
    if (range.valueType === 'number') {
      const numericStart = routeNumber(start);
      const numericEnd = routeNumber(end);
      if (numericStart != null) {
        params[range.startKey] = numericStart;
      }
      if (numericEnd != null) {
        params[range.endKey] = numericEnd;
      }
      continue;
    }
    if (start) {
      params[range.startKey] = start;
    }
    if (end) {
      params[range.endKey] = end;
    }
  }
  return params;
}

function routeNumber(value: string) {
  const parsed = Number(value);
  return value && Number.isFinite(parsed) ? parsed : null;
}

function routeScalar(value: unknown) {
  return value == null || value === '' ? null : String(value);
}
