// Renders one 1600x900 title card per clip. The card becomes the clip's FIRST FRAME, which
// is also the still PowerPoint shows before you press play — so a black or mid-scroll
// thumbnail is what you get without it. Typography/colours mirror build-deck.mjs's tokens.
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import fs from 'node:fs'
import { CLIPS } from './clips.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const require = createRequire(path.join(HERE, '../frontend/package.json'))
const { chromium } = require('playwright')
const OUT = path.join(HERE, '.video/title')
const CHROME = process.env.PI_CHROME ??
  path.join(process.env.HOME, '.cache/ms-playwright/chromium-1234/chrome-linux64/chrome')

const card = (c) => `<!doctype html><meta charset="utf-8"><style>
  html,body{margin:0;width:1600px;height:900px;background:#fff;
    font-family:Carlito,Calibri,"DejaVu Sans",sans-serif;-webkit-font-smoothing:antialiased}
  .bar{position:absolute;left:0;top:0;width:34px;height:900px;background:#1F3864}
  .wrap{position:absolute;left:150px;top:286px;width:1290px}
  .kick{font-size:23px;font-weight:700;color:#2E75B6;letter-spacing:3px;text-transform:uppercase}
  h1{font-size:62px;line-height:1.13;font-weight:300;color:#1F3864;margin:20px 0 0}
  .rule{width:190px;height:5px;background:#2E75B6;margin:34px 0 28px}
  p{font-size:26px;line-height:1.45;color:#3B3B3B;margin:0;max-width:1180px}
  .foot{position:absolute;left:150px;bottom:64px;font-size:19px;color:#767171}
  .dot{display:inline-block;width:11px;height:11px;border-radius:50%;background:#2E75B6;
       margin-right:11px;vertical-align:1px}
</style>
<div class="bar"></div>
<div class="wrap">
  <div class="kick">${c.kicker}</div>
  <h1>${c.title}</h1>
  <div class="rule"></div>
  <p>${c.sub}</p>
</div>
<div class="foot"><span class="dot"></span>Recorded against the live demo environment — real engines, real data, nothing simulated.</div>`

fs.mkdirSync(OUT, { recursive: true })
const browser = await chromium.launch({ executablePath: CHROME })
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } })
for (const c of CLIPS) {
  await page.setContent(card(c), { waitUntil: 'load' })
  await page.screenshot({ path: path.join(OUT, c.name + '.png') })
  console.log('title card:', c.name)
}
await browser.close()
