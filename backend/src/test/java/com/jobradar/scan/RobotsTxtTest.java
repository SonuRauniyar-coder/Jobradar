package com.jobradar.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsTxtTest {
    private static RobotsTxt parse(String content) {
        return RobotsTxt.parse(content, "JobRadar");
    }

    @Test
    void wildcardGroupApplies() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /private\n");
        assertThat(r.isAllowed("/private/x")).isFalse();
        assertThat(r.isAllowed("/careers")).isTrue();
    }

    @Test
    void ourGroupReplacesWildcardGroup() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /\n\nUser-agent: jobradar\nDisallow: /admin\n");
        assertThat(r.isAllowed("/careers")).isTrue();
        assertThat(r.isAllowed("/admin")).isFalse();
    }

    @Test
    void longestMatchWinsAndAllowWinsTies() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /jobs\nAllow: /jobs/open\n");
        assertThat(r.isAllowed("/jobs/open/1")).isTrue();
        assertThat(r.isAllowed("/jobs/closed")).isFalse();
    }

    @Test
    void emptyDisallowAllowsEverything() {
        assertThat(parse("User-agent: *\nDisallow:\n").isAllowed("/anything")).isTrue();
    }

    @Test
    void supportsWildcardAndEndAnchor() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /*.pdf$\nDisallow: /a/*/b\n");
        assertThat(r.isAllowed("/x/file.pdf")).isFalse();
        assertThat(r.isAllowed("/x/file.pdf?y=1")).isTrue();
        assertThat(r.isAllowed("/a/1/b/c")).isFalse();
    }

    @Test
    void groupedAgentsShareRules() {
        RobotsTxt r = parse("User-agent: other\nUser-agent: *\nDisallow: /x\n");
        assertThat(r.isAllowed("/x")).isFalse();
    }

    @Test
    void ignoresCommentsAndOtherGroups() {
        RobotsTxt r = parse("# hello\nUser-agent: googlebot\nDisallow: /\n\nUser-agent: *\nDisallow: /y # note\n");
        assertThat(r.isAllowed("/careers")).isTrue();
        assertThat(r.isAllowed("/y")).isFalse();
    }
}
