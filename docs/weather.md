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
- After a failed read a city is not retried for 10 minutes; a hard budget of 300 reads per UTC day stops reads below
  the free limit. A stored forecast is served for up to 6 hours while the Met Office cannot be reached.
- Concurrent requests for a city wait for the one read in progress. The store is in memory: a restart re-reads on
  demand.
- Only nine normalised fields leave the backend (`WeatherResponse`), never the raw payload.

## Enabling (owner)

1. Register at https://datahub.metoffice.gov.uk/, order **Global spot**, free plan, accepting the terms as the
   business operating Life in UK; copy the API key.
2. Locally: add `MET_OFFICE_API_KEY=` to the backend env and check `/api/weather/current?city=london`.
3. Production, after the release containing this endpoint (Session Manager, as in travel-release.md step 3):
   `/opt/lifeinuk/scripts/set-env.sh /etc/lifeinuk/backend.env MET_OFFICE_API_KEY`, then
   `cd /opt/lifeinuk && docker compose up -d --no-deps app`.
4. Turn it off again by clearing the key the same way.
