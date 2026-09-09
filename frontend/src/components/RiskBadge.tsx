/** LOW / MEDIUM / HIGH / CRITICAL as a labelled chip; the word carries the meaning, the tint helps. */
export default function RiskBadge({ level }: { level: string | null | undefined }) {
  if (!level) return <span className="badge badge-unknown">Unknown</span>;
  const key = level.toLowerCase();
  const label = key.charAt(0).toUpperCase() + key.slice(1);
  return <span className={`badge badge-${key}`}>{label}</span>;
}
