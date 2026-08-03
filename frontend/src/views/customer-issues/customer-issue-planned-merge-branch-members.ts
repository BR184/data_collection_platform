const PLANNED_MERGE_BRANCH_MEMBER_SEPARATOR = /[&,，、]/;

/**
 * 将计划合并版本分支事实原文解析为有序、去重的展示与筛选成员。
 *
 * `&`、半角逗号、全角逗号和顿号是来源文本中的列表分隔符；斜杠、下划线、
 * 连字符、点号和冒号属于分支名本身，不参与拆分。
 */
export function parseCustomerIssuePlannedMergeBranchMembers(
  value?: string | null,
): string[] {
  const rawValue = value?.trim();
  if (!rawValue) {
    return [];
  }
  const members = new Set<string>();
  for (const item of rawValue.split(PLANNED_MERGE_BRANCH_MEMBER_SEPARATOR)) {
    const member = item.trim();
    if (member) {
      members.add(member);
    }
  }
  return [...members];
}
