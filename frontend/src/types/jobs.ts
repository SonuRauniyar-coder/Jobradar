import type { SourceType } from './index';

export type Category = 'FRESHER' | 'EXPERIENCED' | 'UNSPECIFIED';
export type WorkMode = 'REMOTE' | 'HYBRID' | 'ONSITE' | 'UNKNOWN';
export type RoleCategory = 'BACKEND' | 'FRONTEND' | 'FULL_STACK' | 'QA_TESTING' | 'DEVOPS' | 'NETWORK' | 'DATA' | 'OTHER';
export type AppStatus = 'NEW' | 'SEEN' | 'SAVED' | 'APPLIED' | 'INTERVIEW' | 'OFFER' | 'REJECTED' | 'IGNORED';

export const ROLE_LABELS: Record<RoleCategory, string> = {
  BACKEND: 'Backend', FRONTEND: 'Frontend', FULL_STACK: 'Full Stack', QA_TESTING: 'QA/Testing',
  DEVOPS: 'DevOps', NETWORK: 'Network', DATA: 'Data', OTHER: 'Other',
};

export interface JobRow {
  id: number;
  companyId: number;
  companyName: string;
  title: string;
  location: string | null;
  workMode: WorkMode;
  employmentType: string | null;
  experienceRaw: string | null;
  minYears: number | null;
  maxYears: number | null;
  category: Category;
  categoryOverridden: boolean;
  roleCategory: RoleCategory;
  postedAt: string | null;
  firstSeenAt: string;
  isNew: boolean;
  source: SourceType;
  applyUrl: string | null;
  open: boolean;
  status: AppStatus;
  appliedAt: string | null;
  matchScore: number;
}

export interface JobDetail {
  job: JobRow;
  department: string | null;
  description: string | null;
  skills: string[];
  classificationReason: string | null;
  sourceUrl: string | null;
  matchedKeywords: string[];
  notes: string | null;
  followUpDate: string | null;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface JobFilters {
  category?: Category;
  location?: string;
  indiaOnly?: boolean;
  q?: string;
  company?: string;
  workMode?: WorkMode;
  roleCategory?: RoleCategory;
  status?: AppStatus;
  from?: string;
  to?: string;
}

export interface DashboardStats {
  totalCompanies: number;
  companiesScanned: number;
  openJobs: number;
  fresherJobs: number;
  experiencedJobs: number;
  newJobs24h: number;
  failedCompanies: number;
}

export interface SavedSearch {
  id: number;
  name: string;
  filters: JobFilters;
  emailAlert: boolean;
}

export interface TrackerCard {
  jobId: number;
  title: string;
  companyName: string;
  location: string | null;
  applyUrl: string | null;
  open: boolean;
  status: AppStatus;
  appliedAt: string | null;
  notes: string | null;
  followUpDate: string | null;
}

export interface TrackerStats {
  appliedThisWeek: number;
  totalApplied: number;
  responseRatePercent: number;
}

export interface Settings {
  profileKeywords: string[];
  preferredLocations: string[];
  scanConcurrency: number;
  rateLimitPerSec: number;
}
