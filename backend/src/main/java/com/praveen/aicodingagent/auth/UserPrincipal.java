package com.praveen.aicodingagent.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Adapts our User entity to what Spring Security's filter chain expects.
 * This is the object that ends up as the request's Authentication
 * principal, and what @AuthenticationPrincipal resolves to in controllers.
 *
 * Single ROLE_USER for V1 - no admin/role hierarchy yet. Wrapping in a real
 * GrantedAuthority now (not just leaving it out) means adding roles later
 * is additive, not a rework of every @PreAuthorize call site.
 */
public class UserPrincipal implements UserDetails {

    private final User user;

    public UserPrincipal(User user) {
        this.user = user;
    }

    public UUID getId() {
        return user.getId();
    }

    public String getDisplayName() {
        return user.getDisplayName();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
