import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import App from '../App';

function stubConfig(body: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })),
  );
}

describe('placeholder shell', () => {
  it('shows the console and the sign-in mode from /config.json', async () => {
    stubConfig({ authEnabled: false });
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: 'Console' })).toBeInTheDocument();
    expect(await screen.findByText(/local development/)).toBeInTheDocument();
  });

  it('names Cognito when auth is enforced', async () => {
    stubConfig({ authEnabled: true, issuerUri: 'https://issuer', clientId: 'abc' });
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByText(/Cognito/)).toBeInTheDocument();
  });

  it('says so when the config cannot be loaded', async () => {
    stubConfig({ error: 'internal' }, 500);
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByText(/could not be loaded/)).toBeInTheDocument();
  });
});
