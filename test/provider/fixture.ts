import { createServer, type IncomingHttpHeaders, type ServerResponse } from "node:http";
import type { TestContext } from "node:test";

export interface CapturedRequest {
  path: string;
  headers: IncomingHttpHeaders;
  body: Record<string, unknown>;
}

export async function fixture(t: TestContext, handler: (request: CapturedRequest, response: ServerResponse) => void | Promise<void>) {
  const requests: CapturedRequest[] = [];
  const server = createServer(async (request, response) => {
    try {
      const buffers: Buffer[] = [];
      for await (const chunk of request) buffers.push(Buffer.from(chunk));
      const raw = Buffer.concat(buffers).toString();
      const captured = { path: request.url ?? "", headers: request.headers, body: (raw ? JSON.parse(raw) : {}) as Record<string, unknown> };
      requests.push(captured);
      await handler(captured, response);
    } catch {
      response.destroy();
    }
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("Missing test server address");
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  });
  return { baseUrl: `http://127.0.0.1:${address.port}/v1`, requests };
}

export function chunk(delta: Record<string, unknown>, finishReason: string | null = null) {
  return { id: "completion-1", model: "test-model", choices: [{ index: 0, delta, finish_reason: finishReason }] };
}

export function sse(response: ServerResponse, chunks: unknown[], done = true) {
  response.writeHead(200, { "content-type": "text/event-stream" });
  const body = Buffer.from(chunks.map(value => `data: ${JSON.stringify(value)}\r\n\r\n`).join("") + (done ? "data: [DONE]\r\n\r\n" : ""));
  // Split UTF-8 characters and SSE frame boundaries at the transport layer.
  for (let index = 0; index < body.length; index += 7) response.write(body.subarray(index, index + 7));
  response.end();
}
