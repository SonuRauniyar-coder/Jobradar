package com.jobradar.scan;

/** The site refused us (robots.txt, 401, 403 or persistent 429). Reported as BLOCKED rather than FAILED. */
public class BlockedException extends FetchException {
    public BlockedException(String message) {
        super(message);
    }
}
