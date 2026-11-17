package com.jobradar.adapter;

import java.util.List;

/**
 * @param partial true when the list may be incomplete, so jobs missing from it must not be marked CLOSED
 * @param note    human-readable detail for a partial result
 */
public record FetchResult(List<RawJob> jobs, boolean partial, String note) {
    public static FetchResult complete(List<RawJob> jobs) {
        return new FetchResult(jobs, false, null);
    }

    public static FetchResult partial(List<RawJob> jobs, String note) {
        return new FetchResult(jobs, true, note);
    }
}
