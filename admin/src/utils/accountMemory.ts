/**
 * 「记住账号 / 自动登录」账号记忆（2026-11-04 用户需求，类 QQ 登录体验）。
 *
 * 设计要点：
 * 1. 记住多组账号密码（localStorage 持久化），登录页下拉选择、可删除。
 * 2. 「自动登录」是独立开关：勾选后下次打开管理后台自动用记住的密码换新 token；
 *    **手动点「退出登录」时清除该开关**，否则用户永远退不出去。
 * 3. 密码不落明文——用 base64 做轻量混淆（非加密）。管理后台为内网运维工具，
 *    此处仅为「防止随手一瞥看到明文」，无法抵御本机恶意读取；如需强安全应改用
 *    系统凭据/后端会话而非前端记忆。
 */

const ACCOUNTS_KEY = 'bridge_admin_accounts'
const AUTO_LOGIN_KEY = 'bridge_admin_auto_login'
const LAST_USER_KEY = 'bridge_admin_last_user'

export interface SavedAccount {
  username: string
  /** base64 混淆后的密码 */
  password: string
  /** 最后使用时间戳，用于排序（最近使用靠前） */
  ts: number
}

function encodePwd(plain: string): string {
  try {
    return btoa(unescape(encodeURIComponent(plain)))
  } catch {
    return plain
  }
}

function decodePwd(encoded: string): string {
  try {
    return decodeURIComponent(escape(atob(encoded)))
  } catch {
    return encoded
  }
}

function readAccounts(): SavedAccount[] {
  try {
    const raw = localStorage.getItem(ACCOUNTS_KEY)
    const arr = raw ? (JSON.parse(raw) as SavedAccount[]) : []
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function writeAccounts(list: SavedAccount[]) {
  try {
    localStorage.setItem(ACCOUNTS_KEY, JSON.stringify(list))
  } catch {
    /* 隐私模式写入失败静默忽略 */
  }
}

/** 读取全部记住的账号（密码解码为明文，供登录使用） */
export function listAccounts(): SavedAccount[] {
  return readAccounts()
    .slice()
    .sort((a, b) => (b.ts || 0) - (a.ts || 0))
}

/** 保存/更新一组账号（已存在则更新密码与时间戳） */
export function saveAccount(username: string, password: string) {
  const list = readAccounts().filter((a) => a.username !== username)
  list.push({ username, password: encodePwd(password), ts: Date.now() })
  writeAccounts(list)
}

/** 删除一组记住的账号 */
export function removeAccount(username: string) {
  const list = readAccounts().filter((a) => a.username !== username)
  writeAccounts(list)
  // 若删的是上次登录用户，清掉记忆
  if (getLastUser() === username) clearLastUser()
}

/** 按用户名取明文密码（未记住返回 null） */
export function getPassword(username: string): string | null {
  const a = readAccounts().find((x) => x.username === username)
  return a ? decodePwd(a.password) : null
}

// ---------- 自动登录开关 ----------
export function isAutoLogin(): boolean {
  return localStorage.getItem(AUTO_LOGIN_KEY) === '1'
}

export function setAutoLogin(on: boolean) {
  if (on) localStorage.setItem(AUTO_LOGIN_KEY, '1')
  else localStorage.removeItem(AUTO_LOGIN_KEY)
}

// ---------- 上次登录用户 ----------
export function getLastUser(): string {
  return localStorage.getItem(LAST_USER_KEY) || ''
}

export function setLastUser(username: string) {
  if (username) localStorage.setItem(LAST_USER_KEY, username)
  else localStorage.removeItem(LAST_USER_KEY)
}

function clearLastUser() {
  localStorage.removeItem(LAST_USER_KEY)
}
