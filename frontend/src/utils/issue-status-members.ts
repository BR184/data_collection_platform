const STATUS_MEMBER_SEPARATOR = /[、，,&]/;

/**
 * 将议题原始测试状态文本解析为有序、去重的可展示状态标签。
 *
 * 顿号、逗号和 `&` 是历史数据中的成员分隔符；斜杠属于状态名称本身，
 * 因而“已修复/完成”不会被拆开。
 */
export function parseIssueStatusMembers(value: string | null | undefined): string[] {
  const rawStatus = value?.trim();
  if (!rawStatus) {
    return [];
  }
  const members = new Set<string>();
  for (const item of rawStatus.split(STATUS_MEMBER_SEPARATOR)) {
    const member = item.trim();
    if (member) {
      members.add(member);
    }
  }
  return [...members];
}
