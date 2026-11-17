import { FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, download, post } from '../api/client';
import type { CompanyList, UploadResult, UploadRow } from '../types';

const statusStyle: Record<UploadRow['status'], string> = {
  VALID: 'bg-green-100 text-green-800',
  ERROR: 'bg-red-100 text-red-800',
  DUPLICATE: 'bg-amber-100 text-amber-800',
};

export default function Upload() {
  const client = useQueryClient();
  const lists = useQuery({ queryKey: ['lists'], queryFn: () => api<CompanyList[]>('/lists') });
  const [listId, setListId] = useState<number | ''>('');
  const [newName, setNewName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<UploadResult | null>(null);

  const createList = useMutation({
    mutationFn: () => post<CompanyList>('/lists', { name: newName }),
    onSuccess: (list) => {
      client.invalidateQueries({ queryKey: ['lists'] });
      setListId(list.id);
      setNewName('');
    },
  });

  const upload = useMutation({
    mutationFn: (commit: boolean) => {
      const form = new FormData();
      form.append('file', file!);
      return api<UploadResult>(`/lists/${listId}/upload?commit=${commit}`, { method: 'POST', body: form });
    },
    onSuccess: (data) => {
      setResult(data);
      if (data.committed) {
        client.invalidateQueries({ queryKey: ['lists'] });
        client.invalidateQueries({ queryKey: ['companies'] });
      }
    },
  });

  function submitNewList(e: FormEvent) {
    e.preventDefault();
    createList.mutate();
  }

  const error = (createList.error ?? upload.error) as Error | null;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-semibold text-slate-900">Upload companies</h1>
        <button className="btn" onClick={() => download('/lists/template', 'jobradar-companies-template.csv')}>
          Download sample template
        </button>
      </div>

      <div className="card grid gap-4 md:grid-cols-2">
        <div className="space-y-3">
          <label className="block text-sm text-slate-700">
            Company list
            <select className="input mt-1" aria-label="Company list" value={listId}
              onChange={(e) => { setListId(e.target.value ? Number(e.target.value) : ''); setResult(null); }}>
              <option value="">Choose a list…</option>
              {lists.data?.map((l) => <option key={l.id} value={l.id}>{l.name} ({l.companyCount})</option>)}
            </select>
          </label>
          <form onSubmit={submitNewList} className="flex gap-2">
            <input className="input" placeholder="New list name, e.g. Product Companies" aria-label="New list name"
              value={newName} onChange={(e) => setNewName(e.target.value)} required />
            <button className="btn whitespace-nowrap" disabled={createList.isPending}>Create list</button>
          </form>
        </div>
        <div className="space-y-3">
          <label className="block text-sm text-slate-700">
            CSV or XLSX file (up to 500 companies per list)
            <input className="input mt-1" type="file" accept=".csv,.xlsx" aria-label="Company file"
              onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
          </label>
          <button className="btn btn-primary" disabled={!file || !listId || upload.isPending}
            onClick={() => upload.mutate(false)}>
            {upload.isPending ? 'Working…' : 'Validate and preview'}
          </button>
        </div>
      </div>

      {error && <p role="alert" className="text-sm text-red-700">{error.message}</p>}

      {result && (
        <div className="card space-y-3">
          {result.committed ? (
            <p className="text-sm text-green-800" data-testid="upload-saved">
              Saved {result.saved} {result.saved === 1 ? 'company' : 'companies'}. <Link className="underline" to="/companies">Go to Companies to scan them</Link>.
            </p>
          ) : (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm text-slate-700">
                {result.valid} valid · {result.errors} with errors · {result.duplicates} duplicates. Nothing is saved yet.
              </p>
              <button className="btn btn-primary" disabled={result.valid === 0 || upload.isPending}
                onClick={() => upload.mutate(true)}>
                Save {result.valid} valid {result.valid === 1 ? 'company' : 'companies'}
              </button>
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-slate-500">
                <tr><th className="p-2">Row</th><th className="p-2">Company</th><th className="p-2">Website</th>
                  <th className="p-2">Career URL</th><th className="p-2">Result</th></tr>
              </thead>
              <tbody>
                {result.rows.map((r) => (
                  <tr key={r.row} className="border-t border-slate-100">
                    <td className="p-2 text-slate-500">{r.row}</td>
                    <td className="p-2">{r.name ?? '—'}</td>
                    <td className="p-2 break-all">{r.website ?? '—'}</td>
                    <td className="p-2 break-all">{r.careerUrl ?? '—'}</td>
                    <td className="p-2">
                      <span className={`rounded px-1.5 py-0.5 text-xs ${statusStyle[r.status]}`}>{r.status}</span>
                      {r.message && <span className="ml-2 text-xs text-slate-600">{r.message}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
