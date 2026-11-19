-- Met Office reads per UTC day (docs/weather.md), so the daily quota holds across app restarts and
-- container recreation. One small row per day; the counter is taken atomically before each provider call.
CREATE TABLE weather_call_budget (
    day date PRIMARY KEY,
    calls integer NOT NULL CHECK (calls >= 0)
);
