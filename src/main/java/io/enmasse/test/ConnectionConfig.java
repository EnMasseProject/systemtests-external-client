package io.enmasse.test;

public class ConnectionConfig {

    private String hostname;
    private int port;
    private String username;
    private String password;

    public ConnectionConfig() {
		// empty
	}

    public ConnectionConfig(MessagingConfiguration m) {
    	this.hostname = m.getHostname();
    	this.port = m.getPort();
    	this.username = m.getUsername();
    	this.password = m.getPassword();
    }

	public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

}
