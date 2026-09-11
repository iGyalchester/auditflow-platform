import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { rangeQuery } from '../util/timeRange';
import Dialog from './Dialog';

const GO: Record<string, string> = { d: '/dashboard', l: '/audit-log', a: '/alerts', r: '/rules', p: '/reports' };

function inField(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

/**
 * App-wide keys: "/" focuses the audit log's search box (navigating there
 * first if needed), "?" shows this list, "g" then a letter jumps to a
 * page. Never while typing in a field, and never with a modifier held.
 */
export default function Shortcuts() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let pendingGo = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    function onKey(event: KeyboardEvent) {
      if (event.ctrlKey || event.metaKey || event.altKey || inField(event.target)) return;
      if (document.querySelector('[role="dialog"]') && event.key !== '?') return;
      const range = rangeQuery(params);
      if (pendingGo) {
        pendingGo = false;
        clearTimeout(timer);
        const to = GO[event.key];
        if (to) {
          event.preventDefault();
          navigate(`${to}${range}`);
        }
        return;
      }
      if (event.key === 'g') {
        pendingGo = true;
        timer = setTimeout(() => {
          pendingGo = false;
        }, 1500);
        return;
      }
      if (event.key === '?') {
        event.preventDefault();
        setOpen((v) => !v);
        return;
      }
      if (event.key === '/') {
        event.preventDefault();
        const box = document.querySelector<HTMLInputElement>('input[type="search"]');
        if (box) box.focus();
        else navigate(`/audit-log${range}`, { state: { focusSearch: true } });
      }
    }
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      clearTimeout(timer);
    };
  }, [navigate, params]);

  if (!open) return null;
  return (
    <Dialog title="Keyboard shortcuts" onClose={() => setOpen(false)}>
      <dl className="details shortcuts">
        <dt>
          <kbd>/</kbd>
        </dt>
        <dd>Focus the search box on the audit log</dd>
        <dt>
          <kbd>?</kbd>
        </dt>
        <dd>Show or hide this list</dd>
        <dt>
          <kbd>g</kbd> then <kbd>d</kbd>, <kbd>l</kbd>, <kbd>a</kbd>, <kbd>r</kbd> or <kbd>p</kbd>
        </dt>
        <dd>Dashboard, audit log, alerts, rules, reports</dd>
        <dt>
          <kbd>Esc</kbd>
        </dt>
        <dd>Close a drawer or dialog</dd>
      </dl>
    </Dialog>
  );
}
