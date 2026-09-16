package dev.goodrich.althalt.core;

public enum GuardRule {
    UUID_LOCK("UUID lock", true),
    SAME_IP("Same-IP", true),
    IP_LOOKUP("IP lookup unavailable", true),
    OTHER("Safety check", false);

    public final String title;
    public final boolean exemptible;

    GuardRule(String title, boolean exemptible) {
        this.title = title;
        this.exemptible = exemptible;
    }
}
