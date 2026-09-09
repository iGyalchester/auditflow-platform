import { useAuth } from '../auth/AuthContext';

/** Operators only: a loud reminder that every number on screen is another customer's. */
export default function ActingBanner() {
  const { me, actAs } = useAuth();
  if (!me?.actingAs) return null;
  return (
    <div className="banner banner-acting" role="status">
      <span>
        Viewing <strong>{me.actingAsName ?? me.actingAs}</strong> as an operator.
      </span>
      <button type="button" className="btn btn-ghost" onClick={() => actAs(null)}>
        Back to my view
      </button>
    </div>
  );
}
