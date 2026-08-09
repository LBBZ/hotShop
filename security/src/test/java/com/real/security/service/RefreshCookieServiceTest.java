package com.real.security.service;

import com.real.security.config.SecurityProperties;
import com.real.security.identity.SessionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshCookieServiceTest {
    private final RefreshCookieService cookies = new RefreshCookieService(new SecurityProperties());

    @Test
    void userRefreshIsHttpOnlyAndAuthScopedWhileCsrfIsReadableFromBusinessPages() {
        var refresh = cookies.refreshCookie(SessionType.USER, "refresh-value");
        var csrf = cookies.csrfCookie(SessionType.USER, "csrf-value");

        assertThat(refresh.getName()).isEqualTo("hotshop_user_refresh");
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/v1/auth");
        assertThat(csrf.getName()).isEqualTo("hotshop_user_csrf");
        assertThat(csrf.isHttpOnly()).isFalse();
        assertThat(csrf.getPath()).isEqualTo("/");
    }

    @Test
    void administratorCookiesUseDistinctNamesAndAdminOnlyScopes() {
        var refresh = cookies.refreshCookie(SessionType.ADMIN, "refresh-value");
        var csrf = cookies.csrfCookie(SessionType.ADMIN, "csrf-value");

        assertThat(refresh.getName()).isEqualTo("hotshop_admin_refresh");
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/admin/api/v1/auth");
        assertThat(csrf.getName()).isEqualTo("hotshop_admin_csrf");
        assertThat(csrf.isHttpOnly()).isFalse();
        assertThat(csrf.getPath()).isEqualTo("/admin");
    }

    @Test
    void clearingCookiesUsesTheSamePathsAsIssuingThem() {
        assertThat(cookies.clearCookies(SessionType.USER))
                .extracting(cookie -> cookie.getName() + "@" + cookie.getPath())
                .containsExactly(
                        "hotshop_user_refresh@/api/v1/auth",
                        "hotshop_user_csrf@/",
                        "hotshop_user_csrf@/api/v1/auth"
                );
        assertThat(cookies.clearCookies(SessionType.ADMIN))
                .extracting(cookie -> cookie.getName() + "@" + cookie.getPath())
                .containsExactly(
                        "hotshop_admin_refresh@/admin/api/v1/auth",
                        "hotshop_admin_csrf@/admin",
                        "hotshop_admin_csrf@/admin/api/v1/auth"
                );
    }

    @Test
    void legacyCsrfCookiesAreExpiredAtTheirFormerAuthenticationPaths() {
        var userLegacy = cookies.legacyCsrfCookie(SessionType.USER);
        var adminLegacy = cookies.legacyCsrfCookie(SessionType.ADMIN);

        assertThat(userLegacy.getName()).isEqualTo("hotshop_user_csrf");
        assertThat(userLegacy.getPath()).isEqualTo("/api/v1/auth");
        assertThat(userLegacy.getMaxAge()).isZero();
        assertThat(userLegacy.isHttpOnly()).isFalse();
        assertThat(adminLegacy.getName()).isEqualTo("hotshop_admin_csrf");
        assertThat(adminLegacy.getPath()).isEqualTo("/admin/api/v1/auth");
        assertThat(adminLegacy.getMaxAge()).isZero();
        assertThat(adminLegacy.isHttpOnly()).isFalse();
    }
}
