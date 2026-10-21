// Store icon (512x512 PNG, no alpha) and feature graphic (1024x500 PNG, no alpha), drawn in Chromium
// from the launcher icon's own paths (app/src/main/res/drawable/ic_launcher_foreground.xml) and the design
// tokens in res/values/colors.xml, with the app's Fredoka font. Run via scripts/store/play-assets.sh graphics.
// Usage: NODE_PATH=/path/to/node_modules node graphics.cjs <outDir> <fredoka.ttf>
const path = require("path");
const { chromium } = require("playwright");
const [out, font] = process.argv.slice(2);
const SKY = "#5BC0EB", INK = "#3F2E22", CREAM = "#FFF3E3", ORANGE = "#FF8A3D", SOFT = "#8A7664";
const ICON = `<g transform="rotate(-8 54 54)">
<path fill="#000" fill-opacity=".18" d="M36,32.5 L74,32.5 A5,5 0 0 1 79,37.5 L79,81 A5,5 0 0 1 74,86 L36,86 A5,5 0 0 1 31,81 L31,37.5 A5,5 0 0 1 36,32.5 Z"/>
<path fill="#fff" d="M34,30 L74,30 A5,5 0 0 1 79,35 L79,79 A5,5 0 0 1 74,84 L34,84 A5,5 0 0 1 29,79 L29,35 A5,5 0 0 1 34,30 Z"/>
<path fill="none" stroke="#FF5A5F" stroke-width="5" stroke-linecap="round" d="M37,66 A17,17 0 0 1 71,66"/>
<path fill="none" stroke="#FFC93C" stroke-width="5" stroke-linecap="round" d="M43,66 A11,11 0 0 1 65,66"/>
<path fill="none" stroke="#4CD964" stroke-width="5" stroke-linecap="round" d="M49,66 A5,5 0 0 1 59,66"/></g>
<g transform="rotate(-45 70 70)">
<path fill="#8B5A2B" d="M66.5,68.5 L73.5,68.5 L73.5,80 A3.5,3.5 0 0 1 66.5,80 Z"/>
<path fill="#D6D6D6" d="M65.5,64.5 L74.5,64.5 L74.5,68.5 L65.5,68.5 Z"/>
<path fill="${ORANGE}" d="M70,57 L74.5,64.5 L65.5,64.5 Z"/></g>`;
const face = `@font-face{font-family:F;src:url(data:font/ttf;base64,${require("fs").readFileSync(path.resolve(font)).toString("base64")});font-weight:100 900}`;
const svg = (s) => `<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}" viewBox="0 0 108 108"><rect width="108" height="108" fill="${SKY}"/>${ICON}</svg>`;
(async () => {
  const b = await chromium.launch();
  const shot = async (w, h, html, file) => {
    const p = await b.newPage({ viewport: { width: w, height: h } });
    await p.setContent(`<style>${face}html,body{margin:0;background:#fff}</style>${html}`);
    await p.evaluate(() => document.fonts.ready);
    await p.screenshot({ path: path.join(out, file) }); // opaque page => PNG without alpha content
    await p.close();
  };
  await shot(512, 512, svg(512), "icon.png");
  await shot(1024, 500, `<div style="width:1024px;height:500px;background:${CREAM};display:flex;align-items:center;gap:56px;padding-left:72px;box-sizing:border-box;font-family:F">
    <div style="border-radius:76px;overflow:hidden;width:340px;height:340px;flex:none">${svg(340)}</div>
    <div><div style="font-size:80px;white-space:nowrap;font-weight:600;color:${INK};line-height:1">Little Painter</div>
    <div style="font-size:42px;font-weight:400;color:${SOFT};margin-top:22px;line-height:1.3">Draw, fill and play.<br>No ads. No internet.</div></div></div>`, "featureGraphic.png");
  await b.close();
  console.log("wrote icon.png and featureGraphic.png to " + out);
})();
