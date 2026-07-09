// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { expect, test } from '@playwright/test';
import { awaitAppBoot, clickByRole, collectErrors } from './helpers';

// Exercises LocalStorageSettingsRepository for real: a changed setting must survive a full page
// reload. Uses the plain entry point (no animationSpeed override) so the dialog reflects the
// persisted value directly. A fresh Playwright context starts with empty localStorage.
test('a settings change persists across a page reload', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/500/');
  await awaitAppBoot(page);

  // Open settings; the Animations button shows the current speed and cycles on tap.
  await clickByRole(page, 'button', 'Settings');
  await expect(page.getByRole('button', { name: 'Normal' })).toBeVisible({ timeout: 15_000 });
  await clickByRole(page, 'button', 'Normal'); // Normal → Fast

  // It reached localStorage under the shared key, and the dialog reflects it.
  await expect(page.getByRole('button', { name: 'Fast' })).toBeVisible();
  const stored = await page.evaluate(() => window.localStorage.getItem('settings.animation_speed'));
  expect(stored).toBe('FAST');

  // After a full reload the persisted value is read back.
  await page.reload();
  await awaitAppBoot(page);
  await clickByRole(page, 'button', 'Settings');
  await expect(page.getByRole('button', { name: 'Fast' })).toBeVisible({ timeout: 15_000 });

  expect(errors, 'settings flow must be console-error clean').toEqual([]);
});
