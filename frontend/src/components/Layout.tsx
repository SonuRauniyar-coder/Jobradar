import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { clearToken } from '../api/client';

const links = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/jobs', label: 'Jobs' },
  { to: '/companies', label: 'Companies' },
  { to: '/upload', label: 'Upload' },
  { to: '/tracker', label: 'Tracker' },
  { to: '/settings', label: 'Settings' },
];

export default function Layout() {
  const navigate = useNavigate();
  const client = useQueryClient();

  function signOut() {
    clearToken();
    // Drop every cached response so the next person to sign in on this tab never sees this account's data.
    client.cancelQueries();
    client.clear();
    navigate('/login');
  }

  return (
    <div className="min-h-screen bg-slate-100">
      <header className="sticky top-0 z-20 border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-4 gap-y-1 px-4 py-2">
          <span className="font-semibold text-indigo-700">JobRadar</span>
          {/* On narrow screens the links wrap onto their own row instead of scrolling out of sight. */}
          <nav aria-label="Main" className="order-last flex w-full flex-wrap gap-1 sm:order-none sm:w-auto sm:flex-1">
            {links.map((l) => (
              <NavLink key={l.to} to={l.to} end={l.end}
                className={({ isActive }) =>
                  `whitespace-nowrap rounded-md px-3 py-1.5 text-sm ${
                    isActive ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-slate-600 hover:bg-slate-100'
                  }`}>
                {l.label}
              </NavLink>
            ))}
          </nav>
          <button className="btn ml-auto sm:ml-0" onClick={signOut}>Sign out</button>
        </div>
      </header>
      <main className="mx-auto max-w-7xl p-4"><Outlet /></main>
    </div>
  );
}
