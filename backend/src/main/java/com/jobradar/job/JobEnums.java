package com.jobradar.job;

public final class JobEnums {
    private JobEnums() {}

    public enum Category { FRESHER, EXPERIENCED, UNSPECIFIED }

    public enum WorkMode { REMOTE, HYBRID, ONSITE, UNKNOWN }

    public enum RoleCategory { BACKEND, FRONTEND, FULL_STACK, QA_TESTING, DEVOPS, NETWORK, DATA, OTHER }

    public enum ApplicationStatus { NEW, SEEN, SAVED, APPLIED, INTERVIEW, OFFER, REJECTED, IGNORED }
}
