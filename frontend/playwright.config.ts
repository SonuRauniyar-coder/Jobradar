import { defineConfig, devices } from '@playwright/test';

// Hermetic: the "company website" is a local fixture server, and data lives in an in-memory database.
// Build the backend first (mvn package -DskipTests in ../backend).
const BACKEND_PORT = 18081;
const FRONTEND_PORT = 5174;

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  workers: 1,
  use: { baseURL: `http://localhost:${FRONTEND_PORT}`, ...devices['Desktop Chrome'] },
  webServer: [
    { command: 'node e2e/fixture-site.mjs', port: 4599, reuseExistingServer: false },
    {
      command: 'java -jar ../backend/target/jobradar-backend-1.0.0.jar',
      url: `http://localhost:${BACKEND_PORT}/actuator/health`,
      reuseExistingServer: false,
      timeout: 90_000,
      env: {
        SERVER_PORT: String(BACKEND_PORT),
        CORS_ALLOWED_ORIGINS: `http://localhost:${FRONTEND_PORT}`,
        SPRING_DATASOURCE_URL: 'jdbc:h2:mem:e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1',
        ADMIN_EMAIL: 'e2e@jobradar.local',
        ADMIN_PASSWORD: 'e2e-password',
        SEED_SAMPLE_DATA: 'false',
        SCAN_ALLOW_PRIVATE_HOSTS: 'true',
        SCAN_SCHEDULE_ENABLED: 'false',
        PLAYWRIGHT_ENABLED: 'true',
      },
    },
    {
      command: `npx vite --port ${FRONTEND_PORT} --strictPort`,
      port: FRONTEND_PORT,
      reuseExistingServer: false,
      env: { BACKEND_URL: `http://localhost:${BACKEND_PORT}` },
    },
  ],
});
