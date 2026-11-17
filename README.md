# JobRadar

Upload up to 500 companies per list. JobRadar finds the jobs open at each one on official career pages and public ATS boards, sorts them into **Fresher** and **Experienced**, and tracks your applications.

It never scrapes LinkedIn, Naukri, Indeed or Glassdoor; requests to those sites are refused in code.

## Run with Docker

Needs Docker with Compose.

```bash
cp .env.example .env     # then replace every value
docker compose up --build
```

| What | Where |
| --- | --- |
| App | http://localhost:3000 (change with `FRONTEND_PORT`) |
| API and Swagger | http://localhost:8081/swagger-ui.html (change with `BACKEND_PORT`) |

Sign in with `ADMIN_EMAIL` and `ADMIN_PASSWORD` from `.env`. Set `SEED_SAMPLE_DATA=true` in `.env` to start with 20 real companies.

## Run without Docker

Needs JDK 21 or newer, Maven and Node 20 or newer. The local profile uses a file-based H2 database in `backend/data/`, so PostgreSQL is not required.

```bash
# Terminal 1
cd backend
mvn spring-boot:run

# Terminal 2
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 and sign in with `admin@jobradar.local` / `admin12345`. These credentials exist only in the local profile. A list called **Sample Companies** is created for you: go to **Companies** and press **Scan all**.

If port 8080 is taken, run the backend with `SERVER_PORT=8090` and the frontend with `BACKEND_URL=http://localhost:8090`.

To scan JavaScript-only career sites outside Docker, install the browser once:

```bash
cd frontend && npx playwright install chromium
```

Without it those sites are skipped and marked `PARTIAL`; everything else works.

## Company file format

CSV or XLSX, with a header row. A template is available on the **Upload** page, and `backend/src/main/resources/seed/sample-companies.csv` is a working example.

| Column | Required |
| --- | --- |
| `company_name` | Yes |
| `career_page_url` | No, but giving it makes detection faster and more accurate |
| `website` | No |
| `location_preference` | No |

## Environment variables

| Variable | Used for | Default |
| --- | --- | --- |
| `JWT_SECRET` | Signing logins, at least 32 bytes | Required with Docker |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | The login created at first start | Required with Docker |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL (`postgres` profile) | Set by Compose |
| `SPRING_PROFILES_ACTIVE` | `dev` (H2) or `postgres` | `dev` |
| `SEED_SAMPLE_DATA` | Add the 20 sample companies to an empty account | `true` in dev, `false` otherwise |
| `SCAN_CRON`, `SCAN_ZONE` | Daily rescan time | `0 0 6 * * *`, `Asia/Kolkata` |
| `SCAN_SCHEDULE_ENABLED` | Turn the daily rescan off | `true` |
| `PLAYWRIGHT_ENABLED` | Browser rendering for JavaScript sites | `true` |
| `SCAN_ALLOW_PRIVATE_HOSTS` | Let scans reach private addresses; for tests only | `false` |
| `MAIL_ENABLED`, `MAIL_FROM`, `MAIL_TO` | Email digest (`MAIL_TO` defaults to the login email) | `false` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | Mail server | Port 587 |
| `CORS_ALLOWED_ORIGINS` | Allowed browser origins | `http://localhost:5173` |
| `SERVER_PORT` | Backend port outside Docker | `8080` |

Concurrency (default 10) and the per-site request rate (default 1 per second) are on the **Settings** page.

## Email digest

The digest goes out after the scheduled scan, and only if `MAIL_ENABLED=true`. It lists jobs first seen in that scan that match a saved search with **email** ticked. Save a search on the **Jobs** page. If nothing new matches, no email is sent.

## How a job gets its category

Strongest signal first. The reason is stored and shown in the job's details, and you can override the category there; overrides survive rescans.

1. Title says Intern, Trainee, Apprentice, Fresher, New Grad or GET, or the employment type is an internship: **Fresher**
2. Title says Senior, Sr., Lead, Principal, Staff, Manager, Architect, Director or similar: **Experienced**
3. Stated years of experience: minimum 0 is **Fresher**, 1 or more is **Experienced**. If several are stated, the highest minimum counts
4. The source's own experience-level field
5. Softer signals (Junior, Graduate, Campus in the title; "freshers can apply", "entry level" or "2026 batch" in the text): **Fresher**
6. Otherwise **Unspecified**, shown under both the Fresher and Experienced tabs

## Adding a new ATS adapter

1. Add the name to `SourceType` (backend) and `SOURCE_TYPES` (`frontend/src/types/index.ts`).
2. Create a `@Component` implementing `JobSourceAdapter` in `com.jobradar.adapter`. Use `GreenhouseAdapter` as the model:
   - `type()` returns your new `SourceType`.
   - `fetch(target)` returns `FetchResult.complete(jobs)`, or `FetchResult.partial(jobs, note)` if the list may be incomplete. Jobs missing from a partial result are not marked closed.
   - `probe(slug)` is optional. Return true only if a board exists with at least one job.
3. Make every request through `HttpFetcher`. It handles rate limits, retries, timeouts, `robots.txt` and blocked hosts. Use `getJson` for a documented public API and `getPage`, `getSiteJson` or `postJson` for anything else.
4. To detect the ATS from links on career pages, add a pattern to `LINK_PATTERNS` in `CareerDiscoveryService`.
5. Add a test with a mocked `HttpFetcher`, like `GreenhouseLeverAdapterTest`.

Spring finds the component automatically; nothing else needs registering.

## Tests

```bash
cd backend && mvn test            # unit tests, plus API tests on real PostgreSQL (needs Docker running)

cd frontend
npx playwright install chromium   # once
(cd ../backend && mvn -q package -DskipTests)
npm run e2e                       # upload → scan → filter → apply → tracker, against a local fixture site
```

## Known limitations

- **Classification is rule-based.** It reads text patterns, so unusual wording can be misread. Check the stated reason and override when needed.
- **Slug guessing can pick the wrong company.** Without a career URL, JobRadar tries names on public ATS boards, and another company could own that name. Giving `career_page_url` avoids this; you can also correct the source on the **Companies** page.
- **Generic career pages are best effort.** Unusual layouts may yield few or no jobs, and those are marked `PARTIAL`. At most 25 job pages are read per company, to keep scan time bounded.
- **Very large boards are capped.** SmartRecruiters stops at 1,000 postings and Workday at 500, and only the first 25 postings per company get full descriptions, so the others are classified from their titles. These companies show as `PARTIAL`, and their jobs are not auto-closed.
- **Scan time.** With the defaults, a 500-company rescan of ATS boards fits well within 30 minutes. A first scan, or a list made up mostly of generic HTML sites, is limited by the 1 request/second rule and can take close to or over 30 minutes.
- **One scan at a time.** Starting a second returns an error until the first finishes. A restart during a scan marks it `INTERRUPTED`; rescan to continue.
- **`robots.txt` is applied to websites and Workday**, but not to the documented public APIs of Greenhouse, Lever, Ashby, SmartRecruiters and Workable, which exist for programmatic use.
- **EU-hosted Lever boards** (`jobs.eu.lever.co`) are not supported.
- **`location_preference` is stored per company but does not filter results.** Use the location filter or preferred locations on the **Jobs** page.
- **Single-user design.** There is a users table, but no sign-up screen, and the daily scan handles one user reliably.
- **The match score is simple keyword overlap**, the percentage of your profile keywords found in the job.
