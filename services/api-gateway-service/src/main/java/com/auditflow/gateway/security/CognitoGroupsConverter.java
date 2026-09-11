package com.auditflow.gateway.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;

/**
 * Maps a verified Cognito ID token to authorities: everyone is a
 * {@code ROLE_USER}; members of the {@value Roles#OPERATORS_GROUP} group
 * (Cognito lists group names in {@code cognito:groups}) are also
 * {@code ROLE_OPERATOR}. Scopes are deliberately not mapped - an ID token
 * has none, and the API is authorized by role, not by scope.
 */
public class CognitoGroupsConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String GROUPS_CLAIM = "cognito:groups";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList(GROUPS_CLAIM);
        boolean operator = groups != null && groups.contains(Roles.OPERATORS_GROUP);
        return Roles.authorities(operator);
    }
}
