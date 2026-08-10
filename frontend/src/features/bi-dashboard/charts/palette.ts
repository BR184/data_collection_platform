export const BI_PALETTE = {
  blue: '#5470C6',
  teal: '#73C0DE',
  green: '#91CC75',
  orange: '#FAC858',
  red: '#EE6666',
  slate: '#9A9FB0',
  text: '#1F2937',
  subtleText: '#667085',
  grid: '#EDF0F4',
  track: '#F0F2F5',
  repairOrange: '#FAC858',
  repairGreen: '#91CC75',
} as const;

export const BI_SERIES_COLORS = [
  BI_PALETTE.blue,
  BI_PALETTE.teal,
  BI_PALETTE.green,
  BI_PALETTE.orange,
  BI_PALETTE.red,
  BI_PALETTE.slate,
] as const;

/** 根据填充色的相对亮度选择黑白标签，保证柱体内文字可读。 */
export function readableTextColor(background: string): '#111827' | '#FFFFFF' {
  const normalized = background.replace('#', '');
  if (!/^[0-9a-fA-F]{6}$/.test(normalized)) {
    return '#111827';
  }
  const channels = [0, 2, 4].map((offset) => Number.parseInt(normalized.slice(offset, offset + 2), 16) / 255);
  const linear = channels.map((value) => (value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4));
  const luminance = 0.2126 * linear[0]! + 0.7152 * linear[1]! + 0.0722 * linear[2]!;
  return luminance > 0.46 ? '#111827' : '#FFFFFF';
}
