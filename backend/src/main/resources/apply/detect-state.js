// Fixed, read-only script: what kind of page is this? Used to decide whether the user has to act first.
() => {
  const text = (el) => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
  const visible = (el) => {
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const body = document.body ? text(document.body) : '';
  const fields = [...document.querySelectorAll('input, select, textarea')].filter((el) => {
    const type = (el.getAttribute('type') || '').toLowerCase();
    return !['hidden', 'submit', 'button', 'image', 'reset'].includes(type) && (type === 'file' || visible(el));
  });
  const clickables = [...document.querySelectorAll('button, a, [role=button], input[type=submit], input[type=button]')].filter(visible);
  return {
    passwordVisible: [...document.querySelectorAll('input[type=password]')].some(visible),
    signInText: /logga in|sign in|log in/i.test(body),
    bankIdText: /bankid|mobilt bankid/i.test(body),
    createAccountText: /skapa konto|create (an )?account|registrera dig|register/i.test(body),
    confirmPasswordText: /confirm password|bekräfta lösenord|upprepa lösenord/i.test(body),
    fieldCount: fields.length,
    ansokButton: clickables.some((el) => /^\s*ansök/i.test(text(el) || el.value || '')),
    sessionError: /something went wrong|ett fel uppstod|sessionen har gått ut|session (has )?expired/i.test(body),
    url: location.href,
    title: document.title || '',
  };
}
