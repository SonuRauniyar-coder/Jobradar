export type SourceType =
  | 'GREENHOUSE' | 'LEVER' | 'ASHBY' | 'SMARTRECRUITERS' | 'WORKABLE' | 'WORKDAY' | 'GENERIC_HTML' | 'UNKNOWN';

export const SOURCE_TYPES: SourceType[] = [
  'GREENHOUSE', 'LEVER', 'ASHBY', 'SMARTRECRUITERS', 'WORKABLE', 'WORKDAY', 'GENERIC_HTML',
];

export interface CompanyList {
  id: number;
  name: string;
  createdAt: string;
  companyCount: number;
}

export interface Company {
  id: number;
  listId: number;
  name: string;
  website: string | null;
  careerUrl: string | null;
  locationPreference: string | null;
  sourceType: SourceType;
  sourceLocked: boolean;
  atsSlug: string | null;
  lastScannedAt: string | null;
  lastStatus: 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'BLOCKED' | null;
  lastError: string | null;
  jobsCount: number;
}

export interface UploadRow {
  row: number;
  name: string | null;
  careerUrl: string | null;
  website: string | null;
  locationPreference: string | null;
  status: 'VALID' | 'ERROR' | 'DUPLICATE';
  message: string | null;
}

export interface UploadResult {
  rows: UploadRow[];
  valid: number;
  errors: number;
  duplicates: number;
  saved: number;
  committed: boolean;
}

export interface ScanProgress {
  id: number;
  state: 'RUNNING' | 'FINISHED' | 'INTERRUPTED';
  total: number;
  done: number;
  success: number;
  failed: number;
  currentCompany: string | null;
  startedAt: string;
  finishedAt: string | null;
  triggeredBy: string;
}
