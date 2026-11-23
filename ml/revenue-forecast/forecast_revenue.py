#!/usr/bin/env python3
"""Offline Prophet forecast of monthly store revenue."""

from __future__ import annotations

import argparse
import json
import logging
import math
from datetime import date, datetime
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse
from zoneinfo import ZoneInfo

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import pandas as pd
import psycopg
from prophet import Prophet

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_DIR = SCRIPT_DIR.parent.parent
DEFAULT_ENV_FILE = REPO_DIR / ".env"
DEFAULT_OUTPUT_DIR = SCRIPT_DIR / "output"
STORE_ZONE = ZoneInfo("Asia/Ho_Chi_Minh")
MINIMUM_MONTHS = 24
BACKTEST_MONTHS = 6

REVENUE_SQL = """
SELECT (date_trunc('month', created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'))::date AS ds,
       sum(order_total)::float8 AS y
FROM orders
WHERE order_status <> 'CANCELLED'
GROUP BY 1
ORDER BY 1
"""


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def connection_kwargs(env: dict[str, str]) -> dict[str, object]:
    url = env.get("SPRING_DATASOURCE_URL", "")
    if url.startswith("jdbc:"):
        url = url[5:]
    parsed = urlparse(url)
    params = parse_qs(parsed.query)
    return {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "dbname": parsed.path.lstrip("/") or "postgres",
        "user": env.get("SPRING_DATASOURCE_USERNAME") or unquote(parsed.username or ""),
        "password": env.get("SPRING_DATASOURCE_PASSWORD")
        or unquote(parsed.password or ""),
        "sslmode": params.get("sslmode", ["prefer"])[0],
    }


def load_history(connection: psycopg.Connection) -> pd.DataFrame:
    with connection.cursor() as cursor:
        cursor.execute(REVENUE_SQL)
        rows = cursor.fetchall()
    frame = pd.DataFrame(rows, columns=["ds", "y"])
    frame["ds"] = pd.to_datetime(frame["ds"])
    frame["y"] = pd.to_numeric(frame["y"], errors="raise")
    return frame.sort_values("ds").reset_index(drop=True)


def trim_incomplete_month(frame: pd.DataFrame, today: date) -> pd.DataFrame:
    current = pd.Period(today, freq="M")
    return frame.loc[frame["ds"].dt.to_period("M") != current].reset_index(drop=True)


def validate_history(frame: pd.DataFrame, minimum: int = MINIMUM_MONTHS) -> None:
    if len(frame) < minimum:
        raise ValueError(
            f"need at least {minimum} complete months of revenue history; got {len(frame)}"
        )


def annual_totals(forecast: pd.DataFrame) -> pd.DataFrame:
    if forecast.empty:
        return pd.DataFrame(columns=["year", "months", "yhat", "yhat_lower", "yhat_upper"])
    grouped = forecast.assign(year=forecast["ds"].dt.year).groupby("year", as_index=False).agg(
        months=("ds", "count"),
        yhat=("yhat", "sum"),
        yhat_lower=("yhat_lower", "sum"),
        yhat_upper=("yhat_upper", "sum"),
    )
    return grouped[["year", "months", "yhat", "yhat_lower", "yhat_upper"]]


def forecast_payload(
    forecast: pd.DataFrame, annual: pd.DataFrame, metrics: dict[str, object]
) -> dict[str, object]:
    mape = metrics["mape"]
    return {
        "generatedAt": metrics["generatedAt"],
        "model": "Prophet",
        "trainingMonths": metrics["trainingMonths"],
        "holdoutMonths": metrics["holdoutMonths"],
        "mae": round(float(metrics["mae"]), 2),
        "mape": None if mape is None or math.isnan(float(mape)) else round(float(mape), 2),
        "months": [
            {
                "date": row.ds.date().isoformat(),
                "predicted": round(float(row.yhat), 2),
                "lower": round(float(row.yhat_lower), 2),
                "upper": round(float(row.yhat_upper), 2),
            }
            for row in forecast.itertuples(index=False)
        ],
        "annual": [
            {
                "year": int(row.year),
                "months": int(row.months),
                "predicted": round(float(row.yhat), 2),
                "lower": round(float(row.yhat_lower), 2),
                "upper": round(float(row.yhat_upper), 2),
            }
            for row in annual.itertuples(index=False)
        ],
    }


def new_model() -> Prophet:
    return Prophet(
        yearly_seasonality=True,
        weekly_seasonality=False,
        daily_seasonality=False,
        interval_width=0.8,
    )


def backtest(history: pd.DataFrame) -> tuple[float, float]:
    train = history.iloc[:-BACKTEST_MONTHS]
    actual = history.iloc[-BACKTEST_MONTHS:].reset_index(drop=True)
    model = new_model()
    model.fit(train)
    future = model.make_future_dataframe(periods=BACKTEST_MONTHS, freq="MS")
    predicted = model.predict(future).tail(BACKTEST_MONTHS).reset_index(drop=True)
    merged = actual[["ds", "y"]].merge(
        predicted[["ds", "yhat"]], on="ds", how="inner"
    )
    error = (merged["y"] - merged["yhat"]).abs()
    mae = float(error.mean())
    nonzero = merged[merged["y"] != 0]
    mape = (
        float((error[nonzero.index] / nonzero["y"]).abs().mean() * 100)
        if not nonzero.empty
        else float("nan")
    )
    return mae, mape


def make_forecast(history: pd.DataFrame, months: int) -> pd.DataFrame:
    model = new_model()
    model.fit(history)
    future = model.make_future_dataframe(periods=months, freq="MS")
    predicted = model.predict(future)
    future_only = predicted.loc[
        predicted["ds"] > history["ds"].max(),
        ["ds", "yhat", "yhat_lower", "yhat_upper"],
    ]
    return future_only.reset_index(drop=True)


def write_outputs(
    output_dir: Path,
    history: pd.DataFrame,
    forecast: pd.DataFrame,
    annual: pd.DataFrame,
    metrics: dict[str, object],
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    forecast.to_csv(output_dir / "monthly_forecast.csv", index=False, float_format="%.2f")
    annual.to_csv(output_dir / "annual_forecast.csv", index=False, float_format="%.2f")
    (output_dir / "metrics.json").write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    figure, axis = plt.subplots(figsize=(12, 6))
    axis.plot(history["ds"], history["y"] / 1_000_000, label="Actual", color="#1f4e79")
    axis.plot(forecast["ds"], forecast["yhat"] / 1_000_000, label="Forecast", color="#d1495b")
    axis.fill_between(
        forecast["ds"],
        forecast["yhat_lower"] / 1_000_000,
        forecast["yhat_upper"] / 1_000_000,
        color="#d1495b",
        alpha=0.18,
        label="80% interval",
    )
    axis.axvline(history["ds"].max(), color="#888888", linestyle="--", linewidth=1)
    axis.set_title("Monthly revenue forecast")
    axis.set_xlabel("Month")
    axis.set_ylabel("Revenue (million VND)")
    axis.grid(alpha=0.25)
    axis.legend()
    figure.autofmt_xdate()
    figure.tight_layout()
    figure.savefig(output_dir / "forecast.png", dpi=150)
    plt.close(figure)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--months", type=int, default=12)
    args = parser.parse_args(argv)
    if args.months < 1:
        raise ValueError("months must be at least 1")

    env = read_env(args.env_file)
    with psycopg.connect(**connection_kwargs(env)) as connection:
        history = load_history(connection)
    history = trim_incomplete_month(history, datetime.now(STORE_ZONE).date())
    validate_history(history)

    mae, mape = backtest(history)
    forecast = make_forecast(history, args.months)
    annual = annual_totals(forecast)
    metrics = {
        "generatedAt": datetime.now(STORE_ZONE).isoformat(),
        "trainingMonths": int(len(history)),
        "holdoutMonths": BACKTEST_MONTHS,
        "mae": mae,
        "mape": mape,
        "lastHistoryMonth": history["ds"].max().date().isoformat(),
        "forecastStart": forecast["ds"].min().date().isoformat(),
        "forecastEnd": forecast["ds"].max().date().isoformat(),
        "forecastMonths": int(len(forecast)),
    }
    write_outputs(args.output_dir, history, forecast, annual, metrics)
    payload = forecast_payload(forecast, annual, metrics)
    (args.output_dir / "forecast.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"Forecast {metrics['forecastMonths']} months "
        f"({metrics['forecastStart']} to {metrics['forecastEnd']}) "
        f"from {metrics['trainingMonths']} training months. "
        f"Holdout MAE={mae:,.0f} VND, MAPE={mape:.1f}%."
    )
    return 0


if __name__ == "__main__":
    logging.getLogger("prophet").setLevel(logging.WARNING)
    raise SystemExit(main())
