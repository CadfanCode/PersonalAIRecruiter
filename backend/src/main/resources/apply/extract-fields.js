// Fixed script run through page.evaluate in every frame. It reads the page and tags each visible field
// with data-jp-id; it never clicks, types or submits. SPEC 9.2 step 3.
(prefix) => {
  const out = [];
  const text = (el) => (el ? (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim() : '');
  const visible = (el) => {
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const labelOf = (el) => {
    const parts = [];
    if (el.labels) for (const l of el.labels) parts.push(text(l));
    const by = el.getAttribute('aria-labelledby');
    if (by) by.split(/\s+/).forEach((i) => { const e = document.getElementById(i); if (e) parts.push(text(e)); });
    const closest = el.closest('label');
    if (closest) parts.push(text(closest));
    return [...new Set(parts.filter(Boolean))].join(' / ');
  };
  const nearby = (el) => {
    let node = el;
    for (let i = 0; i < 3 && node; i++) {
      const prev = node.previousElementSibling;
      if (prev && text(prev)) return text(prev).slice(0, 200);
      node = node.parentElement;
    }
    return '';
  };
  const group = (el) => {
    const fs = el.closest('fieldset');
    const lg = fs && fs.querySelector('legend');
    if (lg) return text(lg);
    const grp = el.closest('[role=radiogroup],[role=group]');
    if (grp) return grp.getAttribute('aria-label') || text(grp.querySelector('legend,label,h1,h2,h3,h4,p')) || '';
    return '';
  };
  window.__jpCounter = window.__jpCounter || 0;
  for (const el of document.querySelectorAll('input, select, textarea')) {
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute('type') || (tag === 'select' ? 'select' : tag === 'textarea' ? 'textarea' : 'text')).toLowerCase();
    if (['hidden', 'submit', 'button', 'image', 'reset'].includes(type)) continue;
    const isFile = type === 'file';
    const shown = visible(el);
    if (!isFile && !shown) continue; // file inputs are often hidden behind a styled button
    let id = el.getAttribute('data-jp-id');
    if (!id) {
      id = prefix + 'jp-' + (++window.__jpCounter);
      el.setAttribute('data-jp-id', id);
    }
    out.push({
      id,
      tag,
      type,
      name: el.getAttribute('name') || '',
      label: labelOf(el),
      ariaLabel: el.getAttribute('aria-label') || '',
      placeholder: el.getAttribute('placeholder') || '',
      title: el.getAttribute('title') || '',
      legend: group(el),
      nearbyText: nearby(el),
      optionLabel: type === 'radio' || type === 'checkbox' ? labelOf(el) : '',
      groupName: type === 'radio' ? (el.getAttribute('name') || '') : '',
      required: el.required || el.getAttribute('aria-required') === 'true',
      disabled: el.disabled || el.readOnly,
      visible: shown,
      // a password is never read, so it can never reach a snapshot or a prompt
      value: type === 'password' ? '' : type === 'checkbox' || type === 'radio' ? (el.getAttribute('value') || '') : (el.value || ''),
      checked: !!el.checked,
      multiple: !!el.multiple,
      accept: el.getAttribute('accept') || '',
      autocomplete: el.getAttribute('role') === 'combobox' || !!el.getAttribute('aria-autocomplete')
        || el.getAttribute('aria-haspopup') === 'listbox' || el.hasAttribute('list'),
      options: tag === 'select' ? [...el.options].map((o) => ({ value: o.value, label: (o.textContent || '').trim() })) : [],
    });
  }
  return out;
}
