package com.jobradar.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlUtilTest {
    @Test
    void addsSchemeAndKeepsOrdinaryUrls() {
        assertThat(UrlUtil.normalize("example.com/careers")).isEqualTo("https://example.com/careers");
        assertThat(UrlUtil.normalize("https://jobs.example.com/a?b=1#c")).isEqualTo("https://jobs.example.com/a?b=1#c");
        assertThat(UrlUtil.normalize("https://example.com/a%20b")).isEqualTo("https://example.com/a%20b");
        assertThat(UrlUtil.normalize("   ")).isNull();
    }

    @Test
    void encodesCharactersThatAreNotAllowedInUrls() {
        assertThat(UrlUtil.normalize("https://example.com/jobs?team=a|b")).isEqualTo("https://example.com/jobs?team=a%7Cb");
        assertThat(UrlUtil.normalize("https://example.com/open roles")).isEqualTo("https://example.com/open%20roles");
        assertThat(UrlUtil.normalize("https://example.com/karriere/über")).isEqualTo("https://example.com/karriere/%C3%BCber");
    }

    @Test
    void convertsInternationalHostNames() {
        assertThat(UrlUtil.normalize("https://bücher.example/jobs")).isEqualTo("https://xn--bcher-kva.example/jobs");
    }

    @Test
    void explainsUnsupportedUnderscoreHosts() {
        assertThatThrownBy(() -> UrlUtil.normalize("https://my_co.example.com"))
                .hasMessageContaining("\"_\" are not supported");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "ftp://example.com", "https://localhost", "https://"})
    void rejectsInvalidUrls(String url) {
        assertThatThrownBy(() -> UrlUtil.normalize(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lengthIsCheckedAfterTheSchemeIsAdded() {
        String almost = "example.com/" + "a".repeat(UrlUtil.MAX_LENGTH - "example.com/".length());
        assertThat(almost).hasSize(UrlUtil.MAX_LENGTH);
        assertThatThrownBy(() -> UrlUtil.normalize(almost)).hasMessageContaining("longer than 1000");
    }

    @Test
    void recognisesOnlyHttpLinks() {
        assertThat(UrlUtil.isHttpUrl("https://a.com/x")).isTrue();
        assertThat(UrlUtil.isHttpUrl(" HTTP://a.com")).isTrue();
        assertThat(UrlUtil.isHttpUrl("javascript:alert(1)")).isFalse();
        assertThat(UrlUtil.isHttpUrl("data:text/html,x")).isFalse();
        assertThat(UrlUtil.isHttpUrl(null)).isFalse();
    }
}
