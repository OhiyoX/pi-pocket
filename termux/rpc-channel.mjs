/** Correlates JSONL RPC replies and owns every pending request's lifetime. */
export class RpcChannel {
  constructor(write) {
    this.write = write;
    this.pending = new Map();
    this.seq = 0;
    this.closed = false;
  }

  request(type, params = {}, timeoutMs = 120000) {
    if (this.closed) return Promise.reject(new Error('pi channel closed'));
    // bash_execution_update carries this ID back to the shell view.
    let id = params.id;
    if (!id) { do { id = `r${++this.seq}`; } while (this.pending.has(id)); }
    if (this.pending.has(id)) return Promise.reject(new Error(`duplicate pi request id: ${id}`));
    return new Promise((resolve, reject) => {
      const finish = (error, value) => {
        const p = this.pending.get(id);
        if (!p) return;
        this.pending.delete(id);
        clearTimeout(p.timer);
        if (error) reject(error); else resolve(value);
      };
      const timer = setTimeout(() => finish(new Error(`pi command timeout: ${type}`)), timeoutMs);
      this.pending.set(id, { timer, finish });
      try {
        this.write(JSON.stringify({ ...params, id, type }) + '\n', error => {
          if (error) finish(error);
        });
      } catch (error) { finish(error); }
    });
  }

  accept(record) {
    if (record?.type !== 'response') return false;
    const p = this.pending.get(record.id);
    if (!p) return false;
    p.finish(null, record);
    return true;
  }

  close(error = new Error('pi channel closed')) {
    this.closed = true;
    for (const p of [...this.pending.values()]) p.finish(error);
  }
}
