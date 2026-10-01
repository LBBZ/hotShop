/** A cancellable read with a watchdog; comments/heartbeats also reset the deadline. */
export function readStreamChunk(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal,
  idleTimeoutMs: number,
  timeoutError: () => Error,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (
      result?: ReadableStreamReadResult<Uint8Array>,
      error?: unknown,
    ) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      signal.removeEventListener("abort", abort);
      if (result) resolve(result);
      else
        reject(error instanceof Error ? error : new Error("状态流读取失败。"));
    };
    const abort = () => {
      finish(undefined, new DOMException("状态流读取已取消", "AbortError"));
      void reader.cancel().catch(() => undefined);
    };
    const timer = window.setTimeout(() => {
      finish(undefined, timeoutError());
      void reader.cancel().catch(() => undefined);
    }, idleTimeoutMs);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) {
      abort();
      return;
    }
    void reader.read().then(
      (result) => finish(result),
      (error: unknown) => finish(undefined, error),
    );
  });
}
