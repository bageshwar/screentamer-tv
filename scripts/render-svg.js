const fs = require('fs');
const path = require('path');
const os = require('os');
const { chromium } = require('../server-relay/node_modules/playwright-core');

function findChromium() {
  if (process.env.PLAYWRIGHT_CHROMIUM_PATH) return process.env.PLAYWRIGHT_CHROMIUM_PATH;
  const caches = [
    path.join(os.homedir(), 'Library/Caches/ms-playwright'),
    path.join(os.homedir(), '.cache/ms-playwright'),
  ];
  const binaries = [
    (root) => path.join(root, 'chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'),
    (root) => path.join(root, 'chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'),
    (root) => path.join(root, 'headless_shell-linux-x64/headless_shell'),
    (root) => path.join(root, 'chrome-linux/chrome'),
  ];
  for (const cache of caches) {
    if (!fs.existsSync(cache)) continue;
    for (const dir of fs.readdirSync(cache).sort().reverse()) {
      const root = path.join(cache, dir);
      for (const make of binaries) {
        const exe = make(root);
        if (fs.existsSync(exe)) return exe;
      }
    }
  }
  return null;
}

const args = process.argv.slice(2);
if (args.length < 4) {
  console.error("Usage: node render-svg.js <svg-path> <out-path> <width> <height>");
  process.exit(1);
}

const svgPath = path.resolve(args[0]);
const outPath = path.resolve(args[1]);
const width = parseInt(args[2], 10);
const height = parseInt(args[3], 10);

(async () => {
  const exe = findChromium();
  if (!exe) {
    console.error("Chromium not found. Please install it with: npx playwright-core install chromium");
    process.exit(1);
  }
  
  const browser = await chromium.launch({ headless: true, executablePath: exe });
  const page = await browser.newPage({ viewport: { width, height } });
  
  // Load SVG content in a transparent page
  await page.setContent(`
    <!DOCTYPE html>
    <html>
      <head>
        <style>
          html, body {
            margin: 0;
            padding: 0;
            background: transparent;
            overflow: hidden;
            width: 100%;
            height: 100%;
          }
          #svg-container {
            width: ${width}px;
            height: ${height}px;
            display: flex;
            align-items: center;
            justify-content: center;
          }
          svg {
            width: 100%;
            height: 100%;
          }
        </style>
      </head>
      <body>
        <div id="svg-container">
          ${fs.readFileSync(svgPath, 'utf8')}
        </div>
      </body>
    </html>
  `);
  
  const element = await page.$('#svg-container');
  await element.screenshot({ path: outPath, omitBackground: true });
  
  await browser.close();
})();
