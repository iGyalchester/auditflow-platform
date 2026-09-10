import { useId, useRef, type ReactNode } from 'react';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface Props {
  title: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}

/**
 * A modal panel over a backdrop. Escape and the backdrop close it; focus
 * is trapped inside and returned to the opener after (see useFocusTrap).
 * Plain divs rather than <dialog> so the same markup works in every
 * browser and in the test DOM.
 */
export default function Dialog({ title, onClose, children, wide = false }: Props) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  useFocusTrap(panel, onClose);

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
