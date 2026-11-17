import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, patch, safeHttpUrl } from '../api/client';
import { ROLE_LABELS } from '../types/jobs';
import type { Category, JobDetail } from '../types/jobs';

const FOCUSABLE = 'a[href], button:not([disabled]), select:not([disabled]), input:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export default function JobDrawer({ jobId, onClose, onApply }: {
  jobId: number;
  onClose: () => void;
  onApply: (url: string, jobId: number) => void;
}) {
  const client = useQueryClient();
  const panel = useRef<HTMLElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const { data, isLoading, error } = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => api<JobDetail>(`/jobs/${jobId}`),
  });

  const setCategory = useMutation({
    mutationFn: (category: Category) => patch<JobDetail>(`/jobs/${jobId}/category`, { category }),
    onSuccess: (detail) => {
      client.setQueryData(['job', jobId], detail);
      client.invalidateQueries({ queryKey: ['jobs'] });
      client.invalidateQueries({ queryKey: ['stats'] });
    },
  });

  // Modal behaviour: focus moves into the drawer, Esc closes it, Tab stays inside it,
  // and focus returns to the control that opened it.
  const latestOnClose = useRef(onClose);
  latestOnClose.current = onClose;
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeButton.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        latestOnClose.current();
        return;
      }
      if (e.key !== 'Tab' || !panel.current) return;
      const items = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (e.shiftKey && (active === first || !panel.current.contains(active))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (active === last || !panel.current.contains(active))) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      opener?.focus?.();
    };
  }, []);

  const job = data?.job;
  const applyUrl = safeHttpUrl(job?.applyUrl);
  return (
    <div className="fixed inset-0 z-30 flex justify-end bg-black/30" onClick={onClose}>
      <aside ref={panel} role="dialog" aria-modal="true" aria-labelledby="job-drawer-title" onClick={(e) => e.stopPropagation()}
        className="h-full w-full max-w-xl overflow-y-auto overflow-x-hidden bg-white p-5 shadow-xl">
        <button ref={closeButton} className="btn float-right" onClick={onClose}>Close</button>
        {isLoading && <p id="job-drawer-title" className="text-slate-500">Loading…</p>}
        {error && <p role="alert" className="text-red-700">{(error as Error).message}</p>}
        {data && job && (
          <div className="space-y-4">
            <div>
              <h2 id="job-drawer-title" className="break-words pr-16 text-lg font-semibold text-slate-900 [overflow-wrap:anywhere]">{job.title}</h2>
              <p className="break-words text-sm text-slate-600 [overflow-wrap:anywhere]">
                {job.companyName}{job.location ? ` · ${job.location}` : ''}{data.department ? ` · ${data.department}` : ''}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                {ROLE_LABELS[job.roleCategory]} · {job.workMode.toLowerCase()}
                {job.employmentType ? ` · ${job.employmentType}` : ''}
                {job.postedAt ? ` · Posted ${new Date(job.postedAt).toLocaleDateString()}` : ''}
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              {applyUrl && <button className="btn btn-primary" onClick={() => onApply(applyUrl, job.id)}>Apply</button>}
              <label className="flex items-center gap-2 text-sm text-slate-700">
                Category
                <select className="input w-auto" value={job.category} aria-label="Category"
                  onChange={(e) => setCategory.mutate(e.target.value as Category)}>
                  <option value="FRESHER">Fresher</option>
                  <option value="EXPERIENCED">Experienced</option>
                  <option value="UNSPECIFIED">Unspecified</option>
                </select>
              </label>
            </div>

            <section className="rounded bg-slate-50 p-3 text-sm">
              <h3 className="font-medium text-slate-800">Why this category</h3>
              <p className="text-slate-600" data-testid="classification-reason">{data.classificationReason ?? '—'}</p>
              {job.experienceRaw && <p className="mt-1 text-slate-600">Experience: {job.experienceRaw}</p>}
            </section>

            {data.skills.length > 0 && (
              <section>
                <h3 className="mb-1 text-sm font-medium text-slate-800">Skills mentioned</h3>
                <div className="flex flex-wrap gap-1">
                  {data.skills.map((s) => (
                    <span key={s} className={`rounded px-1.5 py-0.5 text-xs ${
                      data.matchedKeywords.includes(s) ? 'bg-indigo-100 text-indigo-800' : 'bg-slate-100 text-slate-700'}`}>{s}</span>
                  ))}
                </div>
              </section>
            )}

            <section>
              <h3 className="mb-1 text-sm font-medium text-slate-800">Description</h3>
              <p className="whitespace-pre-wrap break-words text-sm text-slate-700 [overflow-wrap:anywhere]">{data.description ?? 'No description available.'}</p>
            </section>
          </div>
        )}
      </aside>
    </div>
  );
}
