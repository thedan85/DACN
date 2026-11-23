# Revenue forecast — user guide

This guide is for anyone who needs to run the Prophet revenue forecast, read its
output, or use it from the admin dashboard.

## 1. What this does

The tool predicts monthly revenue for the DACN aquarium store with
[Prophet](https://facebook.github.io/prophet/).

Flow:

```text
orders in PostgreSQL
  → Python pipeline (monthly aggregation + Prophet)
  → output/forecast.json
  → Spring endpoint GET /api/v1/admin/dashboard/forecast
  → Revenue forecast panel on /admin/dashboard
```

Important: the model does **not** retrain on every page load. Running the Python
script refreshes the forecast file; the API and dashboard serve the last
generated result.

## 2. Prerequisites

- Python 3.10 or newer (tested on 3.14).
- Access to the DACN PostgreSQL database.
- `DACN/.env` containing:
  - `SPRING_DATASOURCE_URL`
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`
- At least 24 complete months of order history. The current complete month is
  excluded because it is partial.

## 3. Install and run

From the repository root:

```bash
cd DACN/ml/revenue-forecast
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m unittest -v
.venv/bin/python forecast_revenue.py
```

Useful options:

```bash
.venv/bin/python forecast_revenue.py --months 6
.venv/bin/python forecast_revenue.py --env-file /path/to/.env
.venv/bin/python forecast_revenue.py --output-dir /path/to/output
```

Expected console output:

```text
Forecast 12 months (2026-11-01 to 2027-10-01) from 36 training months.
Holdout MAE=167,966 VND, MAPE=2.1%.
```

The script fails clearly if there are fewer than 24 complete months.

## 4. Demo data (dev only)

The shared dev database currently has only two months of real orders, which is
not enough for yearly seasonality. A dev-only repeatable seed adds 36 months of
synthetic history:

```text
DACN/src/main/resources/db/dev/R__swagger_revenue_history.sql
```

It is loaded by `application-dev.yml`. It is idempotent: rerunning adds no
duplicate rows.

Remove the synthetic history when real history is available:

```sql
DELETE FROM orders WHERE order_id LIKE 'ORD_DEMO_REV_%';
```

This cascades to the demo order items and payments. Never enable the `db/dev`
Flyway location in production.

## 5. Output files

All files are written to `DACN/ml/revenue-forecast/output/`:

| File | Use |
|---|---|
| `forecast.json` | API-ready payload served by Spring and validated by the frontend. |
| `monthly_forecast.csv` | Spreadsheet-friendly monthly values: `ds,yhat,yhat_lower,yhat_upper`. |
| `annual_forecast.csv` | Calendar-year totals and `months`, the number of forecast months in that year. |
| `metrics.json` | Training months, holdout MAE/MAPE, forecast range. |
| `forecast.png` | Chart of actual revenue, forecast, and 80% interval. |

Example `forecast.json` shape:

```json
{
  "generatedAt": "2026-11-23T19:03:21+07:00",
  "model": "Prophet",
  "trainingMonths": 36,
  "holdoutMonths": 6,
  "mae": 167965.57,
  "mape": 2.13,
  "months": [
    {
      "date": "2026-11-01",
      "predicted": 10356310.87,
      "lower": 10198855.41,
      "upper": 10525052.57
    }
  ],
  "annual": [
    {
      "year": 2026,
      "months": 2,
      "predicted": 20634185.43,
      "lower": 20307519.70,
      "upper": 20977003.67
    }
  ]
}
```

Money values are VND numbers. `mape` can be `null`. `annual.months < 12` means
the forecast horizon covers only part of that calendar year; the dashboard
labels those years `(partial)`.

## 6. Use the API

Endpoint:

```text
GET /api/v1/admin/dashboard/forecast
```

Authorization: same as the dashboard summary — an access token with the
`order:read` authority. There are no query parameters.

Login (dev demo account), then call the endpoint:

```bash
TOKEN=$(curl -s http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"swagger.admin@example.com","password":"Swagger123!"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])')

curl -s http://localhost:8080/api/v1/admin/dashboard/forecast \
  -H "Authorization: Bearer $TOKEN"
```

Success has the standard envelope:

```json
{
  "code": 200,
  "message": "OK",
  "data": { "...": "same object as forecast.json" }
}
```

If the forecast file is missing or invalid:

```json
{
  "code": 503,
  "message": "Revenue forecast is not available.",
  "data": null
}
```

Retry after regenerating `forecast.json`. The response never exposes the server
file path.

## 7. Use the dashboard

1. Start the backend with the `dev` profile (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`).
2. Start the frontend (`npm run dev` in `fe-dacn`).
3. Sign in at `http://localhost:3000/admin/login`.
4. Open **Dashboard**. The **Revenue forecast** panel appears below the revenue chart.

The panel shows:

- forecast line and 80% interval band, with a point for every month;
- hover a point, or focus it with Tab, to see that month's revenue and 80%
  range; press Escape to clear the tooltip;
- a monthly table below the chart with the same 12 values, so no hover is
  needed;
- annual totals, with `(partial)` for years covered by fewer than 12 months;
- `generatedAt`, MAE, and MAPE.

A forecast failure shows its own error and retry button; the rest of the
dashboard stays usable.

## 8. Refresh workflow

1. Pull new orders into the database.
2. Run `forecast_revenue.py` again.
3. The API immediately serves the new `forecast.json`; reload the dashboard.

For deployment, set `FORECAST_FILE` to the path where `forecast.json` is placed.
Default:

```text
ml/revenue-forecast/output/forecast.json
```

relative to the backend working directory. No runbook copies the file; the
team that deploys owns that step.

## 9. Troubleshooting

| Symptom | Cause | Action |
|---|---|---|
| `need at least 24 complete months...` | Too little history. | Apply the dev seed or wait for more real months. |
| HTTP 503 from the endpoint | Missing, unreadable, or invalid `forecast.json`. | Regenerate with the Python script; check `FORECAST_FILE`. |
| Dashboard shows old time | Stale artifact. | Re-run the script and reload the page. |
| `401` / `403` | Missing token or wrong authority. | Log in with an account holding `order:read`. |
| 2027 labelled `(partial)` | Horizon ends in October 2027. | Expected; run with `--months 14` to cover all of 2027. |
| `mape: null` | A zero-revenue holdout month. | Expected; the panel shows `n/a`. |

## 10. Caveats

- Demo history is synthetic; forecasts demonstrate the method, not real demand.
- `annual.lower` and `annual.upper` are sums of monthly bounds, not an exact
  80% interval for the annual total.
- The current incomplete month is always excluded.
- Prophet has no official Java port, so the model runs in Python and Java only
  serves the precomputed result.
