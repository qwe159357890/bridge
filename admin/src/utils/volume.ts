// get_volume 回执文本解析（命令页参数区实时音量 + VolumeResultCard 共用）。
// 契约：客户端 VolumeController.snapshotMessage 输出格式
//   「音量：媒体 8/15（53%），铃声 10/15（67%），…；铃声模式 响铃[；免打扰已开启…]」
// 改动客户端文案必须同步本解析。

export interface VolumeStreamSnapshot {
  key: string
  name: string
  current: number
  max: number
  pct: number
}

export interface VolumeSnapshot {
  streams: VolumeStreamSnapshot[]
  ringer: string
  dnd: boolean
}

const NAME_TO_KEY: Record<string, string> = {
  媒体: 'media',
  铃声: 'ring',
  闹钟: 'alarm',
  通知: 'notification',
  系统: 'system',
}

/** 解析 get_volume 回执文本；无法解析出任何流时返回 null。 */
export function parseVolumeMessage(message: string): VolumeSnapshot | null {
  const re = /(媒体|铃声|闹钟|通知|系统) (\d+)\/(\d+)（(\d+)%）/g
  const streams: VolumeStreamSnapshot[] = []
  let m: RegExpExecArray | null
  while ((m = re.exec(message)) !== null) {
    streams.push({
      key: NAME_TO_KEY[m[1]] ?? m[1],
      name: m[1],
      current: Number(m[2]),
      max: Number(m[3]),
      pct: Number(m[4]),
    })
  }
  if (streams.length === 0) return null
  const ringerMatch = message.match(/铃声模式 (\S+)/)
  return {
    streams,
    ringer: ringerMatch ? ringerMatch[1] : '',
    dnd: message.includes('免打扰已开启'),
  }
}

/** 取指定流的人类可读当前值（如「10/15（67%）」）；快照无该流返回空串。 */
export function volumeCurrentText(snapshot: VolumeSnapshot | null, key: string): string {
  const s = snapshot?.streams.find((x) => x.key === key)
  return s ? `${s.current}/${s.max}（${s.pct}%）` : ''
}
