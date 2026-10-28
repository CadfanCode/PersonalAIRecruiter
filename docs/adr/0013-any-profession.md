# 0013 Any profession

Date: 2026-10-28. Status: accepted.

JobPilot started as one developer's tool. It is now meant for anyone, so nothing in it may assume a tech job.

- **Scoring.** The first rubric axis (0-50) is `skills`, not `tech`: how well the candidate's skills, qualifications and
  know-how cover the ad's requirements, in any field. The `job_score.tech` column and the `scoreJob` schema key were renamed
  (V10). Experience (30) and eligibility (20) are unchanged.
- **Discover roles.** The three groups are RELATED (the main field), HYBRID and OUTSIDE_FIELD (was OUTSIDE_TECH). The
  main field is judged from the CV, not assumed.
- **CV database.** Entry kinds gained LICENCE and COURSE next to CERTIFICATION, VOLUNTEER and INTERNSHIP (placements), so
  nursing licences, trade tickets and clinical placements have a home. Security vetting (säkerhetsprövning) is removed from
  the profile (V10 drops the column); a person who wants it on a CV states it as a fact or certification.
- **Prompts and examples.** Prompts name no profession except as one of several examples. The example letters are fictional
  (a nurse, an operations coordinator) and their invented employer names are the "foreign employer" names the validator
  rejects in other letters. The occupation fallback for the aktivitetsrapport is the ad's own title, not a fixed occupation.
- **Seed file.** `seed/cv_database_seed.json` is the original author's own data. The app never loads it by itself; a new
  user starts empty and imports a CV in onboarding.
