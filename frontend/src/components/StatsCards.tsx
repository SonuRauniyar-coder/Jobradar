import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { DashboardStats } from '../types/jobs';

export default function StatsCards() {
  const { data } = useQuery({ queryKey: ['stats'], queryFn: () => api<DashboardStats>('/stats/dashboard') });
  const cards: [string, number | undefined][] = [
    ['Total companies', data?.totalCompanies],
    ['Companies scanned', data?.companiesScanned],
    ['Open jobs', data?.openJobs],
    ['Fresher jobs', data?.fresherJobs],
    ['Experienced jobs', data?.experiencedJobs],
    ['New in last 24h', data?.newJobs24h],
    ['Failed companies', data?.failedCompanies],
  ];
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
      {cards.map(([label, value]) => (
        <div key={label} className="card p-3">
          <div className="text-2xl font-semibold text-slate-900">{value?.toLocaleString() ?? '–'}</div>
          <div className="text-xs text-slate-500">{label}</div>
        </div>
      ))}
    </div>
  );
}
