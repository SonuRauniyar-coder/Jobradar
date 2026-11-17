package com.jobradar.job;

/** Lets tests in other packages reach the package-private formula guard. */
public final class JobExporterAccess {
    private JobExporterAccess() {}

    public static String safe(String value) {
        return JobExporter.safe(value);
    }
}
