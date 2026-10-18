Run backend: .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"

Redis (cloud.redis.io): host/port/user/password live in `.env` as REDIS_* — never commit them. See `application-dev.yml` for wiring.
