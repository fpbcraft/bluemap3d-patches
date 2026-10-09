const {chromium} = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const assert = require('node:assert/strict');

// PNG fixture: a saved region, one biome, grass surface at y=65, eligible for snow.
function png() {
    function crc(bytes) { let c = -1; for (const b of bytes) { c ^= b; for(let k=0;k<8;k++) c = (c>>>1)^((c&1)?0xedb88320:0); } return (c^-1)>>>0; }
    function chunk(type, data) { const name=Buffer.from(type), n=Buffer.alloc(4), checksum=Buffer.alloc(4); n.writeUInt32BE(data.length); checksum.writeUInt32BE(crc(Buffer.concat([name,data]))); return Buffer.concat([n,name,data,checksum]); }
    const header=Buffer.alloc(13); header.writeUInt32BE(512); header.writeUInt32BE(1024,4); header[8]=8; header[9]=2;
    const data=Buffer.alloc(1024*(512*3+1));
    for(let y=0;y<1024;y++) for(let x=0;x<512;x++) {
        const i=y*(512*3+1)+1+x*3;
        data[i]=y<512?0:128; data[i+1]=y<512?1:65; data[i+2]=y<512?5:0;
    }
    return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(data)),chunk('IEND',Buffer.alloc(0))]);
}
let snow=0, mix=0, maps=true, atlasRequests=0;
const palette=()=>[{id:'minecraft:plains',grass:0x804020,foliage:0x804020,mix,baseGrass:0x408020,baseFoliage:0x408020,snow}];
const source=path.resolve(__dirname,'../../addon-compat/src/main/resources/bluemap-seasons/seasonal.js');
const atlas=png();
const server=http.createServer((req,res)=>{
    const pathname=req.url.split('?')[0];
    let content, type='application/javascript';
    if(pathname==='/') {content=fs.readFileSync(path.join(__dirname,'browser.html'));type='text/html';}
    else if(pathname==='/three.js') content=fs.readFileSync(path.join(__dirname,'node_modules/three/build/three.module.js'));
    else if(pathname==='/seasonal.js') content=fs.readFileSync(source);
    else if(pathname.startsWith('/fixtures/')) content=fs.readFileSync(path.join(__dirname,pathname));
    else if(pathname.endsWith('/state.json')) {type='application/json';content=JSON.stringify({version:1,updated:Date.now()/10000,maps:maps?{world:{atlas:'v1/world'}}:{},palette:palette(),tint:true,snow:true});}
    else if(pathname==='/textures.json') {type='application/json';content=JSON.stringify([{resourcePath:'minecraft:block/grass_block_top'},{resourcePath:'minecraft:block/snow'}]);}
    else if(pathname.endsWith('.png')) {atlasRequests++;content=atlas;type='image/png';}
    else if(pathname==='/favicon.ico') {res.writeHead(204);res.end();return;}
    else {res.writeHead(404);res.end();return;}
    res.writeHead(200,{'Content-Type':type,'Cache-Control':'no-store'});res.end(content);
});
(async()=>{
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{}),args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
    try {
        const page=await browser.newPage();
        const errors=[];page.on('pageerror',e=>errors.push(String(e)));page.on('console',m=>{if(m.type()==='error'){errors.push(m.text()); console.error(m.text());}});
        await page.goto('http://127.0.0.1:'+server.address().port);
        await page.waitForFunction(()=>window.__bluemapSeasons?.tiles===2);
        const sample=()=>page.evaluate(()=>window.sample());
        const normal=await sample(); console.log("normal",normal,await page.evaluate(()=>window.__bluemapSeasons));
        mix=1;
        await page.waitForTimeout(11500);
        console.log("after palette",await sample(),await page.evaluate(()=>window.__bluemapSeasons));
        await page.waitForFunction(()=>window.sample().high[0]>100,{},{timeout:20000, polling:500});
        const autumn=await sample();
        assert.ok(autumn.high[0]>normal.high[0]+40,JSON.stringify({normal,autumn}));
        assert.ok(autumn.low[0]>normal.low[0]+40,JSON.stringify({normal,autumn}));
        snow=100;
        await page.waitForFunction(()=>window.sample().high[0]>220,{},{timeout:20000, polling:500});
        const winter=await sample();
        assert.ok(winter.low[0]>220,JSON.stringify(winter));
        assert.equal(atlasRequests,1,'season changes must not reload the saved-world atlas');
        await page.evaluate(()=>window.testSurface(0,65));
        assert.ok((await sample()).high[0]<220,'side faces must not become snow');
        await page.evaluate(()=>window.testSurface(1,64));
        assert.ok((await sample()).high[0]<220,'buried surfaces must not become snow');
        await page.evaluate(()=>{window.testSurface(1,65);window.addLateTile();});
        await page.waitForFunction(()=>window.__bluemapSeasons.tiles===3);
        assert.ok((await page.evaluate(()=>window.sampleLate()))[0]>220,'newly viewed tiles use current snow state');
        // Palette reversal restores the original pixels without reloading terrain or reindexing.
        snow=0;mix=0;
        await page.waitForFunction(()=>window.sample().high[0]<90,{},{timeout:20000, polling:500});
        const thaw=await sample();
        assert.deepEqual(thaw,normal);
        // Disabling the map restores baseline and doesn't dispose shared terrain textures.
        maps=false;
        await page.waitForTimeout(11000);
        assert.deepEqual(await sample(),normal);
        assert.deepEqual(errors,[]);
        console.log(JSON.stringify({normal,autumn,winter,thaw,errors}));
    } finally {await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
