import { FormEvent, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, del, post, put } from '../api/client';
import ScanProgressBar from '../components/ScanProgressBar';
import { useScan } from '../hooks/useScan';
import { SOURCE_TYPES } from '../types';
import type { Company, CompanyList, SourceType } from '../types';

interface FormState {
  id?: number;
  listId: number | '';
  name: string;
  website: string;
  careerUrl: string;
  locationPreference: string;
  sourceType: SourceType;
  atsSlug: string;
  /** False while the user hasn't touched the source fields, so saving keeps an auto-detected source. */
  sourceChanged: boolean;
}

const statusStyle: Record<string, string> = {
  SUCCESS: 'bg-green-100 text-green-800',
  PARTIAL: 'bg-amber-100 text-amber-800',
  FAILED: 'bg-red-100 text-red-800',
  BLOCKED: 'bg-slate-200 text-slate-800',
};

export default function Companies() {
  const client = useQueryClient();
  const [listFilter, setListFilter] = useState<number | ''>('');
  const [form, setForm] = useState<FormState | null>(null);
  const lists = useQuery({ queryKey: ['lists'], queryFn: () => api<CompanyList[]>('/lists') });
  const companies = useQuery({
    queryKey: ['companies', listFilter],
    queryFn: () => api<Company[]>(`/companies${listFilter ? `?listId=${listFilter}` : ''}`),
  });
  const { progress, running, start } = useScan();

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['companies'] });
    client.invalidateQueries({ queryKey: ['lists'] });
  };

  const save = useMutation({
    mutationFn: (f: FormState) => {
      const keepSource = f.id && !f.sourceChanged;
      const body = {
        listId: f.listId || null, name: f.name, website: f.website, careerUrl: f.careerUrl,
        locationPreference: f.locationPreference,
        // null tells the server to leave the source as it is.
        sourceType: keepSource ? null : f.sourceType, atsSlug: keepSource ? null : f.atsSlug,
      };
      return f.id ? put<Company>(`/companies/${f.id}`, body) : post<Company>('/companies', body);
    },
    onSuccess: () => { setForm(null); refresh(); },
  });

  const remove = useMutation({ mutationFn: (id: number) => del(`/companies/${id}`), onSuccess: refresh });
  const removeList = useMutation({
    mutationFn: (id: number) => del(`/lists/${id}`),
    onSuccess: () => {
      setListFilter('');
      refresh();
      client.invalidateQueries({ queryKey: ['jobs'] });
      client.invalidateQueries({ queryKey: ['stats'] });
    },
  });
  const selectedList = lists.data?.find((l) => l.id === listFilter);

  const scope = listFilter ? { listId: listFilter } : {};
  const error = (start.error ?? remove.error ?? removeList.error) as Error | null;

  function submit(e: FormEvent) {
    e.preventDefault();
    if (form) save.mutate(form);
  }

  function edit(c: Company) {
    setForm({
      id: c.id, listId: c.listId, name: c.name, website: c.website ?? '', careerUrl: c.careerUrl ?? '',
      locationPreference: c.locationPreference ?? '', sourceType: c.sourceLocked ? c.sourceType : 'UNKNOWN',
      atsSlug: c.atsSlug ?? '', sourceChanged: false,
    });
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto text-xl font-semibold text-slate-900">Companies</h1>
        <select className="input w-auto" aria-label="Filter by list" value={listFilter}
          onChange={(e) => setListFilter(e.target.value ? Number(e.target.value) : '')}>
          <option value="">All lists</option>
          {lists.data?.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
        </select>
        {selectedList && (
          <button className="btn btn-danger" disabled={removeList.isPending} onClick={() => {
            if (window.confirm(`Delete the list "${selectedList.name}", its ${selectedList.companyCount} companies and their jobs?`)) {
              removeList.mutate(selectedList.id);
            }
          }}>Delete list</button>
        )}
        <button className="btn" onClick={() => setForm({
          listId: listFilter, name: '', website: '', careerUrl: '', locationPreference: '', sourceType: 'UNKNOWN', atsSlug: '',
          sourceChanged: true,
        })}>Add company</button>
        <button className="btn" disabled={running || start.isPending}
          onClick={() => start.mutate({ ...scope, failedOnly: true })}>Rescan failed only</button>
        <button className="btn btn-primary" disabled={running || start.isPending}
          onClick={() => start.mutate(scope)}>Scan all</button>
      </div>

      {progress && <ScanProgressBar progress={progress} />}
      {error && <p role="alert" className="text-sm text-red-700">{error.message}</p>}

      {form && (
        <form onSubmit={submit} className="card grid gap-3 md:grid-cols-3" aria-label="Company form">
          {!form.id && (
            <label className="text-sm text-slate-700">List
              <select className="input mt-1" required value={form.listId}
                onChange={(e) => setForm({ ...form, listId: e.target.value ? Number(e.target.value) : '' })}>
                <option value="">Choose a list…</option>
                {lists.data?.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </select>
            </label>
          )}
          <label className="text-sm text-slate-700">Name
            <input className="input mt-1" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </label>
          <label className="text-sm text-slate-700">Website
            <input className="input mt-1" value={form.website} onChange={(e) => setForm({ ...form, website: e.target.value })} />
          </label>
          <label className="text-sm text-slate-700">Career page URL
            <input className="input mt-1" value={form.careerUrl} onChange={(e) => setForm({ ...form, careerUrl: e.target.value })} />
          </label>
          <label className="text-sm text-slate-700">Location preference
            <input className="input mt-1" value={form.locationPreference}
              onChange={(e) => setForm({ ...form, locationPreference: e.target.value })} />
          </label>
          <label className="text-sm text-slate-700">Source type
            <select className="input mt-1" value={form.sourceType}
              onChange={(e) => setForm({ ...form, sourceType: e.target.value as SourceType, sourceChanged: true })}>
              <option value="UNKNOWN">Auto-detect</option>
              {SOURCE_TYPES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
          <label className="text-sm text-slate-700">ATS slug
            <input className="input mt-1" placeholder="e.g. stripe, or tenant.wd5/Site for Workday" value={form.atsSlug}
              onChange={(e) => setForm({ ...form, atsSlug: e.target.value, sourceChanged: true })} />
          </label>
          {save.error && <p role="alert" className="text-sm text-red-700 md:col-span-3">{(save.error as Error).message}</p>}
          <div className="flex gap-2 md:col-span-3">
            <button className="btn btn-primary" disabled={save.isPending}>Save</button>
            <button type="button" className="btn" onClick={() => setForm(null)}>Cancel</button>
          </div>
        </form>
      )}

      <div className="card overflow-x-auto p-0">
        <table className="w-full min-w-[820px] text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="p-2">Company</th><th className="p-2">Career URL</th><th className="p-2">Source</th>
              <th className="p-2">Last scanned</th><th className="p-2">Status</th><th className="p-2">Jobs</th>
              <th className="p-2">Error</th><th className="p-2">Actions</th>
            </tr>
          </thead>
          <tbody>
            {companies.data?.map((c) => (
              <tr key={c.id} className="border-t border-slate-100 align-top">
                <td className="p-2 font-medium text-slate-900">{c.name}</td>
                <td className="max-w-[200px] truncate p-2">
                  {c.careerUrl ? <a className="text-indigo-700 underline" href={c.careerUrl} target="_blank" rel="noreferrer">{c.careerUrl}</a> : '—'}
                </td>
                <td className="p-2 text-xs">{c.sourceType}{c.sourceLocked ? ' (manual)' : ''}</td>
                <td className="whitespace-nowrap p-2 text-xs text-slate-600">
                  {c.lastScannedAt ? new Date(c.lastScannedAt).toLocaleString() : 'Never'}
                </td>
                <td className="p-2">
                  {c.lastStatus && <span className={`rounded px-1.5 py-0.5 text-xs ${statusStyle[c.lastStatus]}`}>{c.lastStatus}</span>}
                </td>
                <td className="p-2">{c.jobsCount}</td>
                <td className="max-w-[240px] p-2 text-xs text-slate-600">{c.lastError}</td>
                <td className="whitespace-nowrap p-2">
                  <div className="flex gap-1">
                    <button className="btn" disabled={running} onClick={() => start.mutate({ companyIds: [c.id] })}>Scan now</button>
                    <button className="btn" onClick={() => edit(c)}>Edit</button>
                    <button className="btn btn-danger" onClick={() => {
                      if (window.confirm(`Delete ${c.name} and its jobs?`)) remove.mutate(c.id);
                    }}>Delete</button>
                  </div>
                </td>
              </tr>
            ))}
            {companies.data?.length === 0 && (
              <tr><td colSpan={8} className="p-6 text-center text-slate-500">No companies yet. Upload a list to get started.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
