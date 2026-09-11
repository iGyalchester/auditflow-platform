package com.auditflow.gateway.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The two roles the gateway knows. Every authenticated caller is a
 * {@code USER} of their own tenant; an {@code OPERATOR} additionally sees
 * the platform view ({@code /api/v1/operator/**}) and may act as any
 * customer (see {@link com.auditflow.gateway.controllers.RequestScope}).
 *
 * <p>Where the operator role comes from: in the cloud, membership of the
 * Cognito group {@value #OPERATORS_GROUP} (the {@code cognito:groups}
 * claim); locally, the {@value #DEV_ROLES_HEADER} header, which only the
 * open chain reads.
 */
public final class Roles {

    public static final String USER = "USER";
    public static final String OPERATOR = "OPERATOR";

    public static final String ROLE_USER = "ROLE_" + USER;
    public static final String ROLE_OPERATOR = "ROLE_" + OPERATOR;

    /** The Cognito user-pool group whose members are platform operators. */
    public static final String OPERATORS_GROUP = "operators";
    /** Dev-only: {@code X-Roles: operator} grants the operator role. */
    public static final String DEV_ROLES_HEADER = "X-Roles";

    private Roles() {
    }

    public static List<GrantedAuthority> authorities(boolean operator) {
        return operator
                ? List.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority(ROLE_OPERATOR))
                : List.of(new SimpleGrantedAuthority(ROLE_USER));
    }

    /** {@code ["USER"]} or {@code ["USER", "OPERATOR"]}, without the prefix, for the API. */
    public static Set<String> names(Collection<? extends GrantedAuthority> authorities) {
        Set<String> names = new LinkedHashSet<>();
        for (GrantedAuthority authority : authorities) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith("ROLE_")) {
                names.add(value.substring("ROLE_".length()));
            }
        }
        return names;
    }
}
