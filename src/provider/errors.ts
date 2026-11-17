export type ProviderErrorCode =
  | "configuration" | "invalid-request" | "unsupported-protocol"
  | "authentication" | "not-found" | "rate-limit" | "http"
  | "network" | "protocol" | "timeout" | "cancelled";

export class ProviderError extends Error {
  readonly code: ProviderErrorCode;
  readonly providerId: string;
  readonly status?: number;
  readonly retryable: boolean;

  constructor(code: ProviderErrorCode, providerId: string, message: string, status?: number) {
    super(message);
    this.name = "ProviderError";
    this.code = code;
    this.providerId = providerId;
    this.status = status;
    this.retryable = code === "network" || code === "timeout" || code === "rate-limit" || (status !== undefined && status >= 500);
  }
}

export function httpError(providerId: string, status: number): ProviderError {
  const code = status === 401 || status === 403 ? "authentication"
    : status === 404 ? "not-found" : status === 429 ? "rate-limit" : "http";
  // Provider bodies can contain credentials or echoed prompts; keep them out of public errors.
  return new ProviderError(code, providerId, `Provider request failed (HTTP ${status}).`, status);
}
