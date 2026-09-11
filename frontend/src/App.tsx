import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/AuthContext';
import AppShell from './components/AppShell';
import AlertDetailPage from './pages/AlertDetailPage';
import AlertsPage from './pages/AlertsPage';
import AuditLogPage from './pages/AuditLogPage';
import CallbackPage from './pages/CallbackPage';
import DashboardPage from './pages/DashboardPage';
import OperatorPage from './pages/OperatorPage';
import SettingsPage from './pages/SettingsPage';
import ReportsPage from './pages/ReportsPage';
import RulesPage from './pages/RulesPage';
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
        <Route path="/rules" element={<RulesPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/operator" element={<OperatorPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
