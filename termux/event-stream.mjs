/** Bound slow SSE consumers instead of retaining unlimited tool output in RAM. */
export function writeEvent(response, line) {
  if (response.destroyed || response.writableEnded) return false;
  try {
    response.write(line);
    // A single large event commonly exceeds highWaterMark on a healthy socket.
    // Bound total buffered bytes instead of treating write(false) as disconnect.
    if (response.writableLength <= 64 * 1024 * 1024) return true;
  } catch { /* closed socket */ }
  response.destroy();
  return false;
}

/** Async tool rendering must preserve the worker's wire order and ownership. */
export class OrderedEvents {
  constructor(emit, renderTimeoutMs = 5000) {
    this.emit = emit;
    this.renderTimeoutMs = renderTimeoutMs;
    this.tail = Promise.resolve();
  }
  enqueue(record, sessionFile, render = null) {
    this.tail = this.tail.then(async () => {
      let extra = {};
      if (render) {
        let timer;
        try {
          extra = await Promise.race([
            Promise.resolve().then(render),
            new Promise(resolve => { timer = setTimeout(() => resolve({}), this.renderTimeoutMs); }),
          ]) || {};
        } catch { /* raw fallback */ }
        finally { clearTimeout(timer); }
      }
      this.emit({ kind: 'pi', record: { ...record, ...extra }, sessionFile });
    });
    return this.tail;
  }
}
