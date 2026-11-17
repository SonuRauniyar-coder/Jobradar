package com.jobradar.adapter;

import com.jobradar.company.SourceType;

public interface JobSourceAdapter {
    SourceType type();

    FetchResult fetch(ScanTarget target);

    /** True if this ATS has a board with at least one job under the slug. Adapters that cannot be guessed return false. */
    default boolean probe(String slug) {
        return false;
    }
}
