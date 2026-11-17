import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, post } from '../api/client';
import type { ScanProgress } from '../types';

export interface ScanRequest {
  listId?: number;
  companyIds?: number[];
  failedOnly?: boolean;
}

/** Tracks the newest scan, polling every 2 seconds while it runs, and refreshes dependent data as it progresses. */
export function useScan() {
  const client = useQueryClient();

  const latest = useQuery({
    queryKey: ['scan', 'latest'],
    queryFn: async () => {
      const progress = await api<ScanProgress | undefined>('/scans/latest');
      if (progress) {
        client.invalidateQueries({ queryKey: ['companies'] });
        client.invalidateQueries({ queryKey: ['stats'] });
        client.invalidateQueries({ queryKey: ['jobs'] });
      }
      return progress ?? null;
    },
    refetchInterval: (query) => (query.state.data?.state === 'RUNNING' ? 2000 : false),
  });

  const start = useMutation({
    mutationFn: (request: ScanRequest) => post<ScanProgress>('/scans', request),
    onSuccess: (progress) => client.setQueryData(['scan', 'latest'], progress),
  });

  const progress = latest.data ?? null;
  return { progress, running: progress?.state === 'RUNNING', start };
}
