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
  // A search box or a newsletter sign-up on an ad page is not the application form.
  const notTheForm = (el) => (el.getAttribute('type') || '').toLowerCase() === 'search' || el.closest('[role=search]')
    || /search|sök|newsletter|nyhetsbrev/i.test((el.name || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.placeholder || ''));
  const fields = [...document.querySelectorAll('input, select, textarea')].filter((el) => {
    const type = (el.getAttribute('type') || '').toLowerCase();
    return !['hidden', 'submit', 'button', 'image', 'reset'].includes(type) && (type === 'file' || visible(el)) && !notTheForm(el);
  });
  const clickables = [...document.querySelectorAll('button, a, [role=button], input[type=submit], input[type=button]')].filter(visible);
  return {
    passwordVisible: [...document.querySelectorAll('input[type=password]')].some(visible),
    // Whole words only: "registerkontroll" in an ad or "belastningsregister" in a form is not an account page.
    signInText: /\b(logga in|sign in|log in|login)\b/i.test(body),
    bankIdText: /\b(bankid|mobilt bankid)\b/i.test(body),
    createAccountText: /\b(skapa (ett )?konto|create (an )?account|registrera (dig|ett konto)|sign up|register (an )?(account|now|here))\b/i.test(body),
    confirmPasswordText: /\b(confirm password|bekräfta lösenord|upprepa lösenord)\b/i.test(body),
    fieldCount: fields.length,
    // A visible way to start the application: the same labels as find-apply-candidates.js.
    // Not a submit button and not inside a form that has inputs: that one sends an application, it does not open one.
    applyRoute: clickables.some((el) => {
      const form = el.closest('form');
      const inForm = form && form.querySelector('input:not([type=hidden]):not([type=submit]):not([type=button]), select, textarea');
      return !inForm && (el.getAttribute('type') || '').toLowerCase() !== 'submit'
        && /^\s*(ansök|apply|sök (tjänsten|jobbet|nu)|jag är intresserad|i['’]?m interested|i am interested|start (the )?application|påbörja ansökan)/i
          .test(text(el) || el.value || el.getAttribute('aria-label') || '');
    }),
    sessionError: /something went wrong|ett fel uppstod|sessionen har gått ut|session (has )?expired/i.test(body),
    url: location.href,
    title: document.title || '',
  };
}
