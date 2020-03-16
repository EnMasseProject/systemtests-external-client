package io.enmasse.test;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessagingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingConfiguration.class);
    private String hostname;
    private int port;
    private String username;
    private String password;
    private String[] addresses;

    //messaging-client
    private int linksPerConnection;

    //tenant-client
    private int addressesPerTenant;
    private int sendMessagePeriod; //milliseconds

    public MessagingConfiguration() {
        // empty
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

    public String[] getAddresses() {
        return addresses;
    }

    public int getLinksPerConnection() {
        return linksPerConnection;
    }

    public int getAddressesPerTenant() {
        return addressesPerTenant;
    }

    public int getSendMessagePeriod() {
        return sendMessagePeriod;
    }

    public static MessagingConfiguration fromEnv() {
        log.info("Loading configuration from environment variables");

        var c = new MessagingConfiguration();

        c.hostname = System.getenv("amqp-hostname");
        c.port = Integer.parseInt(System.getenv("amqp-port"));
        c.username = System.getenv("amqp-username");
        c.password = System.getenv("amqp-password");
        c.addresses = System.getenv("amqp-addresses").trim().split(",");

        c.linksPerConnection = Integer.parseInt(System.getenv().getOrDefault("amqp-links-per-conn", "1"));

        c.addressesPerTenant = Integer.parseInt(System.getenv().getOrDefault("amqp-addr-per-tenant", "5"));
        c.sendMessagePeriod = Integer.parseInt(System.getenv().getOrDefault("amqp-send-msg-period", "2000"));

        log.info(c.toString());
        return c;
    }

    @Override
    public String toString() {
        return "MessagingConfiguration [hostname=" + hostname + ", port=" + port + ", username=" + username
                + ", password=" + password + ", addresses=" + Arrays.toString(addresses) + ", linksPerConnection="
                + linksPerConnection + ", addressesPerTenant=" + addressesPerTenant + ", sendMessagePeriod="
                + sendMessagePeriod + "]";
    }

}
