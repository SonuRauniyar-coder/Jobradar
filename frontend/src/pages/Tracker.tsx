import { useEffect, useState, type DragEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, patch, safeHttpUrl } from '../api/client';
import type { AppStatus, TrackerCard, TrackerStats } from '../types/jobs';

const COLUMNS: { status: AppStatus; label: string }[] = [
  { status: 'SAVED', label: 'Saved' },
  { status: 'APPLIED', label: 'Applied' },
  { status: 'INTERVIEW', label: 'Interview' },
  { status: 'OFFER', label: 'Offer' },
  { status: 'REJECTED', label: 'Rejected' },
];

/** Only drags that start on a tracker card carry this type, so other dropped content is ignored. */
const CARD_DRAG_TYPE = 'application/x-jobradar-job';

interface Update {
  id: number; status?: AppStatus; notes?: string; expectedNotes?: string; followUpDate?: string; clearFollowUp?: boolean;
}

function Card({ card, onUpdate }: { card: TrackerCard; onUpdate: (u: Update) => void }) {
  const saved = card.notes ?? '';
  const [notes, setNotes] = useState(saved);
  const [editing, setEditing] = useState(false);
  // Show the latest saved notes (from a refetch) unless the user is typing in this box.
  useEffect(() => { if (!editing) setNotes(saved); }, [saved, editing]);
  const href = safeHttpUrl(card.applyUrl);
  return (
    <article draggable data-testid="tracker-card"
      onDragStart={(e) => {
        e.dataTransfer.setData(CARD_DRAG_TYPE, String(card.jobId));
        e.dataTransfer.setData('text/plain', card.title);
      }}
      className="space-y-2 rounded border border-slate-200 bg-white p-2 text-sm shadow-sm">
      <div>
        {href
          ? <a className="break-words font-medium text-indigo-700 hover:underline" href={href} target="_blank" rel="noreferrer">{card.title}</a>
          : <span className="break-words font-medium text-slate-900">{card.title}</span>}
        <p className="text-xs text-slate-500">{card.companyName}{card.location ? ` · ${card.location}` : ''}</p>
        {!card.open && <p className="text-xs text-amber-700">No longer listed</p>}
        {card.appliedAt && <p className="text-xs text-slate-500">Applied {new Date(card.appliedAt).toLocaleDateString()}</p>}
      </div>
      <select className="input" aria-label={`Move ${card.title}`} value={card.status}
        onChange={(e) => onUpdate({ id: card.jobId, status: e.target.value as AppStatus })}>
        {COLUMNS.map((c) => <option key={c.status} value={c.status}>{c.label}</option>)}
      </select>
      <textarea className="input" rows={2} placeholder="Notes" aria-label={`Notes for ${card.title}`} value={notes}
        onFocus={() => setEditing(true)}
        onChange={(e) => setNotes(e.target.value)}
        onBlur={() => {
          setEditing(false);
          // expectedNotes makes the server refuse the save if another tab changed the notes meanwhile.
          if (notes !== saved) onUpdate({ id: card.jobId, notes, expectedNotes: saved });
        }} />
      <label className="block text-xs text-slate-600">Follow up
        <input className="input mt-1" type="date" value={card.followUpDate ?? ''}
          onChange={(e) => onUpdate(e.target.value
            ? { id: card.jobId, followUpDate: e.target.value }
            : { id: card.jobId, clearFollowUp: true })} />
      </label>
    </article>
  );
}

export default function Tracker() {
  const client = useQueryClient();
  const board = useQuery({
    queryKey: ['tracker', 'board'],
    queryFn: () => api<TrackerCard[]>('/tracker/board'),
    refetchOnWindowFocus: true, // picks up changes made in another tab
  });
  const stats = useQuery({ queryKey: ['tracker', 'stats'], queryFn: () => api<TrackerStats>('/tracker/stats') });

  const update = useMutation({
    mutationFn: ({ id, ...body }: Update) => patch(`/jobs/${id}/status`, body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['jobs'] });
    },
    // Refetch after errors too, so a rejected notes edit is replaced by what is actually saved.
    onSettled: () => client.invalidateQueries({ queryKey: ['tracker'] }),
  });

  function onDrop(e: DragEvent, status: AppStatus) {
    e.preventDefault();
    const id = Number(e.dataTransfer.getData(CARD_DRAG_TYPE));
    if (!Number.isInteger(id) || id <= 0) return; // not one of our cards
    update.mutate({ id, status });
  }

  const figures: [string, string | number | undefined][] = [
    ['Applied this week', stats.data?.appliedThisWeek],
    ['Total applied', stats.data?.totalApplied],
    ['Response rate', stats.data ? `${stats.data.responseRatePercent}%` : undefined],
  ];

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Application tracker</h1>
      <div className="grid grid-cols-3 gap-3">
        {figures.map(([label, value]) => (
          <div key={label} className="card p-3">
            <div className="text-2xl font-semibold text-slate-900">{value ?? '–'}</div>
            <div className="text-xs text-slate-500">{label}</div>
          </div>
        ))}
      </div>
      {update.error && <p role="alert" className="text-sm text-red-700">{(update.error as Error).message}</p>}
      <div className="grid gap-3 md:grid-cols-5">
        {COLUMNS.map((column) => {
          const cards = board.data?.filter((c) => c.status === column.status) ?? [];
          return (
            <section key={column.status} aria-label={column.label} data-testid={`column-${column.status}`}
              className="min-h-24 space-y-2 rounded-lg bg-slate-200/60 p-2"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => onDrop(e, column.status)}>
              <h2 className="text-sm font-medium text-slate-700">{column.label} ({cards.length})</h2>
              {cards.map((card) => <Card key={card.jobId} card={card} onUpdate={(u) => update.mutate(u)} />)}
            </section>
          );
        })}
      </div>
    </div>
  );
}
