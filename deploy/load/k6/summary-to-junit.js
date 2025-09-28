#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

function usage() {
  console.error('Usage: node summary-to-junit.js <summary.json> <junit.xml>');
}

function readSummary(filePath) {
  const absolutePath = path.resolve(process.cwd(), filePath);
  try {
    const content = fs.readFileSync(absolutePath, 'utf-8');
    return JSON.parse(content);
  } catch (error) {
    console.error(`Failed to read summary file ${filePath}: ${error.message}`);
    process.exit(1);
  }
}

function metricValue(metric, keys) {
  if (!metric || typeof metric !== 'object') {
    return null;
  }
  const values = metric.values || {};
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(values, key)) {
      return values[key];
    }
  }
  return null;
}

function collectThresholdMessages(metric) {
  if (!metric || typeof metric.thresholds !== 'object') {
    return { failures: [], summaries: [] };
  }
  const failures = [];
  const summaries = [];
  for (const [name, result] of Object.entries(metric.thresholds)) {
    const ok = Boolean(result && result.ok);
    const actual = result && result.actual ? result.actual : 'n/a';
    const threshold = result && result.threshold ? result.threshold : name;
    summaries.push(`${threshold}: ${actual}`);
    if (!ok) {
      failures.push(`${threshold} violated (actual: ${actual})`);
    }
  }
  return { failures, summaries };
}

function escapeXml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function ensureOutputDir(targetPath) {
  const dir = path.dirname(path.resolve(process.cwd(), targetPath));
  fs.mkdirSync(dir, { recursive: true });
}

function buildTestCase({ name, failureMessages, info }) {
  const failureText = failureMessages.length > 0 ? failureMessages.join('; ') : null;
  const infoText = info.filter(Boolean).join(' | ');
  const systemOut = infoText ? `<system-out>${escapeXml(infoText)}</system-out>` : '';
  const failureXml = failureText
    ? `<failure message="${escapeXml(failureText)}"/>`
    : '';
  const lines = [`  <testcase classname="k6" name="${escapeXml(name)}" time="0">`];
  if (failureXml) {
    lines.push(`    ${failureXml}`);
  }
  if (systemOut) {
    lines.push(`    ${systemOut}`);
  }
  lines.push('  </testcase>');
  return lines.join('\n');
}

function main() {
  const [summaryPath, junitPath] = process.argv.slice(2);
  if (!summaryPath || !junitPath) {
    usage();
    process.exit(1);
  }

  const summary = readSummary(summaryPath);
  const metrics = summary.metrics || {};

  const httpDurationMetric = metrics.http_req_duration;
  const durationValue = metricValue(httpDurationMetric, ['p(95)', 'p95']);
  const durationThresholds = collectThresholdMessages(httpDurationMetric);
  const durationTest = buildTestCase({
    name: 'http_req_duration p95',
    failureMessages: durationThresholds.failures,
    info: [
      durationValue !== null ? `p95=${durationValue} ms` : 'p95 unavailable',
      ...durationThresholds.summaries,
    ],
  });

  const failedMetric = metrics.http_req_failed;
  const failedRate = metricValue(failedMetric, ['rate']);
  const failedThresholds = collectThresholdMessages(failedMetric);
  if (failedMetric && (!failedThresholds.failures.length && typeof failedRate === 'number' && failedRate > 0)) {
    failedThresholds.failures.push(`http_req_failed rate ${failedRate}`);
  }
  const failedTest = buildTestCase({
    name: 'http_req_failed rate',
    failureMessages: failedThresholds.failures,
    info: [
      failedRate !== null ? `rate=${failedRate}` : 'rate unavailable',
      ...failedThresholds.summaries,
    ],
  });

  const checksMetric = metrics.checks || {};
  const checksThresholds = collectThresholdMessages(checksMetric);
  const passes = metricValue(checksMetric, ['passes']);
  const fails = metricValue(checksMetric, ['fails']);
  const checkFailures = [...checksThresholds.failures];
  if (typeof fails === 'number' && fails > 0) {
    checkFailures.push(`checks failed=${fails}`);
  }
  const checksTest = buildTestCase({
    name: 'checks aggregate',
    failureMessages: checkFailures,
    info: [
      typeof passes === 'number' ? `passes=${passes}` : null,
      typeof fails === 'number' ? `fails=${fails}` : null,
      ...checksThresholds.summaries,
    ],
  });

  const testcases = [durationTest, failedTest, checksTest];
  const failureCount = testcases.filter((tc) => tc.includes('<failure')).length;
  const xml = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<testsuite name="k6" tests="${testcases.length}" failures="${failureCount}" time="0">`,
    ...testcases,
    '</testsuite>',
  ].join('\n');

  ensureOutputDir(junitPath);
  try {
    fs.writeFileSync(junitPath, `${xml}\n`, 'utf-8');
  } catch (error) {
    console.error(`Failed to write junit file ${junitPath}: ${error.message}`);
    process.exit(1);
  }
}

main();
