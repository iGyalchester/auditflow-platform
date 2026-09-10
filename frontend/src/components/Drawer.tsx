import { useId, useRef, type ReactNode } from 'react';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface Props {
  title: string;
  onClose: () => void;
  children: ReactNode;
}

/**
 * A side panel over a backdrop for "look at one thing without leaving
 * the list". Escape and the backdrop close it; focus is trapped inside
 * and returned to the opener after (see useFocusTrap).
 */
export default function Drawer({ title, onClose, children }: Props) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  useFocusTrap(panel, onClose);

  return (
    <div className="backdrop backdrop-side" onClick={onClose}>
      <div ref={panel} role="dialog" aria-modal="true" aria-labelledby={titleId} className="drawer" tabIndex={-1} onClick={(e) => e.stopPropagation()}>
        <header className="drawer-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="btn btn-ghost" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </header>
        <div className="drawer-body">{children}</div>
      </div>
    </div>
  );
}
