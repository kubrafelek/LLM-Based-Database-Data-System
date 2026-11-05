package com.llmdiscovery.discovery;

/** Connection details for a target database. toString() is safe for logs: no credentials. */
public record ConnectionInfo(String host, int port, String database, String username, String password) {

    public String describe() {
        return host + ":" + port + "/" + database;
    }

    @Override
    public String toString() {
        return "ConnectionInfo[" + describe() + ", username=" + username + "]";
    }
}
