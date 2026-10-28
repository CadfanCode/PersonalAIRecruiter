# 0009 Discover roles

Date: 2026-10-27. Status: accepted.

A broad background (software, teaching, sport science, public service) means the best role is often one the user has not searched
for. Discover mode lets Claude propose roles from the whole CV and searches the chosen ones.

- **Suggestions carry their search fields**, so choosing a role needs no extra Claude call; only roles the user typed or renamed
  need one batched `profileRoles` call. Each role becomes its own immutable SearchProfile on top of the run's base profile, so the
  user's locations and age limit apply to every role and scores stay cached per (job, profile, CV version).
- **One score per ad, against its best-relevance role.** Scoring an ad once per role would multiply cost; JobTech relevance across
  different roles' queries is only roughly comparable, so this is a trade-off. The ad is tagged with every role that found it.
- **Budget.** At most 150 ads are scored per run (60 by default, 150 aggressive), taken round-robin across roles so one broad role
  cannot use the budget. Unscored ads stay in the table, marked not scored.
- **The role's profile is the ad's profile.** Tailoring (selectFacts, writeCv, writeLetter), the drawer score, the application score
  and the yrkesroll typed into Arbetsförmedlingen's form all use the role's profile, via `JobRepository.profileIdFor`/
  `latestSearchProfileId`. The run's base profile of a Discover run has no scores and must not be used for these.
- **Honesty.** The prompt asks for honest match scores and gaps (for example a missing teaching licence) and only facts from the CV
  database; what the suggestions can cover depends on how complete the CV database is.
