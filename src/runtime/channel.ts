export class Channel<T> implements AsyncIterable<T> {
  private readonly values: T[] = [];
  private wake: (() => void) | undefined;
  private ended = false;

  push(value: T): void { if (!this.ended) { this.values.push(value); this.wake?.(); } }
  end(): void { this.ended = true; this.wake?.(); }
  async *[Symbol.asyncIterator](): AsyncGenerator<T> {
    while (true) {
      if (this.values.length) { yield this.values.shift()!; continue; }
      if (this.ended) return;
      await new Promise<void>(resolve => { this.wake = resolve; });
      this.wake = undefined;
    }
  }
}

export function abortable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) return Promise.reject(new Error("Operation cancelled"));
  return new Promise((resolve, reject) => {
    const abort = () => reject(new Error("Operation cancelled"));
    signal.addEventListener("abort", abort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}
