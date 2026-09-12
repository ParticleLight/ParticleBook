
import { readFileSync, readdirSync } from 'fs'
const CR = String.fromCharCode(13)
const R = 'F:/VScode/Read/src/renderer/i18n/locales/'
let all = ''
for (const f of readdirSync(R)) all += readFileSync(R + f, 'utf8').split(CR).join('')
const keys = ['上一页 / 下一页','全书搜索','下一个搜索结果','上一个搜索结果','关闭搜索 / 返回','添加书签','缩放 (PDF)','下一页 (PDF/漫画)']
const missing = keys.filter((k) => !all.includes("'" + k + "'"))
console.log('快捷键说明键 = ' + keys.length + '，缺英文 = ' + missing.length + (missing.length ? ': ' + missing.join(' | ') : ' ✓'))
