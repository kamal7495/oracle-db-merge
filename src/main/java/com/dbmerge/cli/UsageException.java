package com.dbmerge.cli;

/** Bad command-line usage; exits with {@link ExitCodes#USAGE}. The message is shown to the user. */
public class UsageException extends RuntimeException {
    public UsageException(String message) {
        super(message);
    }
}
