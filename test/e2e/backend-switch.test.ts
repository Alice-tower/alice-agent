import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdir, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { resolve } from "node:path";
import { _electron } from "playwright";

test("a delayed request from the previous backend cannot overwrite the current workspace", async t => {
  const temp = resolve("tmp/backend-switch", randomUUID());
  await mkdir(temp, { recursive: true });
  const main = resolve(temp, "main.cjs");
  const preload = resolve(temp, "preload.cjs");
  await writeFile(main, `const {app,BrowserWindow}=require('electron');
    app.setPath('userData',process.env.ALICE_DESKTOP_DATA_DIR);
    app.whenReady().then(()=>new BrowserWindow({webPreferences:{preload:process.env.ALICE_TEST_PRELOAD,contextIsolation:true,sandbox:true,nodeIntegration:false}}).loadFile(process.env.ALICE_TEST_PAGE));
    app.on('window-all-closed',()=>app.quit());`);
  await writeFile(preload, `const {contextBridge,ipcRenderer}=require('electron');
    let backend='A',lists=0;
    const ok=value=>({ok:true,value});
    const conversation=id=>({id,title:'Backend-'+id,createdAt:0,updatedAt:0});
    const info={version:1,providers:[{id:'default',protocol:'fake'}],tools:[]};
    contextBridge.exposeInMainWorld('alice',{
      settings:async()=>ok({baseUrl:'http://localhost:4001',configured:true}),restore:async()=>ok(info),
      connect:async()=>{backend='B';return ok(info)},models:async()=>ok([{id:'model',name:'model'}]),
      conversations:async()=>{const captured=backend;if(++lists===2)await new Promise(resolve=>ipcRenderer.once('test:release',resolve));return ok([conversation(captured)])},
      conversation:async id=>id===backend?ok({conversation:conversation(backend),messages:[],runs:[]}):{ok:false,error:'not found'},
      createConversation:async()=>ok(conversation('new-A')),watch:async()=>ok(),onRun:()=>()=>{},onConnection:()=>()=>{}
    });`);
  const env: Record<string, string> = Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined));
  delete env.ELECTRON_RUN_AS_NODE;
  Object.assign(env, { ALICE_DESKTOP_DATA_DIR: resolve(temp, "desktop"), ALICE_TEST_PRELOAD: preload, ALICE_TEST_PAGE: resolve("dist/desktop/index.html") });
  const app = await _electron.launch({ args: [main], env });
  t.after(async () => { await app.close(); });
  const page = await app.firstWindow();
  await page.getByTestId("conversation-list").getByText("Backend-A", { exact: true }).waitFor();
  await page.getByTestId("new-conversation").click();
  await page.getByTestId("edit-connection").click();
  await page.getByTestId("backend-url").fill("http://localhost:4002");
  await page.getByTestId("access-token").fill("test-token");
  await page.getByTestId("connect").click();
  await page.getByTestId("conversation-list").getByText("Backend-B", { exact: true }).waitFor();
  await app.evaluate(({ BrowserWindow }) => { BrowserWindow.getAllWindows()[0]!.webContents.send("test:release"); });
  await page.waitForTimeout(100);
  const sidebar = await page.getByTestId("conversation-list").innerText();
  assert.match(sidebar, /Backend-B/);
  assert.doesNotMatch(sidebar, /Backend-A/);
  assert.ok(!await page.getByTestId("error").isVisible());
});
