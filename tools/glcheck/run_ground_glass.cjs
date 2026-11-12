#!/usr/bin/env node
// Shell entry point for the installed Ego browser. No browser installation/settings changes.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const check = require('./ground_glass.js');
try {
  const dir = path.resolve(process.argv[2] || '/tmp/glcheck');
  const shaders = Object.fromEntries(Object.entries({vs:'vertex',down:'ground_glass_down',blur:'blur',glass:'ground_glass'})
    .map(([key, name]) => [key, fs.readFileSync(path.join(dir, `${name}.glsl`), 'utf8')]));
  const guide = spawnSync('ego-browser', ['skill'], {stdio:'inherit'});
  if (guide.error) throw guide.error;
  if (guide.status !== 0) throw new Error(`ego-browser skill failed: ${guide.status}`);
  // JSON is inserted into JavaScript stdin, never interpolated into shell code.
  const script = `
const task = await taskSpace("ground-glass-regression");
console.log({taskSpaceId:task.spaceId});
const page = task.page("p1");
await page.goto("about:blank");
const result = await page.evaluate(${check.toString()}, ${JSON.stringify(shaders)});
console.log(JSON.stringify(result, null, 2));
if (!result || result.ok !== true) throw new Error("Ground-glass suite did not pass");
await task.finish({keep:[]});
`;
  const run = spawnSync('ego-browser', ['nodejs'], {input:script, encoding:'utf8', stdio:['pipe','inherit','inherit']});
  if (run.error) throw run.error;
  process.exitCode = run.status === 0 ? 0 : 1;
} catch (error) {
  console.error(error);
  process.exitCode = 1;
}
