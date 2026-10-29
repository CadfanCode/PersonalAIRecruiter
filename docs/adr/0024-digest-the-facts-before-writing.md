# 0024. Digest the facts before writing documents

Date: 2026-10-29. Status: accepted.

## Context

CVs and letters read as blocky: bullets repeated the database sentences, every entry was listed with as many facts as
the selector allowed, the profile summarised the entries below it, and education listed every degree. The user's own sent
documents (`seed/examples`) instead merge facts, order them by what the ad values, drop what does not help and carry one
angle through the letter.

## Decision

1. A planning call, `planDocument`, runs between selection and writing (stage DOCUMENTS). It produces an editor's brief:
   field, one angle, ad priorities, evidence points that combine several facts, profile and letter-example guidance,
   gaps to state in the letter, and what to keep short. Both writers receive it. A failed plan is logged and the writers
   continue without one.
2. The writer prompts treat facts as raw material: bullets combine one to four facts of an entry, are ordered by
   relevance, and never strengthen a claim (rule 4). The profile is one or two paragraphs of 70 to 110 words. Gaps are
   stated in the letter only. The letter is 280 to 380 words with a single angle.
3. Education is selected by Claude (every completed degree as one line, most relevant first; unfinished programmes, duplicates and minor unrelated courses dropped); the newest one is kept as a fallback. This replaces "all
   education entries are always included" in SPEC 8.1. Pinned entries still go in whole.
4. `DocumentValidator` warns with `copied_facts` when most bullets repeat a fact, which triggers the single rewrite.

## Consequences

One more model call per job (cheap: structured output of a few hundred tokens). Prose quality is judged by reading real
output next to the seed examples, not by unit tests; the validator check only catches the worst case.
