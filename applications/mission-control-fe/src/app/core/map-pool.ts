/**
 * Runs `fn` over `items` with at most `limit` in flight at once, answering in `items` order.
 *
 * Every fan-out in the store ends at a Docker daemon — one read per container, per profile,
 * per host — so an unbounded `Promise.all` opens as many concurrent requests as the fleet is
 * large, every tick. Five slices cap themselves with this.
 *
 * A function, not a method on `StoreContext`: nothing about it is context. It reached for no
 * config, no api client and no toast, and a kernel every slice injects is the wrong place to
 * keep a helper that could as well be called by anything.
 */
export async function mapPool<T, R>(
  items: readonly T[],
  limit: number,
  fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const idx = next++;
      results[idx] = await fn(items[idx]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}
