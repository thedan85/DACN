package com.project.dacn.common.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException() {
        super("The account does not exist");
    }
}
