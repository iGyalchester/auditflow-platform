import { useState } from 'react';
import { fetchFrameworks, fetchReport, fetchReportSummary } from '../api/client';
import type { ReportSummary } from '../api/types';
import EmptyState from '../components/EmptyState';
import ErrorBanner from '../components/ErrorBanner';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';
import { useAsync } from '../hooks/useAsync';
import { useTimeRange } from '../hooks/useTimeRange';
import { downloadText } from '../util/csv';
import { formatCount, frameworkLabel, humanize } from '../util/format';

const PREVIEW_LINES = 12;

const BLURB: Record<string, string> = {
  SOC2: 'Access, identity and change evidence for the Trust Services Criteria.',
  GDPR: 'Processing records and access to personal data, by article.',
  HIPAA: 'Access to protected health information and the safeguards around it.',
};

/**
 * One card per framework over the window at the top of the page: the
 * report as numbers first (events, by control, by risk, by type), a
 * preview of the first lines, and the download. A window with too many
 * events is a 413 from the gateway, explained rather than retried:
 * narrow the range.
 */
export default function ReportsPage() {
  const { range, fromIso, toIso } = useTimeRange();
  const frameworks = useAsync<string[]>(fetchFrameworks, []);

  return (
    <>
      <div className="page-title">
        <div>
          <h1>Reports</h1>
          <p className="muted">Evidence reports over {range.label.toLowerCase()}, all times UTC.</p>
        </div>
      </div>

      {frameworks.error && <ErrorBanner message={frameworks.error} onRetry={frameworks.reload} />}
      {!frameworks.error && frameworks.loading && <Skeleton rows={3} />}

      {frameworks.data && frameworks.data.map((fw) => <ReportCard key={`${fw}|${fromIso}|${toIso}`} framework={fw} fromIso={fromIso} toIso={toIso} />)}
    </>
  );
}

function ReportCard({ framework, fromIso, toIso }: { framework: string; fromIso: string; toIso: string }) {
  const { notify } = useToast();
  const summary = useAsync<ReportSummary>(() => fetchReportSummary(framework, fromIso, toIso), [framework, fromIso, toIso]);
  const [preview, setPreview] = useState<string | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const label = frameworkLabel(framework);

  async function showPreview() {
    setPreviewing(true);
    try {
      const { text } = await fetchReport(framework, fromIso, toIso);
      const lines = text.split('\n');
      setPreview(lines.slice(0, PREVIEW_LINES).join('\n') + (lines.length > PREVIEW_LINES ? `\n… ${formatCount(lines.length - PREVIEW_LINES)} more lines` : ''));
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Could not load the preview.', 'error');
    } finally {
      setPreviewing(false);
    }
  }

  async function download() {
    setDownloading(true);
    try {
      const { text, filename } = await fetchReport(framework, fromIso, toIso);
      downloadText(filename ?? `${framework.toLowerCase()}-${fromIso.slice(0, 10)}.txt`, text, 'text/plain');
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Could not download the report.', 'error');
    } finally {
      setDownloading(false);
    }
  }

  const tooLarge = summary.status === 413;

  return (
    <section className="card report-card" aria-labelledby={`report-${framework}`}>
      <div className="card-title">
        <div>
          <h2 id={`report-${framework}`}>{label}</h2>
          <p className="muted small">{BLURB[framework.toUpperCase()] ?? 'Evidence report.'}</p>
        </div>
        <div className="report-actions">
          <button type="button" className="btn" onClick={showPreview} disabled={previewing || tooLarge || !summary.data}>
            {previewing ? 'Loading…' : 'Preview'}
          </button>
          <button type="button" className="btn btn-primary" onClick={download} disabled={downloading || tooLarge || !summary.data}>
            {downloading ? 'Preparing…' : 'Download .txt'}
          </button>
        </div>
      </div>

      {summary.loading && <Skeleton rows={1} />}
      {summary.error && !tooLarge && <ErrorBanner message={summary.error} onRetry={summary.reload} />}
      {tooLarge && (
        <div className="banner banner-error" role="alert">
          <span>This window holds more {label} events than one report covers (10,000). Narrow the time range at the top of the page.</span>
        </div>
      )}

      {summary.data && summary.data.events === 0 && <EmptyState title={`No ${label} evidence in this window`}>No event was classified for a {label} control. Widen the range, or it may simply be quiet.</EmptyState>}

      {summary.data && summary.data.events > 0 && (
        <div className="report-body">
          <div className="report-total">
            <div className="stat-value">{formatCount(summary.data.events)}</div>
            <div className="muted small">events in the report</div>
          </div>
          <Breakdown title="By control" entries={summary.data.byControl} />
          <Breakdown title="By risk" entries={summary.data.byRisk} labels={humanize} />
          <Breakdown title="By type" entries={summary.data.byType} labels={humanize} />
        </div>
      )}

      {preview !== null && (
        <pre className="report-preview" aria-label={`${label} report preview`}>
          {preview}
        </pre>
      )}
    </section>
  );
}

function Breakdown({ title, entries, labels = (k: string) => k }: { title: string; entries: Record<string, number>; labels?: (key: string) => string }) {
  const rows = Object.entries(entries);
  return (
    <table className="breakdown">
      <caption>{title}</caption>
      <tbody>
        {rows.length === 0 && (
          <tr>
            <td className="muted">—</td>
            <td className="num">0</td>
          </tr>
        )}
        {rows.map(([k, v]) => (
          <tr key={k}>
            <td>{labels(k)}</td>
            <td className="num">{formatCount(v)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

