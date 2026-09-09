import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/AuthContext';
import AppShell from './components/AppShell';
import AlertDetailPage from './pages/AlertDetailPage';
import AlertsPage from './pages/AlertsPage';
import AuditLogPage from './pages/AuditLogPage';
import CallbackPage from './pages/CallbackPage';
import ComingSoon from './pages/ComingSoon';
import DashboardPage from './pages/DashboardPage';
import SignInPage from './pages/SignInPage';

/**
 * Route table only - main.tsx supplies the BrowserRouter and
 * AuthProvider, tests supply a MemoryRouter instead. Everything under
 * the AppShell layout route requires a signed-in session.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/sign-in" element={<SignInPage />} />
      <Route path="/callback" element={<CallbackPage />} />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/audit-log" element={<AuditLogPage />} />
        <Route path="/alerts" element={<AlertsPage />} />
        <Route path="/alerts/:alertId" element={<AlertDetailPage />} />
        <Route path="/rules" element={<ComingSoon title="Rules" slice={5} blurb="Edit alert rules with live validation and a dry run over real events." />} />
        <Route path="/reports" element={<ComingSoon title="Reports" slice={5} blurb="SOC 2, GDPR and HIPAA evidence reports over the current window." />} />
        <Route path="/operator" element={<ComingSoon title="Operator" slice={6} blurb="Every customer on the platform, and a way to view the console as one of them." />} />
        <Route path="/settings" element={<ComingSoon title="Settings" slice={6} blurb="Who you are, your session, and the API's rate budget." />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
