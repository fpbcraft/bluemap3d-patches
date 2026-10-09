# Seasonal browser regressions

Run `npm ci`, `npm test`, `npx playwright install chromium`, then `npm run test:browser`
in this directory. `CHROME_PATH` can select an already installed Chrome executable.
The HTTP fixture server binds a random localhost port and is closed after the test.

The four shader fixtures are MIT-licensed BlueMap 5.7 sources from
[commit 8c746860e4a667029b923854fce3aedc853e585f](https://github.com/BlueMap-Minecraft/BlueMap/tree/8c746860e4a667029b923854fce3aedc853e585f/common/webapp/src/js/map).
Only trailing whitespace is normalised; original licence headers are retained. Three.js is pinned to the baseline used
by BlueMap 5.7, rather than using an unrelated current renderer.

The synthetic atlas represents saved surface metadata, with no Minecraft server or
loaded chunks. The production script fetches it through the real browser PNG decoder.
Pixel assertions exercise palette changes, snow/thaw, side/buried-face exclusions,
newly viewed tiles, and map disable in hires and lowres. Seasonal changes must reuse
the atlas rather than re-fetching it. This does not validate Ecliptic reflection against
a live modpack or measure whole-world indexing time.
