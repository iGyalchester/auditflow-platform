import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** Where Cognito sends the browser back; the code in the URL becomes a session here. */
export default function CallbackPage() {
  const { completeCognitoSignIn, loading } = useAuth();
  const navigate = useNavigate();
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (loading) return;
    let alive = true;
    completeCognitoSignIn()
      .then((returnTo) => alive && navigate(returnTo, { replace: true }))
      .catch((e) => alive && setProblem(e instanceof Error ? e.message : 'Sign-in could not be completed.'));
    return () => {
      alive = false;
    };
  }, [loading, completeCognitoSignIn, navigate]);

  return (
    <main className="centered">
      <div className="card narrow">
        {problem ? (
          <>
            <p className="error" role="alert">
              {problem}
            </p>
            <a className="btn" href="/sign-in">
              Try again
            </a>
          </>
        ) : (
          <p className="muted" role="status">
            Finishing sign-in…
          </p>
        )}
      </div>
    </main>
  );
}
