import tempfile
import unittest
from datetime import date
from pathlib import Path

import pandas as pd

from forecast_revenue import (
    annual_totals,
    connection_kwargs,
    read_env,
    trim_incomplete_month,
    validate_history,
)


class TestHelpers(unittest.TestCase):
    def test_read_env_ignores_comments_and_quotes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text(
                "# comment\n"
                "SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/sales\n"
                'SPRING_DATASOURCE_USERNAME="postgres.demo"\n',
                encoding="utf-8",
            )
            env = read_env(path)
        self.assertEqual(env["SPRING_DATASOURCE_USERNAME"], "postgres.demo")
        self.assertNotIn("# comment", env)

    def test_connection_kwargs_strips_jdbc_and_uses_env_credentials(self):
        env = {
            "SPRING_DATASOURCE_URL": (
                "jdbc:postgresql://db.example.com:6543/sales?sslmode=require"
            ),
            "SPRING_DATASOURCE_USERNAME": "postgres.demo",
            "SPRING_DATASOURCE_PASSWORD": "secret",
        }
        self.assertEqual(
            connection_kwargs(env),
            {
                "host": "db.example.com",
                "port": 6543,
                "dbname": "sales",
                "user": "postgres.demo",
                "password": "secret",
                "sslmode": "require",
            },
        )

    def test_trim_incomplete_month_drops_only_current_month(self):
        frame = pd.DataFrame(
            {
                "ds": pd.to_datetime(
                    ["2026-10-01", "2026-11-01", "2026-12-01"]
                ),
                "y": [1.0, 2.0, 3.0],
            }
        )
        result = trim_incomplete_month(frame, date(2026, 11, 23))
        self.assertEqual(list(result["y"]), [1.0, 3.0])

    def test_validate_history_rejects_fewer_than_24_months(self):
        short = pd.DataFrame(
            {
                "ds": pd.date_range("2025-01-01", periods=23, freq="MS"),
                "y": range(23),
            }
        )
        with self.assertRaisesRegex(ValueError, "24"):
            validate_history(short)
        enough = pd.DataFrame(
            {
                "ds": pd.date_range("2025-01-01", periods=24, freq="MS"),
                "y": range(24),
            }
        )
        validate_history(enough)

    def test_annual_totals_sums_forecast_and_counts_months(self):
        forecast = pd.DataFrame(
            {
                "ds": pd.to_datetime(["2027-01-01", "2027-02-01"]),
                "yhat": [10.0, 20.0],
                "yhat_lower": [8.0, 16.0],
                "yhat_upper": [12.0, 24.0],
            }
        )
        result = annual_totals(forecast)
        self.assertEqual(len(result), 1)
        row = result.iloc[0]
        self.assertEqual(row["year"], 2027)
        self.assertEqual(row["months"], 2)
        self.assertEqual(row["yhat"], 30.0)
        self.assertEqual(row["yhat_lower"], 24.0)
        self.assertEqual(row["yhat_upper"], 36.0)


if __name__ == "__main__":
    unittest.main()
