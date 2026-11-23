# Revenue forecast (Prophet)

Offline monthly revenue forecast for the DACN store. No Java, server, or
dashboard changes: the script reads orders from PostgreSQL and writes files.

## Setup

From the repository root:

```bash
cd DACN/ml/revenue-forecast
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Requires Python 3.10+ and access to the PostgreSQL database. By default the
script reads `DACN/.env` for `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

## Run

```bash
.venv/bin/python forecast_revenue.py
```

Options: `--env-file PATH`, `--output-dir PATH`, `--months N` (default 12).

The script groups revenue by Vietnam calendar month, excludes the incomplete
current month, requires at least 24 complete months, excludes `CANCELLED`
orders, and sums `orders.order_total`. It backtests the last six months,
refits on all history, then forecasts the next twelve months.

## Outputs

Written to `output/` by default:

- `monthly_forecast.csv` — `ds`, `yhat`, `yhat_lower`, `yhat_upper`.
- `annual_forecast.csv` — calendar-year sums and the number of forecast months
  in each year. A year with fewer than 12 months is partial.
- `metrics.json` — training months, holdout MAE/MAPE, forecast range.
- `forecast.png` — actual revenue, forecast line, and 80% interval.

## Tests

```bash
.venv/bin/python -m unittest -v
```

## Demo history

`DACN/src/main/resources/db/dev/R__swagger_revenue_history.sql` seeds 36 months
of synthetic orders for the dev demo. The history is synthetic, not real store
demand. Once real history has two seasonal cycles, remove the seed with:

```sql
DELETE FROM orders WHERE order_id LIKE 'ORD_DEMO_REV_%';
```
