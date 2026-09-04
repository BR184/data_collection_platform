export function normalizeLabelGroupFieldKey(value: string | null | undefined) {
  const normalized = String(value ?? '').trim();
  switch (normalized) {
    case '模块':
    case '模块名':
    case '模块名称':
    case 'module':
    case 'moduleName':
    case 'moduleNames':
      return 'moduleName';
    case '评审负责人':
    case 'reviewOwner':
      return 'reviewOwner';
    case '评审专家':
    case 'reviewExpert':
      return 'reviewExpert';
    case '项目':
    case 'project':
    case 'projectName':
      return 'projectName';
    case '客户问题处理人':
    case 'assigneeName':
      return 'assigneeName';
    default:
      return normalized;
  }
}

export function sameLabelGroupFieldKey(left: string | null | undefined, right: string | null | undefined) {
  const normalizedLeft = normalizeLabelGroupFieldKey(left);
  const normalizedRight = normalizeLabelGroupFieldKey(right);
  return normalizedLeft !== '' && normalizedLeft === normalizedRight;
}
