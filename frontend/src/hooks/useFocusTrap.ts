import { useEffect, useState, type RefObject } from 'react';

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The behaviour every modal panel shares, written once: focus moves into
 * the panel when it opens, Tab and Shift+Tab cycle inside it, Escape
 * closes it (in the capture phase, so nothing behind the panel sees the
 * key), and whatever had focus before it opened gets it back when it
 * closes, so a keyboard user lands on the button or row they came from.
 */
export function useFocusTrap(panel: RefObject<HTMLElement | null>, onClose: () => void): void {
  // captured during the first render, before anything inside the panel
  // takes focus, so it is the element that opened us
  const [opener] = useState(() => document.activeElement as HTMLElement | null);

  useEffect(() => {
    const el = panel.current;
    if (el && !el.contains(document.activeElement)) el.focus();
    return () => {
      if (opener && opener.isConnected) opener.focus();
    };
  }, [panel, opener]);

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
  }, [panel, onClose]);
}
