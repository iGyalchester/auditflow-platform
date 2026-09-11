import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// Vitest runs without globals, so Testing Library cannot register its own
// afterEach; unmount between tests here or every render stacks up. Storage
// is cleared too, or one test's dev session leaks into the next.
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
});
