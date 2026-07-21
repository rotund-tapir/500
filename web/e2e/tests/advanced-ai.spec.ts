// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { expect, test } from '@playwright/test';
import { awaitAppBoot, clickByRole, collectErrors } from './helpers';

// Compose switches mirror into the accessibility tree as *unnamed* buttons (their label is a
// sibling text node), so the Advanced AI switch is found as the unnamed button vertically aligned
// with its label, and its state is asserted through localStorage (the persistence under test).
async function clickSwitchLabelled(page: import('@playwright/test').Page, label: string) {
  const labelBox = await page.getByText(label, { exact: true }).first().boundingBox();
  if (!labelBox) throw new Error(`no bounding box for label "${label}"`);
  const labelY = labelBox.y + labelBox.height / 2;
  // The switch is the only button in the label's row (the row is Text + Switch).
  for (const button of await page.getByRole('button').all()) {
    const box = await button.boundingBox();
    if (!box) continue;
    const y = box.y + box.height / 2;
    if (Math.abs(y - labelY) < labelBox.height) {
      await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
      return;
    }
  }
  throw new Error(`no switch aligned with label "${label}"`);
}

// Exercises LocalStorageSettingsRepository's bot_skill entry for real: toggling the switch writes
// the shared key, and after a reload the app must have read it back — proven by toggling again,
// which only flips back to STANDARD if the reloaded switch knew it was ADVANCED.
test('the Advanced AI toggle persists across a page reload', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/500/');
  await awaitAppBoot(page);

  await clickByRole(page, 'button', 'Settings');
  await expect(page.getByText('Advanced AI', { exact: true })).toBeVisible({ timeout: 15_000 });
  await clickSwitchLabelled(page, 'Advanced AI');
  await expect
    .poll(() => page.evaluate(() => window.localStorage.getItem('settings.bot_skill')))
    .toBe('ADVANCED');

  await page.reload();
  await awaitAppBoot(page);
  await clickByRole(page, 'button', 'Settings');
  await expect(page.getByText('Advanced AI', { exact: true })).toBeVisible({ timeout: 15_000 });
  await clickSwitchLabelled(page, 'Advanced AI');
  await expect
    .poll(() => page.evaluate(() => window.localStorage.getItem('settings.bot_skill')))
    .toBe('STANDARD');

  expect(errors, 'advanced-AI settings flow must be console-error clean').toEqual([]);
});

// The advanced (Monte-Carlo) bots on the shared fixture, with ?aiBudgetMs= shrinking their search
// budgets to test speed. On wasm the whole search runs on the single browser thread, so the game
// progressing past bot turns without console errors is exactly the "cooperative yielding works,
// nothing freezes or crashes" smoke this guards. The bots' moves aren't pinned (wall-clock budgets
// are nondeterministic), so assertions are flow-level, not card-level.
test('advanced AI: a game progresses through bot thinking without freezing', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/500/?seed=42&animationSpeed=OFF&soundVolume=0&botSkill=ADVANCED&aiBudgetMs=25');
  await awaitAppBoot(page);

  await clickByRole(page, 'button', 'Play with bots');
  await clickByRole(page, 'button', /^Play$/);

  // Reaching our bid prompt means every advanced bot before seat 0 searched and bid.
  await expect(page.getByRole('button', { name: 'Pass' })).toBeVisible({ timeout: 30_000 });
  await clickByRole(page, 'button', 'Pass');

  // The auction resolves: someone's contract is announced (play begins), or — if everyone
  // passed — the redeal brings the bid panel straight back. Either way the advanced bots kept
  // the game moving.
  await expect(
    page.getByText(/Contract:/).first().or(page.getByRole('button', { name: 'Pass' }).first()),
  ).toBeVisible({ timeout: 30_000 });

  expect(errors, 'advanced-AI flow must be console-error clean').toEqual([]);
});
