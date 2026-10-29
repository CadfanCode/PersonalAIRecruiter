# 0023 The application form is worked through by a look, decide, act loop

Date: 2026-10-28. Status: accepted. Amends CLAUDE.md hard rule 1 and ADR 0018 ("Known gap: pressing Nästa is not automated"),
replaces the one-shot `mapFormFields` pass.

- **Problem.** The old flow classified the page once (at DOMCONTENTLOADED), extracted `input/select/textarea` elements, asked the
  model for one mapping, executed it and stopped after two passes. It could not choose from custom drop-downs (they are not
  `<select>`), could not press Next, and called single-page apps a login wall because it classified before they drew their
  Apply button and counted any "Logga in" text on the page, header included.
- **Approach.** The way Claude drove a form through Playwright MCP: take the accessibility snapshot (`page.ariaSnapshot`, AI
  mode, every element has a `ref`), decide, act by ref, snapshot again. `ApplyService.browse` runs the loop; the model call is
  `browseApplication` (stage Auto-fill). One call returns the page kind and the actions for the page.
- **What did not change.** The model is still not trusted. `ActionValidator` checks every action against the controls of the
  snapshot (profile equality for name, email, phone and city; answers memory for judgement questions; approved documents only;
  consent and credential fields refused; personal numbers never). `BrowserGuard` is still the only class that touches an element;
  refs are matched against `^(f\d+)?e\d+$` before they reach a locator string.
- **Secrets.** The snapshot repeats the value of text boxes, including a password a saved login just typed. `PageSnapshot`
  therefore replaces every text box value with `[filled]` before anything leaves the class. A test proves a password and a
  personnummer never appear in the prompt.
- **Rule 1 amended (second exception).** `ClickPurpose.ADVANCE_STEP` presses a button whose whole label is a next word
  (`GuardPatterns.ADVANCE_LABEL`), and the guest routes of an account page. Because most Next buttons are `type=submit` of
  their own step form, the type check is replaced by: the label rule, a visible-heading check for review, summary, confirm and
  granska pages, and a refusal when any other visible control sends, submits, finishes, registers or is a bare Ansök/Apply. Hidden
  later steps are ignored (their text still reads back, which a test caught). If a site's last step is labelled "Continue" with
  no such sign, JobPilot would press it; this is the residual risk, and the user is told to check every filled form.
- **Custom drop-downs.** `choose` tries `selectOption`, and when the element is not a `<select>` opens it
  (`ClickPurpose.OPEN_DROPDOWN`: a combobox, `aria-haspopup`, `aria-expanded` or `aria-controls`) and clicks the visible option
  whose text equals the wanted one, or the single option that contains it. Nothing is typed unless the list is empty until typed.
- **Sign-in.** A wall needs a visible password field (with no guest route offered) or the model's `login` verdict plus
  sign-in wording in the main content; header, nav and footer text is ignored by `detect-state.js`.
- **Loop safety.** 30 looks, a deadline for reaching a form, a button never pressed twice on one page, an action never repeated
  on one page, two unchanged looks end the run.
- **Cost.** One model call per look instead of one per form: an ad, its form and each step each cost a call.
- **Removed.** `find-apply-candidates.js`, `ApplyCandidates`, `BrowserGuard.openApplication`, the `mapFormFields` prompt, the
  two-pass limit. Real-site behaviour (Workday, Varbi, Ponty, Teamtailor) is not verified; only local fixtures were run.
