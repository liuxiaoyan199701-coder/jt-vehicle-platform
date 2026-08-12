export type Listener<T> = (event: T) => void;

export class TypedEventEmitter<Events extends object> {
  private readonly listeners = new Map<keyof Events, Set<Listener<Events[keyof Events]>>>();

  on<K extends keyof Events>(type: K, listener: Listener<Events[K]>): () => void {
    let bucket = this.listeners.get(type);
    if (!bucket) {
      bucket = new Set();
      this.listeners.set(type, bucket);
    }
    bucket.add(listener as Listener<Events[keyof Events]>);
    return () => this.off(type, listener);
  }

  off<K extends keyof Events>(type: K, listener: Listener<Events[K]>): void {
    const bucket = this.listeners.get(type);
    bucket?.delete(listener as Listener<Events[keyof Events]>);
    if (bucket?.size === 0) {
      this.listeners.delete(type);
    }
  }

  protected emit<K extends keyof Events>(type: K, event: Events[K]): void {
    const bucket = this.listeners.get(type);
    if (!bucket) return;
    for (const listener of [...bucket]) {
      listener(event);
    }
  }

  protected clearListeners(): void {
    this.listeners.clear();
  }
}
