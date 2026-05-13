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

export function formatBeijingDateTime(value?: string | null, emptyText = '-') {
  if (!value) {
    return emptyText;
  }
  if (!OFFSET_SUFFIX_PATTERN.test(value)) {
    return value.replace('T', ' ').slice(0, 19);
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value.replace('T', ' ').slice(0, 19);
  }
  const parts = Object.fromEntries(
    beijingDateTimeFormatter
      .formatToParts(date)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}
