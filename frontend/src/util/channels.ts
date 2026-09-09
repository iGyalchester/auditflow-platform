/** "slack,email" -> ["slack", "email"]; null and blanks -> []. */
export function channels(commaSeparated: string | null | undefined): string[] {
  if (!commaSeparated) return [];
  return commaSeparated
    .split(',')
    .map((c) => c.trim())
    .filter(Boolean);
}

/** Configured on the rule today but not reached when the alert fired. */
export function undelivered(ruleChannels: string | null | undefined, notified: string | null | undefined): string[] {
  const reached = new Set(channels(notified));
  return channels(ruleChannels).filter((c) => !reached.has(c));
}
