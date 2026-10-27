// Playwright 驱动：打开测试页 → 等待推流结束 → 汇总解码帧数曲线
// 用法：node tools/debug/run_player_test.mjs "<页面file:// URL>" [等待秒]
import { chromium } from 'playwright';

const url = process.argv[2];
const waitSec = Number(process.argv[3] || 20);
if (!url) { console.error('用法: node run_player_test.mjs <file:// URL> [等待秒]'); process.exit(1); }

const browser = await chromium.launch({ channel: 'chrome', headless: true }).catch(() => chromium.launch({ headless: true }));
const page = await browser.newPage();
const logs = [];
page.on('console', (m) => { const t = m.text(); logs.push(t); if (t.includes('[TEST]') || t.includes('mpegts')) console.log('[console]', t.slice(0, 300)); });
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 300)));

await page.goto(url);
await page.waitForTimeout(waitSec * 1000);

const stats = await page.evaluate(() => window.__stats || []);
const interesting = stats.filter((_, i) => i % 4 === 0); // 每 2 秒取一点
console.log('\n=== 解码帧数曲线（每 2 秒） ===');
for (const s of interesting) console.log(`t=${s.t}s 解码=${s.decoded} 掉帧=${s.dropped} cur=${s.cur} bufEnd=${s.bufEnd} ready=${s.ready}`);
const last = stats[stats.length - 1];
console.log(`\n最终: 解码=${last?.decoded} 掉帧=${last?.dropped} cur=${last?.cur} bufEnd=${last?.bufEnd}`);
console.log(`结论: ${last && last.decoded > (waitSec * 15) ? '✅ 解码正常' : '❌ 复现解码停滞'}`);
await browser.close();
