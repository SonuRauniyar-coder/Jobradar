import { expect, Page, test } from '@playwright/test';

// Browser checks for the defects in the QA report of 10 Nov 2026. Each test sets up its own data
// against the local fixture site, so it does not depend on jobradar.spec.ts.

const EMAIL = 'e2e@jobradar.local';
const PASSWORD = 'e2e-password';

/** reload=false signs in on the login page the app is already showing, keeping the in-memory cache. */
async function login(page: Page, reload = true) {
  if (reload) await page.goto('/login');
  await page.getByLabel('Email').fill(EMAIL);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
}

async function apiCall(page: Page, method: string, path: string, body?: unknown, multipart?: Record<string, unknown>) {
  const token = await page.evaluate(() => localStorage.getItem('jobradar.token'));
  const res = await page.request.fetch(`/api${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}` },
    data: body,
    multipart: multipart as never,
  });
  expect(res.status(), `${method} ${path}`).toBeLessThan(400);
  return res.status() === 204 ? null : res.json();
}

/** A list with one company on the fixture site, scanned, so there are jobs to work with. */
async function scannedList(page: Page) {
  const tag = Math.random().toString(36).slice(2, 8);
  const list = await apiCall(page, 'POST', '/lists', { name: `QA ${tag}` });
  const csv = `company_name,career_page_url\nQA Fixture ${tag},http://127.0.0.1:4599/careers\n`;
  await apiCall(page, 'POST', `/lists/${list.id}/upload?commit=true`, undefined,
    { file: { name: 'c.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) } });
  const run = await apiCall(page, 'POST', '/scans', { listId: list.id });
  await expect.poll(async () => (await apiCall(page, 'GET', `/scans/${run.id}`)).state, { timeout: 60_000 }).not.toBe('RUNNING');
  const companies = await apiCall(page, 'GET', `/companies?listId=${list.id}`);
  expect(companies[0].lastStatus, 'fixture scan').toBe('SUCCESS');
  return { listId: list.id as number, company: `QA Fixture ${tag}` };
}

test('B01 applying a saved search keeps the job list working', async ({ page }) => {
  await login(page);
  const { company } = await scannedList(page);
  const badRequests: string[] = [];
  page.on('request', (r) => { if (r.url().includes('=null')) badRequests.push(r.url()); });

  await page.goto('/jobs');
  await page.getByLabel('Company').fill(company);
  page.once('dialog', (d) => d.accept(`Search ${company}`));
  await page.getByRole('button', { name: 'Save search' }).click();
  await expect(page.getByRole('status')).toContainText('Search saved.');

  await page.getByLabel('Company').fill('');
  const saved = page.getByLabel('Saved searches');
  const option = saved.locator('option', { hasText: `Search ${company}` });
  await saved.selectOption(await option.getAttribute('value') as string);
  await expect(page.getByTestId('job-row').filter({ hasText: company }).filter({ hasText: 'Software Engineering Intern' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(badRequests).toEqual([]);
});

test('B04 a new sign-in never shows data cached from the previous session', async ({ page }) => {
  await login(page);
  const totalCard = page.locator('.card', { hasText: 'Total companies' });
  await expect(totalCard).toContainText(/\d/); // this session's numbers are loaded and cached
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.route('**/api/stats/dashboard', async (route) => {
    await new Promise((r) => setTimeout(r, 3000));
    await route.continue();
  });
  await expect(page).toHaveURL(/\/login$/);
  await login(page, false);
  await expect(totalCard).toContainText('–'); // still loading, not the old number
  await expect(totalCard).not.toContainText(/\d/);
});

test('B20 B26 details drawer marks the job seen and behaves as a modal', async ({ page }) => {
  await login(page);
  const { company } = await scannedList(page);
  await page.goto('/jobs');
  await page.getByRole('tab', { name: 'All' }).click();
  await page.getByLabel('Company').fill(company);
  const row = page.getByTestId('job-row').filter({ hasText: company }).filter({ hasText: 'QA Engineer' });
  await row.getByRole('button', { name: 'Details' }).click();

  const dialog = page.getByRole('dialog', { name: 'QA Engineer' });
  await expect(dialog).toHaveAttribute('aria-modal', 'true');
  await expect(dialog.getByRole('button', { name: 'Close' })).toBeFocused();
  for (let i = 0; i < 8; i++) {
    await page.keyboard.press('Tab');
    expect(await dialog.evaluate((d) => d.contains(document.activeElement))).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(row).toContainText('SEEN');
});

test('B09 double-clicking Save does not fail', async ({ page }) => {
  await login(page);
  const { company } = await scannedList(page);
  const failures: string[] = [];
  page.on('response', (r) => { if (r.url().includes('/api/') && r.status() >= 400) failures.push(`${r.status()} ${r.url()}`); });
  await page.goto('/jobs');
  await page.getByRole('tab', { name: 'All' }).click();
  await page.getByLabel('Company').fill(company);
  const row = page.getByTestId('job-row').filter({ hasText: company }).filter({ hasText: 'Senior Platform Engineer' });
  await row.getByRole('button', { name: 'Save', exact: true }).dblclick();
  await expect(row).toContainText('SAVED');
  expect(failures).toEqual([]);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('B19 B25 B29 jobs page heading, all statuses, and debounced typing', async ({ page }) => {
  await login(page);
  await page.goto('/jobs');
  await expect(page.getByRole('heading', { level: 1, name: 'Jobs' })).toBeVisible();
  await expect(page.getByLabel('Status').locator('option')).toContainText(['Interview', 'Offer']);

  let searches = 0;
  page.on('request', (r) => { if (/\/api\/jobs\?/.test(r.url())) searches++; });
  await page.getByLabel('Company').pressSequentially('abcdefgh', { delay: 30 });
  await page.waitForTimeout(800);
  expect(searches).toBeLessThanOrEqual(2);
});

test('B13 dropping something that is not a card on the tracker is ignored', async ({ page }) => {
  await login(page);
  await page.goto('/tracker');
  const statusCalls: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/status')) statusCalls.push(r.url()); });
  await page.getByTestId('column-OFFER').evaluate((column) => {
    const data = new DataTransfer();
    data.setData('text/plain', 'hello');
    column.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: data }));
  });
  await page.waitForTimeout(500);
  expect(statusCalls).toEqual([]);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('B07 editing a company keeps its detected source', async ({ page }) => {
  await login(page);
  const { listId, company } = await scannedList(page);
  await page.goto('/companies');
  await page.getByLabel('Filter by list').selectOption(String(listId));
  const row = page.getByRole('row', { name: new RegExp(company) });
  await expect(row).toContainText('GENERIC_HTML');
  await row.getByRole('button', { name: 'Edit' }).click();
  await page.getByLabel('Location preference').fill('Pune');
  const saved = page.waitForResponse((r) => r.url().includes('/api/companies/') && r.request().method() === 'PUT');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await (await saved).json()).sourceType).toBe('GENERIC_HTML');
  await page.reload();
  await page.getByLabel('Filter by list').selectOption(String(listId));
  await expect(row).toContainText('GENERIC_HTML');
});

test('B34 a list can be deleted from the Companies page', async ({ page }) => {
  await login(page);
  const list = await apiCall(page, 'POST', '/lists', { name: `Delete me ${Date.now()}` });
  await page.goto('/companies');
  await page.getByLabel('Filter by list').selectOption(String(list.id));
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'Delete list' }).click();
  await expect(page.getByLabel('Filter by list').locator('option', { hasText: list.name })).toHaveCount(0);
});

test('B31 one saved company is described in the singular', async ({ page }) => {
  await login(page);
  await page.goto('/upload');
  await page.getByLabel('New list name').fill(`Single ${Date.now()}`);
  await page.getByRole('button', { name: 'Create list' }).click();
  await expect(page.getByLabel('Company list')).toHaveValue(/\d+/);
  await page.getByLabel('Company file').setInputFiles({
    name: 'one.csv', mimeType: 'text/csv', buffer: Buffer.from('company_name\nOnly One Co\n'),
  });
  await page.getByRole('button', { name: 'Validate and preview' }).click();
  await page.getByRole('button', { name: 'Save 1 valid company' }).click();
  await expect(page.getByTestId('upload-saved')).toContainText('Saved 1 company.');
});

test('B30 B32 settings accept 0.25 requests per second and clear "Saved." after edits', async ({ page }) => {
  await login(page);
  await page.goto('/settings');
  await page.getByLabel('Requests per second, per site (0.1–5)').fill('0.25');
  await page.getByRole('button', { name: 'Save settings' }).click();
  await expect(page.getByText('Saved.')).toBeVisible();
  await page.getByLabel('Preferred locations (comma separated)').fill('Pune');
  await expect(page.getByText('Saved.')).toHaveCount(0);
});

test('B28 B33 phone-width navigation shows every link, and /login redirects when signed in', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 700 });
  await login(page);
  await page.goto('/tracker');
  for (const name of ['Dashboard', 'Jobs', 'Companies', 'Upload', 'Tracker', 'Settings']) {
    const box = await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name, exact: true }).boundingBox();
    expect(box, name).not.toBeNull();
    expect(box!.x + box!.width, `${name} fits on screen`).toBeLessThanOrEqual(375);
  }
  await page.goto('/login');
  await expect(page).toHaveURL(/\/$/);
});
