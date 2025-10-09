#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const budgetKb = parseInt(process.env.BUNDLE_BUDGET_KB || '500', 10);
const dist = path.resolve(__dirname, '..', 'dist');
if (!fs.existsSync(dist)) {
  console.error('[FAIL] dist not found; run `pnpm build` first');
  process.exit(2);
}
function sizeKb(p) { return Math.round((fs.statSync(p).size / 1024)); }

let total = 0;
const files = fs.readdirSync(dist).filter(f => /\.(js|css)$/.test(f));
files.forEach(f => { total += sizeKb(path.join(dist, f)); });

console.log(`[INFO] bundle total: ${total} KB (budget ${budgetKb} KB)`);
if (total > budgetKb) {
  console.error(`[FAIL] bundle size ${total} KB exceeds budget ${budgetKb} KB`);
  process.exit(1);
}
console.log('[OK] bundle within budget');
