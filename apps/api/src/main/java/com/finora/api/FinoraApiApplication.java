package com.finora.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FinoraApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinoraApiApplication.class, args);
	}

}
