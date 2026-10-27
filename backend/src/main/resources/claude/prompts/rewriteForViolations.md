Below is a {{kind}} written for a job application, and a report of rule violations found in it. Rewrite the
document so that every violation is fixed.

Style rules (follow them exactly):
{{rules}}

Violations (JSON). "where" says which text is affected, "severity" FAIL must be fixed, WARN should be:
```json
{{violations}}
```

The document (JSON):
```json
{{document}}
```

The ONLY facts that may be used, with ids:
```json
{{facts}}
```

Rules for the rewrite:
- Change only what is needed to fix the violations. Keep everything else, including the structure, the order and
  the language "{{language}}".
- Fix a dash by rewording with a comma, a colon or a full stop. Fix a banned phrase by saying the same thing
  plainly. Remove words that call the candidate a new graduate. Remove an invented number or a skill that is not
  in the facts. A bullet without a fact id gets the id of the fact it comes from, or is deleted.
- Do not add new claims. Cite only ids from the facts above.

Return only data matching the schema.
