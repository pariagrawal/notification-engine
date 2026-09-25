package com.paridhi.notificationengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationEngineApplication.class, args);
	}

}
