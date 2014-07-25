/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.support;

import org.apache.commons.codec.binary.Base64;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.shield.authc.AuthenticationException;
import org.elasticsearch.shield.authc.AuthenticationToken;
import org.elasticsearch.transport.TransportRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 */
public class UsernamePasswordToken implements AuthenticationToken {

    private static final String TOKEN_HEADER = "X-ES-UsernamePasswordToken";
    static final String BASIC_AUTH_HEADER = "Authorization";
    private static final Pattern BASIC_AUTH_PATTERN = Pattern.compile("Basic\\s(.+)");

    private final String username;
    private final char[] password;

    public UsernamePasswordToken(String username, char[] password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public String principal() {
        return username;
    }

    @Override
    public char[] credentials() {
        return password;
    }

    public static UsernamePasswordToken extractToken(TransportRequest request, UsernamePasswordToken defaultToken) {
        UsernamePasswordToken token = (UsernamePasswordToken) request.getHeaders().get(TOKEN_HEADER);
        if (token != null) {
            return token;
        }

        String authStr = (String) request.getHeaders().get(BASIC_AUTH_HEADER);
        if (authStr == null) {
            if (defaultToken == null) {
                return null;
            }
            request.putHeader(TOKEN_HEADER, defaultToken);
            return defaultToken;
        }

        Matcher matcher = BASIC_AUTH_PATTERN.matcher(authStr.trim());
        if (!matcher.matches()) {
            throw new AuthenticationException("Invalid basic authentication header value");
        }

        String userpasswd = new String(Base64.decodeBase64(matcher.group(1)), Charsets.UTF_8);
        int i = userpasswd.indexOf(':');
        token = new UsernamePasswordToken(userpasswd.substring(0, i), userpasswd.substring(i+1).toCharArray());
        request.putHeader(TOKEN_HEADER, token);
        return token;
    }

    public static void putTokenHeader(TransportRequest request, UsernamePasswordToken token) {
        String basicToken = token.username + ":" + new String(token.password);
        basicToken = new String(Base64.encodeBase64(basicToken.getBytes(Charsets.UTF_8)), Charsets.UTF_8);
        request.putHeader("Authorization", "Basic " + basicToken);
    }
}
