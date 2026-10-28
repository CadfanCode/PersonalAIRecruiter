# 0010 Paste a job link, and sites that must not be automated

Date: 2026-10-27. Status: accepted.

Some jobs are not on Platsbanken (for example LinkedIn). The user pastes the address and the job joins the normal pipeline in a
MANUAL run.

- **LinkedIn is never fetched and never opened by automation.** It discourages bots, and the apply browser would use the user's
  real signed-in account. `jobpilot.manual.paste-only-hosts` (default linkedin.com) lists such sites: the user pastes the ad text
  from their own browser, and Finalize shows "Apply on the site yourself" with no tab. If the ad names an explicit employer apply
  link, that link is used for filling like any other.
- **Other pages are read once, carefully.** http(s) only, public addresses only (checked again at every redirect so a public page
  cannot bounce the reader into the local network), size and time limits, no cookies or login. Pages that cannot be read, or are too
  thin to be an ad, fall back to the paste step instead of failing.
- **The page text is data.** `extractJob` is told to ignore instructions in it; the structured JobPosting data many sites publish is
  given to the model first. The municipality must be spelled as Platsbanken spells it, because the aktivitetsrapport plan needs it.
- **No overwrite.** An ad that is already stored (same dedupe key) is linked to the run instead of upserted, so a pasted copy of a
  Platsbanken ad cannot replace its apply link.
