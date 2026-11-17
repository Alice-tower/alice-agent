import { z } from "zod";
import type { RuntimeTool } from "../runtime/index.ts";

export function createBackendTimeTool(): RuntimeTool {
  return {
    name: "get_backend_time",
    description: "Read the backend machine's current date, time, timezone and seconds until local midnight. No arguments.",
    parameters: { type: "object", properties: {}, additionalProperties: false },
    execute: async (args, signal) => {
      if (signal.aborted) throw new Error("Operation cancelled");
      z.strictObject({}).parse(args);
      const now = new Date();
      const midnight = new Date(now);
      midnight.setHours(24, 0, 0, 0);
      return JSON.stringify({
        iso: now.toISOString(), local: now.toString(),
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        secondsUntilMidnight: Math.floor((midnight.getTime() - now.getTime()) / 1000),
      });
    },
  };
}
