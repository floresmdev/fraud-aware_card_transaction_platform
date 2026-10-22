package dev.floresm.transactions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. {@code @SpringBootApplication} turns on component scanning for this package and its
 * sub-packages: every {@code @Component}, {@code @Service}, {@code @Repository} and
 * {@code @RestController} below {@code dev.floresm.transactions} becomes a Spring-managed bean,
 * and Spring wires them together through their constructors (Inversion of Control).
 */
@SpringBootApplication
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
