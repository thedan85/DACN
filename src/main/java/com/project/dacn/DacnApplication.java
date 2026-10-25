package com.project.dacn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
public class DacnApplication {

	public static void main(String[] args) {
		Dotenv.configure().ignoreIfMissing().systemProperties().load();

		// dotenv stores SPRING_PROFILES_ACTIVE as a system property, but Spring
		// only reads spring.profiles.active. Map it so .env activates the profile.
		if (System.getProperty("spring.profiles.active") == null) {
			String profiles = System.getProperty("SPRING_PROFILES_ACTIVE");
			if (profiles != null && !profiles.isBlank()) {
				System.setProperty("spring.profiles.active", profiles);
			}
		}

		SpringApplication.run(DacnApplication.class, args);
	}
}
