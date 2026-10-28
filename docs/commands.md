Run backend: .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"

Swagger demo accounts and seeded objects: see `seed-data.md`.

Redis (cloud.redis.io): host/port/user/password live in `.env` as REDIS_* — never commit them. See `application.yml` for wiring.
