#!/usr/bin/env node
/**
 * Ouvre un Chromium visible pour le guide Firebase.
 * Capture d'écran sur demande : touch /tmp/voxcrew-browser-screenshot
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const SCREENSHOT_PATH = '/tmp/voxcrew-firebase-guide.png';
const TRIGGER_PATH = '/tmp/voxcrew-browser-screenshot';
const START_URL = process.env.START_URL || 'https://console.firebase.google.com/';

if (fs.existsSync(TRIGGER_PATH)) fs.unlinkSync(TRIGGER_PATH);

const userDataDir = path.join(process.env.HOME || '', '.voxcrew-playwright-profile');

const context = await chromium.launchPersistentContext(userDataDir, {
  headless: false,
  viewport: { width: 1280, height: 900 },
  args: ['--start-maximized'],
});

const page = context.pages()[0] || (await context.newPage());
await page.goto(START_URL, { waitUntil: 'domcontentloaded' });
await page.screenshot({ path: SCREENSHOT_PATH, fullPage: false });
console.log(`Browser ready. Screenshot: ${SCREENSHOT_PATH}`);
console.log('Touch trigger to refresh screenshot: ' + TRIGGER_PATH);

setInterval(async () => {
  if (!fs.existsSync(TRIGGER_PATH)) return;
  try {
    await page.screenshot({ path: SCREENSHOT_PATH, fullPage: false });
    console.log('Screenshot updated:', new Date().toISOString());
  } catch (e) {
    console.error('Screenshot failed:', e.message);
  }
  fs.unlinkSync(TRIGGER_PATH);
}, 500);

process.on('SIGINT', async () => {
  await context.close();
  process.exit(0);
});
