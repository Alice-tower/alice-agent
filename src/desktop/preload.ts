import { contextBridge, ipcRenderer } from "electron";
import type { DesktopBridge, ConnectionState } from "./bridge.ts";
import type { RunSnapshot } from "../api/contracts.ts";

const bridge: DesktopBridge = {
  settings: () => ipcRenderer.invoke("desktop:settings"),
  restore: () => ipcRenderer.invoke("desktop:restore"),
  connect: input => ipcRenderer.invoke("desktop:connect", input),
  models: id => ipcRenderer.invoke("desktop:models", id),
  conversations: () => ipcRenderer.invoke("desktop:conversations"),
  createConversation: () => ipcRenderer.invoke("desktop:createConversation"),
  conversation: id => ipcRenderer.invoke("desktop:conversation", id),
  submit: (id, message) => ipcRenderer.invoke("desktop:submit", id, message),
  cancel: id => ipcRenderer.invoke("desktop:cancel", id),
  watch: id => ipcRenderer.invoke("desktop:watch", id),
  onRun: handler => {
    const listener = (_event: Electron.IpcRendererEvent, run: RunSnapshot) => handler(run);
    ipcRenderer.on("desktop:run", listener);
    return () => ipcRenderer.removeListener("desktop:run", listener);
  },
  onConnection: handler => {
    const listener = (_event: Electron.IpcRendererEvent, state: ConnectionState) => handler(state);
    ipcRenderer.on("desktop:connection", listener);
    return () => ipcRenderer.removeListener("desktop:connection", listener);
  },
};
contextBridge.exposeInMainWorld("alice", bridge);
