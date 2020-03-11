/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.test;

import io.prometheus.client.Counter;
import io.prometheus.client.exporter.HTTPServer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.message.Message;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ProbeClient extends AbstractVerticle {
    private final ConnectionConfig connectionConfig;
    private final String address;
    private final long receiveTimeout;

    private ProbeClient(ConnectionConfig connectionConfig, String address, long receiveTimeout) {
        this.connectionConfig = connectionConfig;
        this.address = address;
        this.receiveTimeout = receiveTimeout;
    }

    @Override
    public void start(Future<Void> startPromise) {
        ProtonClientOptions protonClientOptions = new ProtonClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                .setHostnameVerificationAlgorithm("");

        vertx.setTimer(receiveTimeout, id -> {
            if (!startPromise.isComplete()) {
                startPromise.fail("Failed completing probe within timeout");
            }
        });
        ProtonClient client = ProtonClient.create(vertx);
        client.connect(protonClientOptions, connectionConfig.getHostname(), connectionConfig.getPort(), connectionConfig.getUsername(), connectionConfig.getPassword(),
        		connectResult -> {
	            if (connectResult.succeeded()) {
	                ProtonConnection connection = connectResult.result();
	                ProtonReceiver receiver = connection.createReceiver(address);
	                receiver.handler((protonDelivery, message) -> {
	                    startPromise.complete();
	                    connection.close();
	                });
	                receiver.openHandler(receiverAttachResult -> {
	                    if (receiverAttachResult.succeeded()) {
	                        ProtonSender sender = connection.createSender(address);
	                        sender.openHandler(senderAttachResult -> {
	                            if (senderAttachResult.succeeded()) {
	                                Message message = Proton.message();
	                                message.setBody(new AmqpValue("PING"));
	                                sender.send(message);
	                            } else {
	                                startPromise.fail(senderAttachResult.cause());
	                            }
	                        });
	                        sender.open();
	                    } else {
	                        startPromise.fail(receiverAttachResult.cause());
	                    }
	                });
	                receiver.open();
	                connection.open();
	            } else {
	                startPromise.fail(connectResult.cause());
	            }
	        });
    }

    private static final Counter successCounter = Counter.build()
            .name("test_probe_success_total")
            .help("N/A")
            .register();

    private static final Counter failureCounter = Counter.build()
            .name("test_probe_failure_total")
            .help("N/A")
            .register();

    public static void main(String[] args) throws InterruptedException, IOException {

    	MessagingConfiguration config = MessagingConfiguration.fromEnv();

        HTTPServer server = new HTTPServer(8080);

        long receiveTimeout = 30_000;
        Vertx vertx = Vertx.vertx();
        while (true) {
            CountDownLatch completed = new CountDownLatch(config.getAddresses().length);
            for (String address : config.getAddresses()) {
                vertx.deployVerticle(new ProbeClient(new ConnectionConfig(config), address, receiveTimeout), result -> {
                    if (result.succeeded()) {
                        successCounter.inc();
                    } else {
                        failureCounter.inc();
                        System.out.println(result.cause().getMessage());
                        result.cause().printStackTrace();
                    }
                    completed.countDown();
                });
            }
            if (!completed.await(1, TimeUnit.MINUTES)) {
                System.err.println("Probe timed out after 1 minute");
            } else {
                Thread.sleep(10000);
            }
            System.out.println("# Metrics");
            System.out.println("successCounter = " + successCounter.get());
            System.out.println("failureCounter = " + failureCounter.get());
            System.out.println("##########");
        }
    }
}
