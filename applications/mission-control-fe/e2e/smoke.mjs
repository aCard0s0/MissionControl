// Route smoke: every page, on a real backend, as an operator sees it.
//
// The unit suites stub every store and exclude templates from coverage, so a page that
// renders `NaN%`, opens the browser's confirm(), or drifts from the header convention passes
// them all. This walks each route at a desktop and a phone width and fails on anything the
// page itself would show a person: a console error, a failed request, horizontal overflow, a
// button with no name, a header outside the vocabulary in docs/mission_control_guidelines.md.
//
//   BASE=http://localhost:8080 npm run e2e:smoke
//
// Read-only. It opens nothing that writes, so it is safe against a live deployment too.
import { chromium } from 'playwright';

const BASE = (process.env.BASE ?? 'http://localhost:4300').replace(/\/$/, '');

// app.routes.ts, minus the redirects
const ROUTES = [
  '/containers', '/overview', '/agents', '/profiles', '/models', '/credentials', '/mcp-servers',
  '/prompts', '/skills', '/board', '/calendar', '/webhooks', '/reference', '/server-logs',
];

// "Component conventions": the fixed scope crumbs, plus the selected container's own name
// on container-scoped pages, or the dash the picker shows when there is none.
const CRUMBS = new Set(['FLEET', 'GLOBAL LIBRARY', 'DASHBOARD', '—']);
const containers = await fetch(`${BASE}/api/containers`)
  .then(r => (r.ok ? r.json() : []))
  .catch(() => []);
for (const c of containers) CRUMBS.add(String(c.name).toUpperCase());

// navigating away aborts in-flight polls; that is the browser, not the page
const HARMLESS_FAILURE = /ERR_ABORTED/;

const browser = await chromium.launch();
const failures = [];

for (const width of [1440, 390]) {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  const noise = [];
  page.on('console', m => { if (m.type() === 'error') noise.push(`console.error: ${m.text()}`); });
  page.on('pageerror', e => noise.push(`pageerror: ${e.message}`));
  page.on('response', r => {
    if (r.status() >= 400) noise.push(`${r.status()} ${r.request().method()} ${r.url().replace(BASE, '')}`);
  });
  page.on('requestfailed', r => {
    const why = r.failure()?.errorText ?? '';
    if (!HARMLESS_FAILURE.test(why)) noise.push(`request failed: ${r.url().replace(BASE, '')} ${why}`);
  });

  for (const route of ROUTES) {
    noise.length = 0;
    await page.goto(BASE + route, { waitUntil: 'load' });
    await page.waitForTimeout(1500);   // the reveal animation and the first poll

    const seen = await page.evaluate(() => {
      const text = sel => document.querySelector(sel)?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
      return {
        title: document.title,
        h2: text('.page-head h2'),
        crumb: text('.page-head .crumb')?.toUpperCase() ?? null,
        // a page with nothing to scope to shows an empty panel instead of a header
        emptyState: !!document.querySelector('.panel.empty'),
        overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
        unnamedButtons: [...document.querySelectorAll('button')]
          .filter(b => !b.textContent?.trim() && !b.getAttribute('aria-label') && !b.title).length,
      };
    });

    const problems = [...new Set(noise)];
    if (!seen.title.endsWith('· Mission Control')) problems.push(`title "${seen.title}"`);
    if (!seen.h2 && !seen.emptyState) problems.push('no .page-head and no empty state');
    if (seen.h2 && seen.crumb === null) problems.push('header without a .crumb');
    if (seen.crumb !== null && !CRUMBS.has(seen.crumb)) {
      problems.push(`crumb "${seen.crumb}" is outside the vocabulary (${[...CRUMBS].join(', ')})`);
    }
    if (seen.overflow) problems.push('page scrolls horizontally');
    if (seen.unnamedButtons) problems.push(`${seen.unnamedButtons} button(s) with no accessible name`);

    if (problems.length) failures.push({ route, width, problems });
    console.log(`${problems.length ? '✗' : '✓'} ${String(width).padStart(4)}px ${route}`);
  }
  await page.close();
}
await browser.close();

if (failures.length) {
  for (const f of failures) console.error(`\n${f.route} @ ${f.width}px\n  - ${f.problems.join('\n  - ')}`);
  process.exit(1);
}
console.log(`\n${ROUTES.length} routes × 2 widths clean on ${BASE}`);
