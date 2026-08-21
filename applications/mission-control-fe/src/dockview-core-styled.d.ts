/**
 * dockview-core 8.x publishes its stylesheet only inside its bundled builds:
 * the `import`/`require` entry points (`dist/package/main.*`) carry the code
 * alone, and the package ships no `.css` file to add alongside them. The styled
 * bundle at `dist/dockview-core.js` appends the stylesheet to `document.head`
 * when it loads, so that is the entry the app imports — without it the dock
 * renders with no sashes, no tab strip and no overlay positioning.
 *
 * Two things about this are the library's private business rather than its API: this file
 * name, and the shape `SerializedDockview` persists a layout in (which terminal-tabs.ts walks
 * by hand in pruneLayout). Neither would fail at compile time if a release moved it, so the
 * dependency is pinned to `~8.2.0` and a spec puts a real `toJSON()` back through the prune
 * and into a fresh dock — a shape change fails that test instead of silently costing the
 * saved arrangement.
 *
 * The published types only cover the package root, so they are mapped across
 * here. That keeps the deep import fully typed and, because the type side and
 * the value side resolve to the same declarations, keeps exactly one copy of the
 * library in the bundle: everything but `terminal-dock.ts` imports types only.
 */
declare module 'dockview-core/dist/dockview-core.js' {
  export * from 'dockview-core';
}
