package dev.goodrich.althalt.core;

import java.util.Set;

public final class BlockedException extends Exception {
    public final GuardRule rule;
    public final String expectedAccount;
    public final Set<String> addresses;

    public BlockedException(String message) {
        this(GuardRule.OTHER, message, null, Set.of(), null);
    }

    public BlockedException(String message, Throwable cause) {
        this(GuardRule.OTHER, message, null, Set.of(), cause);
    }

    public BlockedException(GuardRule rule, String message, String expectedAccount, Set<String> addresses, Throwable cause) {
        super(message, cause);
        this.rule = rule;
        this.expectedAccount = expectedAccount;
        this.addresses = Set.copyOf(addresses);
    }
}
