#!/usr/bin/env node
const fs = require('fs');

const inFile = process.argv[2] || 'synthetics_report.json';
const outFile = process.argv[3] || 'synthetics_junit.xml';

let raw;
try {
  raw = fs.readFileSync(inFile, 'utf8');
} catch (err) {
  console.error(`Failed to read ${inFile}: ${err.message}`);
  process.exit(1);
}

let data;
try {
  data = JSON.parse(raw);
} catch (err) {
  console.error(`Failed to parse ${inFile} as JSON: ${err.message}`);
  process.exit(1);
}

if (!Array.isArray(data.results)) {
  console.error('Invalid report: results array missing');
  process.exit(1);
}

const cases = data.results.map((r) => {
  const name = `synthetic_${r.name}`;
  const ok = Boolean(r.ok);
  const start = r.start ? new Date(r.start) : null;
  const end = r.end ? new Date(r.end) : null;
  const time = start && end && !Number.isNaN(start.valueOf()) && !Number.isNaN(end.valueOf())
    ? ((end - start) / 1000).toFixed(3)
    : '0.000';
  const safeCode = typeof r.code === 'number' ? r.code : 0;
  if (ok) {
    return `<testcase classname="synthetics" name="${name}" time="${time}"/>`;
  }
  const failureMessage = `HTTP ${safeCode}`;
  return `<testcase classname="synthetics" name="${name}" time="${time}"><failure message="${failureMessage}"/></testcase>`;
}).join('\n');

const suiteFailures = typeof data.fail === 'number' ? data.fail : data.results.filter((r) => !r.ok).length;
const suite = `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="synthetics" tests="${data.results.length}" failures="${suiteFailures}" time="0">\n${cases}\n</testsuite>\n`;

try {
  fs.writeFileSync(outFile, suite, 'utf8');
} catch (err) {
  console.error(`Failed to write ${outFile}: ${err.message}`);
  process.exit(1);
}

console.log(`JUnit written to ${outFile}`);
