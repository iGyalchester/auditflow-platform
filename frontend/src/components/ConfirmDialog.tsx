import { useState, type ReactNode } from 'react';
import Dialog from './Dialog';

interface Props {
  title: string;
  confirmLabel: string;
  onConfirm: () => Promise<void> | void;
  onClose: () => void;
  children: ReactNode;
}

/** "Are you sure?" with the consequence spelled out and the dangerous button last. */
export default function ConfirmDialog({ title, confirmLabel, onConfirm, onClose, children }: Props) {
  const [busy, setBusy] = useState(false);
  async function confirm() {
    setBusy(true);
    try {
      await onConfirm();
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog title={title} onClose={onClose}>
      <div className="confirm-body">{children}</div>
      <div className="actions">
        <button type="button" className="btn" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button type="button" className="btn btn-danger" onClick={confirm} disabled={busy} autoFocus>
          {confirmLabel}
        </button>
      </div>
    </Dialog>
  );
}
