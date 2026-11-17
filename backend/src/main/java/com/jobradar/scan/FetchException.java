package com.jobradar.scan;

/** A request failed for a network or HTTP reason. */
public class FetchException extends RuntimeException {
    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
