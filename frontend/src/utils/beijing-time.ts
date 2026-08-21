const BEIJING_TIME_ZONE = 'Asia/Shanghai';
const OFFSET_SUFFIX_PATTERN = /(z|[+-]\d{2}:?\d{2})$/i;

const beijingDateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: BEIJING_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

function formatLocalDateTimePrefix(value: string | null | undefined, length: number, emptyText: string) {
  return value ? value.replace('T', ' ').slice(0, length) : emptyText;
}

/** 将不带时区的 ISO 日期时间按页面既有约定转换为文本。 */
export function formatLocalDateTime(value?: string | null, emptyText = '-') {
  return formatLocalDateTimePrefix(value, 19, emptyText);
}

/** 将不带时区的 ISO 日期时间按分钟显示，不执行时区转换。 */
export function formatLocalDateTimeMinute(value?: string | null, emptyText = '-') {
  return formatLocalDateTimePrefix(value, 16, emptyText);
}

/** 将日期文本截取为页面使用的年月日。 */
export function formatLocalDate(value?: string | null, emptyText = '-') {
  return value ? value.slice(0, 10) : emptyText;
}

/** 将带时区的日期时间转换为北京时间；无法解析时保留原始文本的本地显示形式。 */
export function formatBeijingDateTime(value?: string | null, emptyText = '-') {
  if (!value) {
    return emptyText;
  }
  if (!OFFSET_SUFFIX_PATTERN.test(value)) {
    return formatLocalDateTime(value, emptyText);
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return formatLocalDateTime(value, emptyText);
  }
  const parts = Object.fromEntries(
    beijingDateTimeFormatter
      .formatToParts(date)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}
