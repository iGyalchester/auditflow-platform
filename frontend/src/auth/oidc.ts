import type { ConsoleConfig } from '../api/types';

/**
 * Cognito sign-in, for when the gateway says auth is enforced. The whole
 * OpenID Connect dance - authorization code + PKCE, the redirect to the
 * hosted UI, the callback, token storage and silent renewal - is
 * oidc-client-ts's job; this file only tells it where the pool is.
 *
 * Loaded lazily so the open-auth path (local development, most tests)
 * never imports the library.
 */
export interface OidcSession {
  /** The ID token: the gateway requires token_use=id (it carries the customer claim). */
  idToken: string;
  expiresAt: number | null;
  subject: string | null;
  email: string | null;
}

export interface Oidc {
  currentUser(): Promise<OidcSession | null>;
  signIn(returnTo: string): Promise<void>;
  completeSignIn(): Promise<string>;
  signOut(): Promise<void>;
}

const SCOPE = 'openid email profile auditflow-api/read auditflow-api/write';

export async function createOidc(config: ConsoleConfig): Promise<Oidc> {
  if (!config.issuerUri || !config.clientId) {
    throw new Error('auth is enforced but the gateway published no issuer or client id');
  }
  const { UserManager, WebStorageStateStore } = await import('oidc-client-ts');
  const origin = window.location.origin;
  const manager = new UserManager({
    authority: config.issuerUri,
    client_id: config.clientId,
    redirect_uri: `${origin}/callback`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: SCOPE,
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });

  function toSession(user: { id_token?: string; expires_at?: number; profile: { sub?: string; email?: string } } | null): OidcSession | null {
    if (!user || !user.id_token) return null;
    return {
      idToken: user.id_token,
      expiresAt: user.expires_at ? user.expires_at * 1000 : null,
      subject: user.profile.sub ?? null,
      email: user.profile.email ?? null,
    };
  }

  return {
    async currentUser() {
      const user = await manager.getUser();
      if (user && user.expired) {
        try {
          return toSession(await manager.signinSilent());
        } catch {
          return null;
        }
      }
      return toSession(user);
    },
    signIn(returnTo) {
      return manager.signinRedirect({ state: { returnTo } });
    },
    async completeSignIn() {
      const user = await manager.signinCallback();
      const state = user?.state as { returnTo?: string } | undefined;
      return state?.returnTo ?? '/';
    },
    async signOut() {
      await manager.removeUser();
      // Cognito's hosted UI has its own session cookie and its own logout
      // shape (client_id + logout_uri), which is why the discovery document
      // carries no end_session_endpoint and the URL is built by hand.
      if (config.hostedUiDomain) {
        const url = new URL('/logout', config.hostedUiDomain);
        url.searchParams.set('client_id', config.clientId!);
        url.searchParams.set('logout_uri', `${origin}/`);
        window.location.assign(url.toString());
      }
    },
  };
}
