// Fixed, read-only script: the links and buttons of the page that could open an application form. Nothing is clicked here.
// The index is the position among all matches of the selector, so the guard can find the same element again.
() => {
  const text = (el) => (el.innerText || el.value || el.getAttribute('aria-label') || el.textContent || '').replace(/\s+/g, ' ').trim();
  const visible = (el) => {
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const all = [...document.querySelectorAll('button, a, [role=button], input[type=button], input[type=submit]')];
  return all.map((el, index) => {
    const form = el.closest('form');
    const inputs = form ? form.querySelectorAll('input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=image]), select, textarea').length : 0;
    return {
      index,
      tag: el.tagName.toLowerCase(),
      type: (el.getAttribute('type') || '').toLowerCase(),
      role: (el.getAttribute('role') || '').toLowerCase(),
      text: text(el).slice(0, 80),
      // A link to the same page (href="#...") is a button in disguise: no address to load, it has to be pressed.
      href: el.tagName === 'A' && el.getAttribute('href') && !el.getAttribute('href').startsWith('#')
        && new URL(el.href, location.href).href.split('#')[0] !== location.href.split('#')[0] ? (el.href || '') : '',
      target: el.getAttribute('target') || '',
      visible: visible(el),
      inFormWithFields: inputs > 0,
      inForm: !!form,
    };
  }).filter((c) => c.visible && c.text);
}
