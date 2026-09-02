package com.auditflow.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * A stand-in for a Cognito user pool: an RSA signing key, a tiny HTTP
 * server publishing its public half as a JWKS document, and helpers that
 * mint ID tokens the way Cognito does. Tests sign with the private key;
 * the gateway verifies against the served JWKS - the real code path.
 */
final class TestJwks {

    static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TESTPOOL";
    static final String CLIENT_ID = "1example23456789";

    private static final RSAKey KEY;
    private static final HttpServer SERVER;
    static final String JWKS_URI;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
            String jwks = new JWKSet(KEY.toPublicJWK()).toString();
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            SERVER.start();
            JWKS_URI = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/.well-known/jwks.json";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TestJwks() {
    }

    static JWTClaimsSet.Builder idTokenClaims(String customerId) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .subject("user-42")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("token_use", "id")
                .claim("custom:customer_id", customerId);
    }

    static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY.toPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String signWithUnknownKey(JWTClaimsSet claims) {
        try {
            RSAKey rogue = new RSAKeyGenerator(2048).keyID("not-in-jwks").generate();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rogue.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(rogue.toPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }
}
