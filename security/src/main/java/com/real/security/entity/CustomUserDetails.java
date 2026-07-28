package com.real.security.entity;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.real.security.identity.IdentityType;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

@Data
@NoArgsConstructor
public class CustomUserDetails implements UserDetails {

    private Long userId;
    private String username;
    private String password;
    private Collection<? extends GrantedAuthority> authorities;
    private IdentityType identityType;
    private String tokenId;
    private Instant tokenExpiresAt;
    private String authorizedParty;
    private Set<String> scopes = Set.of();

    public CustomUserDetails(
            Long userId,
            String username,
            String password,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this(userId, username, password, authorities, null, null, null, null, Set.of());
    }

    @Builder
    public CustomUserDetails(
            Long userId,
            String username,
            String password,
            Collection<? extends GrantedAuthority> authorities,
            IdentityType identityType,
            String tokenId,
            Instant tokenExpiresAt,
            String authorizedParty,
            Set<String> scopes
    ) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.authorities = authorities;
        this.identityType = identityType;
        this.tokenId = tokenId;
        this.tokenExpiresAt = tokenExpiresAt;
        this.authorizedParty = authorizedParty;
        this.scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

}
