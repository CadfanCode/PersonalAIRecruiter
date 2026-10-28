# 0016 Saved portal logins

Date: 2026-10-28. Status: accepted. Relaxes CLAUDE.md hard rule 2 (the user authorised it); rules 1 and 3 stand.

- **What is allowed.** After the user signed in to an employer portal themselves, JobPilot may offer to remember that login. The
  user types it into JobPilot's own dialog. Later JobPilot types it into that portal's login form. Nothing else: it never creates an
  account, never handles BankID, never touches an Arbetsförmedlingen login, and never presses Log in (pressing a submit button on an
  external site would break rule 1; the user does it).
- **Where it lives.** The password is in the OS credential store, account `portal:<host>`. `saved_login` holds host and username
  only. The API never returns a password, `Credentials` and the request records print `***`, and a test checks the password reaches
  no prompt and no status text. Without a credential store nothing is saved.
- **When it is typed.** Only by Java code in `BrowserGuard.fillSavedLogin`, never as a model-proposed action (`ActionValidator`
  still blocks password fields for anything the model proposes, and its tests are unchanged). Only when the tab's host equals the
  saved host exactly (no parent domains, no "www" guessing), only on a page with one visible password field in a form with at most
  three inputs (a sign-up form has a second password field or more inputs). A page that offers BankID next to an ordinary account login is fine; a BankID-only page has no password field, so the one-password-field rule already skips it. JobPilot never types anything for BankID.
- **Enforced by tests.** `NoUnguardedBrowserCallsTest` checks the method types exactly twice, never clicks or presses a key, checks
  the host first and never logs, and that only `ApplyService` calls it, from one place that first looks the login up by host.
- **The user can see and remove it.** Settings lists saved logins by site and username and deletes them (removing the secret too).
