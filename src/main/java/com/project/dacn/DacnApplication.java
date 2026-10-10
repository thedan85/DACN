package com.project.dacn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
public class DacnApplication {

	public static void main(String[] args) {
		Dotenv.configure().ignoreIfMissing().systemProperties().load();
		SpringApplication.run(DacnApplication.class, args);
	}
}
