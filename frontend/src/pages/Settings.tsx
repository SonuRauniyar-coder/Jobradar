import { FormEvent, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, put } from '../api/client';
import type { Settings as SettingsData } from '../types/jobs';

const toList = (text: string) => text.split(',').map((v) => v.trim()).filter(Boolean);

export default function Settings() {
  const client = useQueryClient();
  const { data } = useQuery({ queryKey: ['settings'], queryFn: () => api<SettingsData>('/settings') });
  const [keywords, setKeywords] = useState('');
  const [locations, setLocations] = useState('');
  const [concurrency, setConcurrency] = useState(10);
  const [rate, setRate] = useState(1);

  useEffect(() => {
    if (data) {
      setKeywords(data.profileKeywords.join(', '));
      setLocations(data.preferredLocations.join(', '));
      setConcurrency(data.scanConcurrency);
      setRate(data.rateLimitPerSec);
    }
  }, [data]);

  const save = useMutation({
    mutationFn: () => put<SettingsData>('/settings', {
      profileKeywords: toList(keywords), preferredLocations: toList(locations),
      scanConcurrency: concurrency, rateLimitPerSec: rate,
    }),
    onSuccess: (saved) => {
      client.setQueryData(['settings'], saved);
      client.invalidateQueries({ queryKey: ['jobs'] });
    },
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate();
  }

  return (
    <form onSubmit={submit} className="card max-w-xl space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Settings</h1>
      <label className="block text-sm text-slate-700">
        Profile keywords (comma separated; used for the match score)
        <textarea className="input mt-1" rows={2} placeholder="Java, Spring Boot, React, Playwright, QA" value={keywords}
          onChange={(e) => { setKeywords(e.target.value); save.reset(); }} />
      </label>
      <label className="block text-sm text-slate-700">
        Preferred locations (comma separated)
        <textarea className="input mt-1" rows={2} placeholder="Gurugram, Noida, Bangalore" value={locations}
          onChange={(e) => { setLocations(e.target.value); save.reset(); }} />
      </label>
      <div className="grid grid-cols-2 gap-3">
        <label className="block text-sm text-slate-700">
          Scan concurrency (1–50)
          <input className="input mt-1" type="number" min={1} max={50} required value={concurrency}
            onChange={(e) => { setConcurrency(Number(e.target.value)); save.reset(); }} />
        </label>
        <label className="block text-sm text-slate-700">
          Requests per second, per site (0.1–5)
          <input className="input mt-1" type="number" min={0.1} max={5} step="any" required value={rate}
            onChange={(e) => { setRate(Number(e.target.value)); save.reset(); }} />
        </label>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-700">{(save.error as Error).message}</p>}
      {save.isSuccess && <p className="text-sm text-green-800">Saved.</p>}
      <button className="btn btn-primary" disabled={save.isPending}>Save settings</button>
    </form>
  );
}
