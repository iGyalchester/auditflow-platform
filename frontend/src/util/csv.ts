import type { AuditLogRow } from '../api/types';

/**
 * RFC 4180: quote when needed, double the quotes inside, CRLF line ends.
 * Plus the spreadsheet rule: a cell that starts with = + - @ or a tab or
 * carriage return would be read as a formula by Excel and LibreOffice,
 * and every exported column is text a source system chose (a resource
 * name, an action, a user id), so such a cell is prefixed with an
 * apostrophe, which spreadsheets show as the literal text.
 */
function cell(value: unknown): string {
  if (value === null || value === undefined) return '';
  let text = String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export const AUDIT_LOG_COLUMNS: (keyof AuditLogRow)[] = [
  'eventId',
  'occurredAt',
  'eventType',
  'userId',
  'sessionId',
  'resource',
  'action',
  'riskLevel',
  'anomalous',
  'controls',
];

/** The rows on screen as a spreadsheet: what the explorer shows, no more. */
export function auditLogCsv(rows: AuditLogRow[]): string {
  const lines = [AUDIT_LOG_COLUMNS.join(',')];
  for (const row of rows) {
    lines.push(AUDIT_LOG_COLUMNS.map((c) => cell(row[c])).join(','));
  }
  return lines.join('\r\n') + '\r\n';
}

/** Hands the browser a file to save. */
export function downloadText(filename: string, text: string, type = 'text/csv'): void {
  const blob = new Blob([text], { type: `${type};charset=utf-8` });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
