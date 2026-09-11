/** Shapes the gateway returns. Field names match the Java records exactly. */

export interface ConsoleConfig {
  authEnabled: boolean;
  issuerUri?: string;
  clientId?: string;
  hostedUiDomain?: string;
}

export type Role = 'USER' | 'OPERATOR';

export interface Me {
  customerId: string;
  customerName?: string | null;
  subject?: string | null;
  roles: Role[];
  actingAs?: string | null;
  actingAsName?: string | null;
}

export const EVENT_TYPES = ['DATABASE_QUERY', 'API_CALL', 'AUTH_EVENT', 'FILE_ACCESS', 'PERMISSION_CHANGE', 'DATA_EXPORT'] as const;
export type EventType = (typeof EVENT_TYPES)[number];

export const RISK_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type RiskLevel = (typeof RISK_LEVELS)[number];

export interface AuditLogRow {
  eventId: string;
  userId: string | null;
  sessionId: string | null;
  occurredAt: string;
  eventType: string;
  resource: string | null;
  action: string | null;
  riskLevel: string | null;
  anomalous: boolean;
  /** "SOC2:AC-2,GDPR:Art-30" */
  controls: string | null;
}

export interface AuditLogFilter {
  type?: string;
  riskLevel?: string;
  userId?: string;
  anomalous?: boolean;
  q?: string;
  from?: string;
  to?: string;
  limit?: number;
}

export interface AlertRow {
  alertId: string;
  ruleId: string | null;
  ruleName: string | null;
  eventId: string;
  triggeredAt: string;
  notifiedChannels: string | null;
  ruleChannels: string | null;
}

export interface AlertFilter {
  ruleId?: string;
  from?: string;
  to?: string;
  limit?: number;
}

export interface AuditLogDetail {
  event: AuditLogRow;
  alerts: AlertRow[];
}

export interface AlertDetail {
  alert: AlertRow;
  event: AuditLogRow | null;
  configuredChannels: string[];
  notifiedChannels: string[];
  undeliveredChannels: string[];
}

export interface AlertRule {
  ruleId: string;
  customerId: string;
  name: string;
  description: string | null;
  eventType: EventType | null;
  riskThreshold: RiskLevel | null;
  conditionExpression: string | null;
  enabled: boolean;
  notificationChannels: string[];
}

export interface AlertRuleRequest {
  name: string;
  description?: string | null;
  eventType?: EventType | null;
  riskThreshold?: RiskLevel | null;
  conditionExpression?: string | null;
  enabled?: boolean;
  notificationChannels?: string[];
}

export interface RuleDraft {
  eventType?: EventType | null;
  riskThreshold?: RiskLevel | null;
  conditionExpression?: string | null;
}

export interface RuleValidation {
  valid: boolean;
  error?: string | null;
}

export interface DryRun {
  from: string;
  to: string;
  scanned: number;
  matched: number;
  sample: AuditLogRow[];
}

export interface Totals {
  events: number;
  alerts: number;
  critical: number;
  anomalous: number;
  users: number;
}

export interface DayBucket {
  day: string;
  events: number;
  alerts: number;
  byRisk: Record<string, number>;
}

export interface NameCount {
  name: string;
  count: number;
}

export interface Stats {
  window: { from: string; to: string };
  totals: Totals;
  previous: Totals;
  perDay: DayBucket[];
  byType: Record<string, number>;
  byRisk: Record<string, number>;
  byControl: Record<string, number>;
  topUsers: NameCount[];
  topResources: NameCount[];
}

export interface ReportSummary {
  framework: string;
  from: string;
  to: string;
  events: number;
  byControl: Record<string, number>;
  byRisk: Record<string, number>;
  byType: Record<string, number>;
}

export interface OperatorCustomer {
  customerId: string;
  name: string | null;
  events24h: number;
  events7d: number;
  alerts7d: number;
  rules: number;
  lastEventAt: string | null;
}

export interface PlatformStats {
  window: { from: string; to: string };
  totals: { events: number; alerts: number; customers: number };
  perDay: { day: string; events: number; alerts: number; eventsByCustomer: Record<string, number> }[];
  topCustomers: { customerId: string; name: string | null; events: number; alerts: number }[];
  byCustomer: Record<string, string | null>;
}
