import { useEffect, useId, useRef, useState, type ReactNode } from 'react';

interface Props {
  title: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * A modal panel over a backdrop. Escape and the backdrop close it; Tab
 * cycles inside it; whatever had focus before it opened gets it back
 * when it closes. Plain divs rather than <dialog> so the same markup
 * works in every browser and in the test DOM.
 */
export default function Dialog({ title, onClose, children, wide = false }: Props) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const [opener] = useState(() => document.activeElement as HTMLElement | null);

  useEffect(() => {
    const el = panel.current;
    if (el && !el.contains(document.activeElement)) el.focus();
    return () => {
      if (opener && opener.isConnected) opener.focus();
    };
  }, [opener]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !panel.current) return;
      const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !panel.current.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [onClose]);

  return (
    <div className="backdrop" onClick={onClose}>
      <div ref={panel} role="dialog" aria-modal="true" aria-labelledby={titleId} className={`dialog${wide ? ' dialog-wide' : ''}`} tabIndex={-1} onClick={(e) => e.stopPropagation()}>
        <header className="dialog-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="btn btn-ghost" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </header>
        {children}
      </div>
    </div>
  );
}
