import { useEffect, useMemo, useRef, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createColumnHelper, flexRender, getCoreRowModel, SortingState, useReactTable } from '@tanstack/react-table';
import { api, del, download, patch, post, put, safeHttpUrl } from '../api/client';
import JobDrawer from '../components/JobDrawer';
import StatsCards from '../components/StatsCards';
import { ROLE_LABELS } from '../types/jobs';
import type { AppStatus, Category, JobFilters, JobRow, Page, RoleCategory, SavedSearch, Settings, WorkMode } from '../types/jobs';

const TABS: { label: string; value?: Category }[] = [
  { label: 'Fresher', value: 'FRESHER' },
  { label: 'Experienced', value: 'EXPERIENCED' },
  { label: 'Unspecified', value: 'UNSPECIFIED' },
  { label: 'All' },
];
const STATUSES: AppStatus[] = ['NEW', 'SEEN', 'SAVED', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED', 'IGNORED'];
/** Text filters wait this long after the last keystroke before searching. */
const TYPING_DELAY_MS = 300;
const CITIES = ['Gurugram', 'Noida', 'Bangalore', 'Hyderabad', 'Pune', 'Mumbai', 'Chennai'];
const categoryStyle: Record<Category, string> = {
  FRESHER: 'bg-green-100 text-green-800',
  EXPERIENCED: 'bg-blue-100 text-blue-800',
  UNSPECIFIED: 'bg-slate-200 text-slate-700',
};
const helper = createColumnHelper<JobRow>();

function queryString(filters: JobFilters, extra: Record<string, string | number | undefined>) {
  const params = new URLSearchParams();
  Object.entries({ ...filters, ...extra }).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '' && value !== false) params.set(key, String(value));
  });
  return params.toString();
}

/** Saved searches store unset filters as null; drop those so they are not sent as the text "null". */
function cleanFilters(filters: JobFilters): JobFilters {
  return Object.fromEntries(
    Object.entries(filters ?? {}).filter(([, value]) => value !== null && value !== undefined && value !== ''),
  ) as JobFilters;
}

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

function experienceText(j: JobRow) {
  if (j.experienceRaw) return j.experienceRaw;
  if (j.minYears === null) return '—';
  return j.maxYears === null ? `${j.minYears}+ yrs` : `${j.minYears}–${j.maxYears} yrs`;
}

export default function Jobs() {
  const client = useQueryClient();
  const [filters, setFilters] = useState<JobFilters>({ category: 'FRESHER' });
  const [page, setPage] = useState(0);
  const [sorting, setSorting] = useState<SortingState>([]);
  const [drawerId, setDrawerId] = useState<number | null>(null);
  const [askApplied, setAskApplied] = useState<number | null>(null);
  const [message, setMessage] = useState('');

  const [busyId, setBusyId] = useState<number | null>(null);
  const inFlight = useRef(new Set<number>());

  const update = (patchValue: Partial<JobFilters>) => { setFilters((f) => ({ ...f, ...patchValue })); setPage(0); };
  const sort = sorting[0] ? `${sorting[0].id},${sorting[0].desc ? 'desc' : 'asc'}` : undefined;
  // Typed filters are debounced so each keystroke does not send its own request.
  const q = useDebounced(filters.q, TYPING_DELAY_MS);
  const company = useDebounced(filters.company, TYPING_DELAY_MS);
  const location = useDebounced(filters.location, TYPING_DELAY_MS);
  const qs = queryString({ ...filters, q, company, location }, { page, size: 25, sort });

  const jobs = useQuery({
    queryKey: ['jobs', qs],
    queryFn: () => api<Page<JobRow>>(`/jobs?${qs}`),
    placeholderData: keepPreviousData,
  });
  const searches = useQuery({ queryKey: ['saved-searches'], queryFn: () => api<SavedSearch[]>('/saved-searches') });
  const settings = useQuery({ queryKey: ['settings'], queryFn: () => api<Settings>('/settings') });

  const setStatus = useMutation({
    mutationFn: ({ id, status }: { id: number; status: AppStatus }) => patch(`/jobs/${id}/status`, { status }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['jobs'] });
      client.invalidateQueries({ queryKey: ['tracker'] });
      client.invalidateQueries({ queryKey: ['stats'] });
    },
  });

  /** Ignores a second click on the same job while its first update is still running. */
  function changeStatus(id: number, status: AppStatus) {
    if (inFlight.current.has(id)) return;
    inFlight.current.add(id);
    setBusyId(id);
    setStatus.mutate({ id, status }, {
      onSettled: () => { inFlight.current.delete(id); setBusyId((b) => (b === id ? null : b)); },
    });
  }

  /** Opening a new job's details marks it as Seen. */
  function openDetails(j: JobRow) {
    setDrawerId(j.id);
    if (j.status === 'NEW') changeStatus(j.id, 'SEEN');
  }

  async function exportJobs(format: 'csv' | 'xlsx') {
    setMessage('');
    try {
      const headers = await download(`/jobs/export?${queryString(filters, { sort, format })}`, `jobradar-jobs.${format}`);
      const totalMatching = Number(headers.get('X-Total-Count'));
      const limit = Number(headers.get('X-Export-Limit'));
      if (limit && totalMatching > limit) {
        setMessage(`Exported the first ${limit.toLocaleString()} of ${totalMatching.toLocaleString()} matching jobs. `
          + 'Narrow the filters to export the rest.');
      }
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  const saveSearch = useMutation({
    mutationFn: (name: string) => post('/saved-searches', { name, filters, emailAlert: false }),
    onSuccess: () => { client.invalidateQueries({ queryKey: ['saved-searches'] }); setMessage('Search saved.'); },
  });
  const toggleAlert = useMutation({
    mutationFn: (s: SavedSearch) => put(`/saved-searches/${s.id}`, { ...s, emailAlert: !s.emailAlert }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['saved-searches'] }),
  });
  const removeSearch = useMutation({
    mutationFn: (id: number) => del(`/saved-searches/${id}`),
    onSuccess: () => client.invalidateQueries({ queryKey: ['saved-searches'] }),
  });

  function apply(url: string, id: number) {
    const safe = safeHttpUrl(url);
    if (!safe) {
      setMessage('This job has no usable application link. Open the company career page instead.');
      return;
    }
    window.open(safe, '_blank', 'noopener');
    setAskApplied(id);
  }

  const columns = useMemo(() => [
    helper.accessor('companyName', { id: 'company', header: 'Company' }),
    helper.accessor('title', {
      id: 'title', header: 'Job Title',
      cell: ({ row: { original: j } }) => (
        <span>
          <button className="break-words text-left font-medium text-indigo-700 hover:underline" onClick={() => openDetails(j)}>{j.title}</button>
          {j.isNew && <span className="ml-1 rounded bg-rose-100 px-1 text-[10px] font-semibold text-rose-700">NEW</span>}
          {j.status !== 'NEW' && <span className="ml-1 rounded bg-slate-100 px-1 text-[10px] text-slate-600">{j.status}</span>}
          {j.matchScore > 0 && <span className="ml-1 rounded bg-indigo-50 px-1 text-[10px] text-indigo-700">{j.matchScore}% match</span>}
        </span>
      ),
    }),
    helper.accessor('location', { id: 'location', header: 'Location', cell: (c) => c.getValue() ?? '—' }),
    helper.accessor('minYears', { id: 'experience', header: 'Experience', cell: ({ row }) => experienceText(row.original) }),
    helper.accessor('category', {
      id: 'category', header: 'Category',
      cell: (c) => <span className={`rounded px-1.5 py-0.5 text-xs ${categoryStyle[c.getValue()]}`}>{c.getValue().toLowerCase()}</span>,
    }),
    helper.accessor('postedAt', {
      id: 'postedAt', header: 'Posted',
      cell: (c) => (c.getValue() ? new Date(c.getValue()!).toLocaleDateString() : '—'),
    }),
    helper.accessor('source', { id: 'source', header: 'Source', cell: (c) => <span className="text-xs">{c.getValue()}</span> }),
    helper.display({
      id: 'actions', header: 'Actions', enableSorting: false,
      cell: ({ row: { original: j } }) => (
        <div className="flex gap-1">
          {safeHttpUrl(j.applyUrl) && <button className="btn btn-primary" onClick={() => apply(j.applyUrl!, j.id)}>Apply</button>}
          <button className="btn" disabled={busyId === j.id} onClick={() => changeStatus(j.id, 'SAVED')}>Save</button>
          <button className="btn" disabled={busyId === j.id} onClick={() => changeStatus(j.id, 'IGNORED')}>Ignore</button>
          <button className="btn" onClick={() => openDetails(j)}>Details</button>
        </div>
      ),
    }),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [busyId]);

  const table = useReactTable({
    data: jobs.data?.content ?? [],
    columns,
    state: { sorting },
    onSortingChange: (u) => { setSorting(u); setPage(0); },
    manualSorting: true,
    manualPagination: true,
    enableMultiSort: false,
    getCoreRowModel: getCoreRowModel(),
  });

  const total = jobs.data?.totalElements ?? 0;
  const totalPages = jobs.data?.totalPages ?? 0;
  const pending = (setStatus.error ?? saveSearch.error) as Error | null;

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Jobs</h1>
      <StatsCards />

      <div className="flex flex-wrap gap-1 border-b border-slate-200" role="tablist">
        {TABS.map((t) => (
          <button key={t.label} role="tab" aria-selected={filters.category === t.value}
            className={`-mb-px border-b-2 px-4 py-2 text-sm ${filters.category === t.value
              ? 'border-indigo-600 font-medium text-indigo-700' : 'border-transparent text-slate-600'}`}
            onClick={() => update({ category: t.value })}>{t.label}</button>
        ))}
      </div>

      <div className="card grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
        <input className="input" placeholder="Keyword in title or description" aria-label="Keyword"
          value={filters.q ?? ''} onChange={(e) => update({ q: e.target.value })} />
        <input className="input" placeholder="Company" aria-label="Company"
          value={filters.company ?? ''} onChange={(e) => update({ company: e.target.value })} />
        <input className="input" placeholder="City, e.g. Noida" aria-label="Location" list="city-list"
          value={filters.location ?? ''} onChange={(e) => update({ location: e.target.value })} />
        <datalist id="city-list">{CITIES.map((c) => <option key={c} value={c} />)}</datalist>
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" checked={!!filters.indiaOnly} onChange={(e) => update({ indiaOnly: e.target.checked })} />
          India only
        </label>
        <select className="input" aria-label="Work mode" value={filters.workMode ?? ''}
          onChange={(e) => update({ workMode: (e.target.value || undefined) as WorkMode | undefined })}>
          <option value="">Any work mode</option>
          <option value="REMOTE">Remote</option><option value="HYBRID">Hybrid</option><option value="ONSITE">Onsite</option>
        </select>
        <select className="input" aria-label="Role" value={filters.roleCategory ?? ''}
          onChange={(e) => update({ roleCategory: (e.target.value || undefined) as RoleCategory | undefined })}>
          <option value="">Any role</option>
          {Object.entries(ROLE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <select className="input" aria-label="Status" value={filters.status ?? ''}
          onChange={(e) => update({ status: (e.target.value || undefined) as AppStatus | undefined })}>
          <option value="">Any status</option>
          {STATUSES.map((s) => <option key={s} value={s}>{s[0] + s.slice(1).toLowerCase()}</option>)}
        </select>
        <div className="flex items-center gap-1">
          <input className="input" type="date" aria-label="Posted from" value={filters.from ?? ''}
            onChange={(e) => update({ from: e.target.value || undefined })} />
          <input className="input" type="date" aria-label="Posted to" value={filters.to ?? ''}
            onChange={(e) => update({ to: e.target.value || undefined })} />
        </div>
      </div>

      {settings.data && settings.data.preferredLocations.length > 0 && (
        <div className="flex flex-wrap items-center gap-1 text-xs text-slate-600">
          Preferred:
          {settings.data.preferredLocations.map((place) => (
            <button key={place} className="btn px-2 py-0.5 text-xs" onClick={() => update({ location: place })}>{place}</button>
          ))}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-auto text-sm text-slate-600" data-testid="job-total">{total.toLocaleString()} jobs</span>
        <select className="input w-auto" aria-label="Saved searches" value=""
          onChange={(e) => {
            const found = searches.data?.find((s) => s.id === Number(e.target.value));
            if (found) { setFilters(cleanFilters(found.filters)); setPage(0); }
          }}>
          <option value="">Saved searches…</option>
          {searches.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <button className="btn" onClick={() => {
          const name = window.prompt('Name this search');
          if (name?.trim()) saveSearch.mutate(name.trim());
        }}>Save search</button>
        <button className="btn" onClick={() => exportJobs('csv')}>Export CSV</button>
        <button className="btn" onClick={() => exportJobs('xlsx')}>Export Excel</button>
      </div>

      {searches.data && searches.data.length > 0 && (
        <div className="flex flex-wrap gap-2 text-xs">
          {searches.data.map((s) => (
            <span key={s.id} className="flex items-center gap-2 rounded border border-slate-200 bg-white px-2 py-1">
              {s.name}
              <label className="flex items-center gap-1 text-slate-600">
                <input type="checkbox" checked={s.emailAlert} onChange={() => toggleAlert.mutate(s)} /> email
              </label>
              <button className="text-red-700" aria-label={`Delete ${s.name}`} onClick={() => removeSearch.mutate(s.id)}>×</button>
            </span>
          ))}
        </div>
      )}

      {message && <p className="text-sm text-slate-800" role="status">{message}</p>}
      {pending && <p role="alert" className="text-sm text-red-700">{pending.message}</p>}
      {jobs.error && <p role="alert" className="text-sm text-red-700">{(jobs.error as Error).message}</p>}

      <div className="card overflow-x-auto p-0">
        <table className="w-full min-w-[960px] text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500">
            {table.getHeaderGroups().map((group) => (
              <tr key={group.id}>
                {group.headers.map((header) => (
                  <th key={header.id} className="p-2">
                    {header.column.getCanSort() ? (
                      <button className="uppercase" onClick={header.column.getToggleSortingHandler()}>
                        {flexRender(header.column.columnDef.header, header.getContext())}
                        {{ asc: ' ▲', desc: ' ▼' }[header.column.getIsSorted() as string] ?? ''}
                      </button>
                    ) : flexRender(header.column.columnDef.header, header.getContext())}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map((row) => (
              <tr key={row.id} className="border-t border-slate-100 align-top" data-testid="job-row">
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className="p-2">{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                ))}
              </tr>
            ))}
            {jobs.data?.content.length === 0 && (
              <tr><td colSpan={8} className="p-6 text-center text-slate-500">No jobs match these filters.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-center gap-3 text-sm">
        <button className="btn" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</button>
        <span className="text-slate-600">Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</span>
        <button className="btn" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>Next</button>
      </div>

      {drawerId !== null && <JobDrawer jobId={drawerId} onClose={() => setDrawerId(null)} onApply={apply} />}

      {askApplied !== null && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4">
          <div role="dialog" aria-label="Did you apply?" className="card w-full max-w-sm space-y-4">
            <p className="font-medium text-slate-900">Did you apply?</p>
            <div className="flex justify-end gap-2">
              <button className="btn" onClick={() => setAskApplied(null)}>Not yet</button>
              <button className="btn btn-primary" onClick={() => {
                setStatus.mutate({ id: askApplied, status: 'APPLIED' });
                setAskApplied(null);
              }}>Yes, mark applied</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
