// A pretend company career site, so E2E tests never depend on the live internet.
import { createServer } from 'node:http';

const jobs = {
  1: { title: 'Software Engineering Intern', city: 'Noida', text: 'Interns welcome. Learn Java.' },
  2: { title: 'Senior Platform Engineer', city: 'Bangalore', text: 'Requires 8+ years of experience.' },
  3: { title: 'QA Engineer', city: 'Pune', text: 'Requires 3+ years of experience in testing.' },
};

const page = (body) => `<!doctype html><html><body>${body}</body></html>`;

createServer((req, res) => {
  const match = /^\/careers\/open\/(\d+)$/.exec(req.url);
  if (req.url === '/careers') {
    const links = Object.entries(jobs).map(([id, j]) => `<a href="/careers/open/${id}">${j.title}</a>`).join('');
    res.writeHead(200, { 'content-type': 'text/html' }).end(page(`<h1>Careers at Fixture Corp</h1>${links}`));
  } else if (req.url === '/js-careers') {
    // Nothing useful until JavaScript runs, like a single-page career site.
    const script = `const s=document.createElement('script');s.type='application/ld+json';
      s.textContent=JSON.stringify({'@type':'JobPosting',title:'Rendered Graduate Trainee',url:'http://127.0.0.1:4599/js/1',
      jobLocation:{address:{addressLocality:'Gurugram',addressCountry:'India'}}});document.body.appendChild(s);`;
    res.writeHead(200, { 'content-type': 'text/html' }).end(page(`<div id="root"></div><script>${script}</script>`));
  } else if (match && jobs[match[1]]) {
    const job = jobs[match[1]];
    const posting = {
      '@type': 'JobPosting', title: job.title, description: `<p>${job.text}</p>`, identifier: { value: `FX-${match[1]}` },
      url: `http://127.0.0.1:4599/careers/open/${match[1]}`, datePosted: '2026-10-01',
      jobLocation: { address: { addressLocality: job.city, addressCountry: 'India' } },
    };
    res.writeHead(200, { 'content-type': 'text/html' })
      .end(page(`<script type="application/ld+json">${JSON.stringify(posting)}</script><h1>${job.title}</h1>`));
  } else {
    res.writeHead(404).end('not found');
  }
}).listen(4599);
