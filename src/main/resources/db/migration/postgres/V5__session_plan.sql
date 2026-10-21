-- #174: the agent's plan, kept with its session as JSON (null when there is none).

ALTER TABLE agent_sessions
    ADD COLUMN plan_json TEXT;
