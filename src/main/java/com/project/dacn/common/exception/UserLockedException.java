package com.project.dacn.common.exception;

public class UserLockedException extends RuntimeException {
    public UserLockedException() {
        super("User account is locked");
    }
}
