/**
 * Which image tags move, and which pin a release.
 *
 * <p>Lives in core because two layers have to agree on it: the containers page decides whether
 * to offer an update, and {@link ContainerLifecycle} decides whether the request is worth
 * sending. They disagreed once — the page offered `latest → latest` and the store dropped it
 * as a no-op — so the button did nothing at all.
 *
 * <p>Mirrors `ImageRef.FLOATING` on the backend.
 */
const FLOATING_TAGS = new Set(['latest', 'main', 'edge', 'nightly', 'dev']);

export function isFloatingTag(tag: string): boolean {
  return FLOATING_TAGS.has((tag ?? '').trim().toLowerCase());
}
