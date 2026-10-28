-- Small key/value store for the settings the user changes in the app (performance, onboarding answers, theme ...).
CREATE TABLE app_setting (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
