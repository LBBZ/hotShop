package com.real.security.util;

import com.real.common.enums.TokenType;
import com.real.security.entity.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Data
@Component
public class JwtTokenUtil {
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.access-expiration}")
    private Long accessExpiration;
    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration;

    // 生成密钥
    private Key getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
    private String buildToken(Map<String, Object> claims, String subject, Long expiration) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration * 1000))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractToken(String bearerToken) {
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        } else {
            return bearerToken;
        }
    }

    // 生成令牌
    public String generateToken(CustomUserDetails customUserDetails, TokenType tokenType) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tokenType", tokenType);
        return buildToken(claims, customUserDetails.getUsername(), accessExpiration);
    }
    /**
     * @param token 令牌
     * @param userDetails 用户详细信息
     * @return 返回令牌是否合法
     */
    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = getUsernameFromToken(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
    /**
     * @param token 令牌
     * @param tokenType 期望token种类
     * @return 返回Token是否有效
     */
    public Boolean validateToken(String token, TokenType tokenType) {
        final TokenType realTokenType = getTokenTypeFromToken(token);
        return (realTokenType.equals(tokenType)) && !isTokenExpired(token);
    }
    /**
     * @param token 令牌
     * @return 返回token是否过期
     */
    public Boolean isTokenExpired(String token) {
        final Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }

    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    private TokenType getTokenTypeFromToken(String token) {
        return TokenType.strTransitionToEnums(getClaimFromToken(token, claims -> claims.get("tokenType", String.class)));
    }
    private <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }
    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}