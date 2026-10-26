# How to start the build in Claude Code

1. Unzip this folder somewhere on your Linux machine, for example `~/projects/jobpilot`, and run
   `git init` in it.
2. Make sure you have Java 21, Docker, Node 22 and Claude Code installed. Run `claude --version`
   to check, and `claude` once to sign in if needed.
3. Open a terminal in the folder and start Claude Code with `claude`.
4. Paste the prompt below.

---

Read CLAUDE.md and docs/SPEC.md in full before writing any code. We are building JobPilot exactly
as specified there.

Work through the milestones in SPEC section 15 in order. For each milestone:
- Plan it briefly first, then implement it.
- Write the tests listed in SPEC section 14 for that milestone and make them pass
  (`./mvnw verify` and `npm test`).
- Run the app and check the milestone's acceptance criterion yourself where you can.
- Commit with a clear message.
- Stop and give me a short summary plus anything I need to do by hand before you continue.

Before milestone 3, fetch the live JobTech JobSearch OpenAPI spec and confirm the exact
parameters and whether an api-key header is needed. Update SPEC 6.1 if anything differs.

Use seed/cv_database_seed.json to populate the CV database in milestone 2. The PDFs in
seed/examples are the documents I actually sent on 26 Oct 2026. Use them as the visual and tonal
reference for the generated CVs and letters in milestone 4.

Never break the hard rules in CLAUDE.md, especially: never click submit on external sites, never
handle passwords, BankID or consent boxes, and never put facts in a CV that are not in the CV
database.

Start with milestone 1.
