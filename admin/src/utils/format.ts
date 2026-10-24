// 通用格式化工具

/** 人性化显示时长（秒）：
 *  - < 1 分钟   → "XX秒"
 *  - < 1 小时   → "XX分YY秒"
 *  - < 1 天     → "XX小时YY分"
 *  - >= 1 天    → "XX天XX小时"
 */
export function formatDuration(seconds: number): string {
  if (seconds == null || isNaN(seconds)) return '-'
  const s = Math.max(0, Math.floor(seconds))
  const day = Math.floor(s / 86400)
  const hour = Math.floor((s % 86400) / 3600)
  const minute = Math.floor((s % 3600) / 60)
  const sec = s % 60

  if (day > 0) return `${day}天${hour}小时`
  if (hour > 0) return `${hour}小时${minute}分`
  if (minute > 0) return `${minute}分${sec}秒`
  return `${sec}秒`
}

/** 时间戳（秒）→ 本地时间字符串 */
export function formatTime(ts: number): string {
  if (!ts) return '-'
  return new Date(ts * 1000).toLocaleString('zh-CN')
}
