# Homepage weather (Met Office Weather DataHub)

`GET /api/weather/current?city=<id>` returns the Met Office hourly spot forecast step for the current hour at one of
eight fixed cities (london, birmingham, manchester, leeds, glasgow, edinburgh, cardiff, belfast). The homepage Live
Services section shows it in its Weather tile. **Off until a key is configured:** without `MET_OFFICE_API_KEY` the
endpoint answers `503 WEATHER_NOT_CONFIGURED` and nothing calls the Met Office.

## Licence (checked 2026-11-19)

- Product: Weather DataHub **Global spot**, free plan ([pricing](https://datahub.metoffice.gov.uk/pricing/site-specific)):
  up to 360 calls a day, reset 00:00 UTC; paid plans from £9/month (900 a day). Spot data is updated hourly.
- [Terms and conditions](https://www.metoffice.gov.uk/api/assets/file/met-office-weatherdatahub-terms-and-conditionspdf?prefix=assets)
  (15 August 2024, v13) cover business customers; the [FAQ](https://datahub.metoffice.gov.uk/support/faqs) answers
  "Can I use the data I obtain commercially?" with "Yes. Subject to the terms of the licence granted to you."
  Clause 2.2 licenses the API "to receive the Met Office Weather DataHub Products and provide them to Your Users of Your
  Application(s)"; clause 3.2 permits use, publishing and adaptation within the application, but not making the data
  available to third parties other than through it or re-selling it in original form.
- Attribution (clause 2.6.1): "Data supplied by the Met Office", shown in the Weather tile next to the data. No Met
  Office logo is used (clause 3.5 needs written consent).
- The provider's location names are separately licensed, so they are not requested (`includeLocationName=false`); the
  frontend shows its own translated city names.

## Quota design

- Fixed city list only: a visitor cannot make the backend request another location.
- Each city is read at most once an hour (`WeatherService.REFRESH`); a response covers 48 hours, so the current-hour
  step is picked from the stored forecast at request time. Normal use: at most 8 × 24 = 192 calls a day.
- After a failed or rejected read a city is not retried for 10 minutes.
- **Daily budget in PostgreSQL** (`weather_call_budget`, migration V16): before every Met Office call one read is
  taken from a budget of 300 per UTC day with a single atomic `INSERT … ON CONFLICT … WHERE calls < 300` statement,
  so concurrent requests cannot pass it and app restarts or container recreation do not reset it. If the database
  cannot be reached no call is made. One row per day is kept (a few kilobytes a year).
- Only nine normalised fields leave the backend (`WeatherResponse`), never the raw payload.

## Refreshing without blocking requests

- Reads run on a small background pool (4 daemon threads; at most one read per city is in flight). A request that
  finds a usable stored forecast is answered from it at once, even while that city's refresh is running.
- With nothing usable stored (first request after a start, or after a long outage) a request waits at most 3 seconds
  for the read and otherwise answers `502 WEATHER_UNAVAILABLE`; the read carries on and the next request gets it.
- Concurrent requests for a city share the one read in progress.
- The forecast store itself is in memory: after a restart each city is read once on demand (within the budget).

## Freshness

A newly read forecast replaces the stored one only if:

- its model run (`modelRunDate`) is not in the future (5 minutes' allowance) and not more than 12 hours old;
- its model run is not older than the stored forecast's (an equal run refreshes the read time);
- it has a step for the current hour: a step at or before now and less than an hour before it. A forecast that starts
  later, ends earlier or has a gap at the current hour is rejected, and a neighbouring hour is never shown instead.

Otherwise the stored forecast is kept and the city backs off for 10 minutes. A stored forecast is served for up to 6
hours after it was read, and only while it has a step for the current hour.

The response's `forecastTime` is the step's hour and `modelRunAt` the run it comes from. The homepage labels the value
as a forecast, e.g. "Met Office forecast for 14:00, issued 11:00 (UK time)", never as a current observation, and
hides it once its hour is over. A page left open reads again just after the hour ends (or 10 minutes after a failed
read), only while the tab is visible.

## Enabling (owner)

1. Register at https://datahub.metoffice.gov.uk/, order **Global spot**, free plan, accepting the terms as the
   business operating Life in UK; copy the API key.
2. Locally: add `MET_OFFICE_API_KEY=` to the backend env and check `/api/weather/current?city=london`.
3. Production, after the release containing this endpoint (it adds migration V16, so that release needs V16 approved
   as in deploy/RUNBOOK.md), in Session Manager as in travel-release.md step 3:

   ```sh
   sudo -i
   /opt/lifeinuk/scripts/set-env.sh /etc/lifeinuk/backend.env MET_OFFICE_API_KEY   # hidden prompt
   cd /opt/lifeinuk && docker compose up -d --no-deps app                          # recreate the app only
   curl -s 'http://127.0.0.1:8080/api/weather/current?city=london'                    # a forecast, not WEATHER_NOT_CONFIGURED
   ```

   `backend.env` is read only when the app container is created: `docker restart` or a crash restart keeps the old
   settings.

## Disabling (owner)

`set-env.sh` keeps the current secret when the prompt is answered with nothing, so use `--clear`, which writes
`MET_OFFICE_API_KEY=` (saving the previous file first, printing only the name):

```sh
sudo -i
/opt/lifeinuk/scripts/set-env.sh --clear /etc/lifeinuk/backend.env MET_OFFICE_API_KEY
/opt/lifeinuk/scripts/set-env.sh --fingerprint /etc/lifeinuk/backend.env MET_OFFICE_API_KEY   # "MET_OFFICE_API_KEY empty"
cd /opt/lifeinuk && docker compose up -d --no-deps app                                      # recreate: about a minute of API 502s
curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8080/api/weather/current?city=london'  # 503
curl -s 'http://127.0.0.1:8080/api/weather/current?city=london'                                  # {"code":"WEATHER_NOT_CONFIGURED",...}
```

From then on nothing calls the Met Office and the homepage tile says weather is unavailable. This exact sequence
(set a key, recreate, `--clear`, recreate, `503 WEATHER_NOT_CONFIGURED`) runs in CI in `deploy/test/ci-stack-test.sh`.
To undo, restore the saved file (`set-env.sh` prints its path) or set the key again as above.
