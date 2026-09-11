/**
 * Placeholder blocks shown while a page loads, in the shape of what will
 * appear, so the layout does not jump when the data lands. Announced
 * once as "Loading"; the individual blocks are decorative.
 */
export default function Skeleton({ rows = 3, tiles = 0 }: { rows?: number; tiles?: number }) {
  return (
    <div aria-busy="true" aria-label="Loading" role="status" className="skeleton-group">
      {tiles > 0 && (
        <div className="stats">
          {Array.from({ length: tiles }, (_, i) => (
            <div key={i} className="skeleton skeleton-tile" aria-hidden="true" />
          ))}
        </div>
      )}
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton skeleton-row" aria-hidden="true" />
      ))}
    </div>
  );
}
