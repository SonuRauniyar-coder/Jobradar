import type { ScanProgress } from '../types';

export default function ScanProgressBar({ progress }: { progress: ScanProgress }) {
  const percent = progress.total === 0 ? 0 : Math.round((progress.done / progress.total) * 100);
  const running = progress.state === 'RUNNING';
  return (
    <div className="card" data-testid="scan-progress">
      <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
        <span className="font-medium text-slate-900">
          {running ? 'Scanning' : progress.state === 'INTERRUPTED' ? 'Scan interrupted' : 'Last scan finished'}:{' '}
          {progress.done} of {progress.total} done
        </span>
        <span className="text-slate-600">
          <span className="text-green-700">{progress.success} succeeded</span> ·{' '}
          <span className="text-red-700">{progress.failed} failed</span>
        </span>
      </div>
      <div className="mt-2 h-2 overflow-hidden rounded bg-slate-200" role="progressbar"
        aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
        <div className="h-full bg-indigo-600 transition-all" style={{ width: `${percent}%` }} />
      </div>
      {running && progress.currentCompany && (
        <p className="mt-2 truncate text-xs text-slate-500">Current: {progress.currentCompany}</p>
      )}
    </div>
  );
}
