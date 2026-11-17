import { expect, Page, test } from '@playwright/test';

const CSV = 'company_name,career_page_url,website,location_preference\n'
  + 'Fixture Corp,http://127.0.0.1:4599/careers,,Noida\n'
  + 'JS Corp,http://127.0.0.1:4599/js-careers,,Gurugram\n'
  + 'FIXTURE CORP,,,\n'
  + ',,https://missing-name.example.com,\n';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByLabel('Email').fill('e2e@jobradar.local');
  await page.getByLabel('Password').fill('e2e-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
}

test('rejects a wrong password', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill('e2e@jobradar.local');
  await page.getByLabel('Password').fill('wrong-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toContainText('Invalid email or password');
});

test('upload, scan, filter fresher jobs, mark applied', async ({ page }) => {
  await login(page);

  // Upload with validation preview
  await page.getByRole('link', { name: 'Upload', exact: true }).click();
  await page.getByLabel('New list name').fill('E2E Companies');
  await page.getByRole('button', { name: 'Create list' }).click();
  await expect(page.getByLabel('Company list')).toHaveValue(/\d+/);
  await page.getByLabel('Company file').setInputFiles({ name: 'companies.csv', mimeType: 'text/csv', buffer: Buffer.from(CSV) });
  await page.getByRole('button', { name: 'Validate and preview' }).click();
  await expect(page.getByText('2 valid · 1 with errors · 1 duplicates')).toBeVisible();
  await page.getByRole('button', { name: 'Save 2 valid companies' }).click();
  await expect(page.getByTestId('upload-saved')).toContainText('Saved 2 companies');

  // Background scan with progress
  await page.getByRole('link', { name: 'Companies', exact: true }).click();
  await page.getByRole('button', { name: 'Scan all' }).click();
  await expect(page.getByTestId('scan-progress')).toContainText('2 of 2 done', { timeout: 60_000 });
  await expect(page.getByRole('row', { name: /Fixture Corp/ })).toContainText('GENERIC_HTML');
  await expect(page.getByRole('row', { name: /Fixture Corp/ })).toContainText('SUCCESS');

  // Fresher tab shows the intern and not the senior role
  await page.getByRole('link', { name: 'Jobs', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Fresher' })).toHaveAttribute('aria-selected', 'true');
  const rows = page.getByTestId('job-row');
  await expect(rows.filter({ hasText: 'Software Engineering Intern' }).first()).toBeVisible();
  await expect(rows.filter({ hasText: 'Senior Platform Engineer' })).toHaveCount(0);
  await expect(rows.first()).toContainText('NEW');
  // This one only exists after the Playwright fallback runs the page's JavaScript.
  await expect(rows.filter({ hasText: 'Rendered Graduate Trainee' }).first()).toBeVisible();

  // City filter
  await page.getByLabel('Location').fill('Pune');
  await expect(rows.filter({ hasText: 'Software Engineering Intern' })).toHaveCount(0);
  await page.getByLabel('Location').fill('');

  // Details drawer explains the classification
  const intern = rows.filter({ hasText: 'Software Engineering Intern' }).first();
  await intern.getByRole('button', { name: 'Details' }).click();
  await expect(page.getByTestId('classification-reason')).toContainText('Intern');
  await page.getByRole('button', { name: 'Close' }).click();

  // Apply opens a new tab and asks for confirmation
  const popup = page.waitForEvent('popup');
  await intern.getByRole('button', { name: 'Apply' }).click();
  await (await popup).close();
  await page.getByRole('button', { name: 'Yes, mark applied' }).click();
  await expect(rows.filter({ hasText: 'Software Engineering Intern' }).first()).toContainText('APPLIED');

  // Experienced tab
  await page.getByRole('tab', { name: 'Experienced' }).click();
  await expect(rows.filter({ hasText: 'Senior Platform Engineer' }).first()).toBeVisible();

  // Tracker
  await page.getByRole('link', { name: 'Tracker', exact: true }).click();
  await expect(page.getByTestId('column-APPLIED').getByTestId('tracker-card')).toContainText('Software Engineering Intern');
  await page.getByLabel('Move Software Engineering Intern').selectOption('INTERVIEW');
  await expect(page.getByTestId('column-INTERVIEW').getByTestId('tracker-card')).toContainText('Software Engineering Intern');
  await expect(page.getByText('100%')).toBeVisible();
});

test('layout fits a phone screen', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 700 });
  await login(page);
  for (const path of ['/', '/jobs', '/companies', '/upload', '/tracker', '/settings']) {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `horizontal overflow on ${path}`).toBeLessThanOrEqual(0);
  }
});
