package com.auditflow.gateway.data;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** The {@code customers} table: display names for tenant ids. */
@Repository
public class CustomerRepository {

    private final JdbcTemplate jdbcTemplate;

    public CustomerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findName(String customerId) {
        return jdbcTemplate.query("SELECT name FROM customers WHERE customer_id = ?",
                        (rs, i) -> rs.getString("name"), customerId)
                .stream().findFirst();
    }
}
