package com.distributedcache.cachenode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
public class CachenodeApplication {

	public static void main(String[] args) {
		SpringApplication.run(CachenodeApplication.class, args);
	}
}