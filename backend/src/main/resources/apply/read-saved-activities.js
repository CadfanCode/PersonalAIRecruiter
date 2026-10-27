// Fixed, read-only script: the activities already saved on the aktivitetsrapport page, for the duplicate check.
() => {
  const text = (el) => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
  const months = { januari: 1, februari: 2, mars: 3, april: 4, maj: 5, juni: 6, juli: 7, augusti: 8, september: 9, oktober: 10, november: 11, december: 12 };
  const pad = (n) => String(n).padStart(2, '0');
  const dateOf = (t) => {
    const iso = t.match(/\d{4}-\d{2}-\d{2}/);
    if (iso) return iso[0];
    const sv = t.match(/(\d{1,2})\s+(januari|februari|mars|april|maj|juni|juli|augusti|september|oktober|november|december)\w*\s+(\d{4})/i);
    return sv ? `${sv[3]}-${pad(months[sv[2].toLowerCase()])}-${pad(Number(sv[1]))}` : null;
  };
  const seen = new Set();
  const entries = [];
  for (const el of document.querySelectorAll('li, tr, article, [class*=activity i], [class*=aktivitet i]')) {
    const t = text(el);
    const date = dateOf(t);
    if (!date || t.length > 600 || seen.has(t)) continue;
    seen.add(t);
    entries.push({ text: t, date });
  }
  const body = document.body ? text(document.body) : '';
  return {
    entries,
    noneMarker: /inga (sökta )?(jobb|aktiviteter)|inga aktiviteter|no activities|du har inte (lagt till|rapporterat)/i.test(body),
  };
}
