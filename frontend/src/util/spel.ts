/**
 * A SpEL string literal for a value: single-quoted, with a quote inside
 * written as two quotes (SpEL's escape; a backslash is an ordinary
 * character there). Used when the console pre-fills a rule condition
 * from an event's own text, which a source system chose.
 */
export function spelStringLiteral(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}
