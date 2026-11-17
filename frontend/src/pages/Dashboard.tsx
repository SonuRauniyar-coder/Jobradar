import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import ScanProgressBar from '../components/ScanProgressBar';
import StatsCards from '../components/StatsCards';
import { useScan } from '../hooks/useScan';
import type { JobRow, Page } from '../types/jobs';

export default function Dashboard() {
  const { progress } = useScan();
  const newest = useQuery({
    queryKey: ['jobs', 'dashboard-newest'],
    queryFn: () => api<Page<JobRow>>('/jobs?category=FRESHER&size=8&sort=firstSeenAt,desc'),
  });

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Dashboard</h1>
      <StatsCards />
      {progress && <ScanProgressBar progress={progress} />}
      <section className="card">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="font-medium text-slate-900">Newest fresher jobs</h2>
          <Link className="text-sm text-indigo-700 underline" to="/jobs">See all jobs</Link>
        </div>
        <ul className="divide-y divide-slate-100">
          {newest.data?.content.map((j) => (
            <li key={j.id} className="flex flex-wrap items-baseline justify-between gap-x-3 py-2 text-sm">
              <span className="font-medium text-slate-800">{j.title}</span>
              <span className="text-slate-500">{j.companyName}{j.location ? ` · ${j.location}` : ''}</span>
            </li>
          ))}
          {newest.data?.content.length === 0 && (
            <li className="py-4 text-sm text-slate-500">
              Nothing yet. <Link className="underline" to="/upload">Upload a company list</Link> and run a scan.
            </li>
          )}
        </ul>
      </section>
    </div>
  );
}
