/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.test;

import io.prometheus.client.Counter;
import io.prometheus.client.exporter.HTTPServer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import org.HdrHistogram.AtomicHistogram;
import org.HdrHistogram.Histogram;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MessagingClient extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(MessagingClient.class);
    private static final Counter connectSuccesses = Counter.build()
            .name("test_connect_success_total")
            .help("N/A")
            .register();

    private static final Counter connectFailures = Counter.build()
            .name("test_connect_failure_total")
            .help("N/A")
            .register();

    private static final Counter disconnects = Counter.build()
            .name("test_disconnects_total")
            .help("N/A")
            .register();

    private static final Counter reconnects = Counter.build()
            .name("test_reconnects_total")
            .help("N/A")
            .register();

    private static final Counter reconnectSuccesses = Counter.build()
            .name("test_reconnect_success_total")
            .help("N/A")
            .register();

    private static final Counter reconnectFailures = Counter.build()
            .name("test_reconnect_failure_total")
            .help("N/A")
            .register();

    private static final Counter attaches = Counter.build()
            .name("test_attaches_total")
            .help("N/A")
            .register();

    private static final Counter detaches = Counter.build()
            .name("test_detaches_total")
            .help("N/A")
            .register();

    private static final Counter reattaches = Counter.build()
            .name("test_reattaches_total")
            .help("N/A")
            .register();

    private static final Map<AddressType, Histogram> reconnectTime = Map.of(
            AddressType.anycast, new AtomicHistogram(Long.MAX_VALUE, 2),
            AddressType.queue, new AtomicHistogram(Long.MAX_VALUE, 2));

    private static final io.prometheus.client.Histogram reconnectHist = io.prometheus.client.Histogram.build()
            .name("test_reconnect_duration")
            .help("N/A")
            .buckets(1.0, 2.5, 7.5, 10.0, 25.0, 50.0, 75.0, 100.0)
            .register();

    private static final Map<AddressType, Histogram> reattachTime = Map.of(
            AddressType.anycast, new AtomicHistogram(Long.MAX_VALUE, 2),
            AddressType.queue, new AtomicHistogram(Long.MAX_VALUE, 2));

    private static final io.prometheus.client.Histogram reattachHist = io.prometheus.client.Histogram.build()
            .name("test_reattach_duration")
            .help("N/A")
            .labelNames("addressType")
            .buckets(1.0, 2.5, 7.5, 10.0, 25.0, 50.0, 75.0, 100.0)
            .register();

    private static final Counter numReceived = Counter.build()
            .name("test_received_total")
            .help("N/A")
            .labelNames("addressType")
            .register();

    private static final Counter numAccepted = Counter.build()
            .name("test_accepted_total")
            .help("N/A")
            .labelNames("addressType")
            .register();

    private static final Counter numRejected = Counter.build()
            .name("test_rejected_total")
            .help("N/A")
            .labelNames("addressType")
            .register();

    private static final Counter numReleased = Counter.build()
            .name("test_released_total")
            .help("N/A")
            .labelNames("addressType")
            .register();

    private static final Counter numModified = Counter.build()
            .name("test_modified_total")
            .help("N/A")
            .labelNames("addressType")
            .register();

    private static final double percentile = 99.0;

    private final ConnectionConfig connectionConfig;

    private final AddressType addressType;
    private final LinkType linkType;
    private final List<String> addresses;
    private final Map<String, AtomicLong> lastDetach = new HashMap<>();
    private final AtomicLong lastDisconnect = new AtomicLong(0);
    private final AtomicLong reconnectDelay = new AtomicLong(1);
    private final Map<String, AtomicLong> reattachDelay = new HashMap<>();

    private MessagingClient(ConnectionConfig config, AddressType addressType, LinkType linkType, List<String> addresses) {
        this.connectionConfig = config;
        this.addressType = addressType;
        this.linkType = linkType;
        this.addresses = new ArrayList<>(addresses);
        for (String address : addresses) {
            lastDetach.put(address, new AtomicLong(0));
            reattachDelay.put(address, new AtomicLong(1));
        }
    }

    @Override
    public void start(Future<Void> startPromise) {
        ProtonClient client = ProtonClient.create(vertx);
        connectAndAttach(client, startPromise);
    }

    private static final long maxRetryDelay = 5000;

    private void connectAndAttach(ProtonClient client, Future<Void> startPromise) {
        ProtonClientOptions protonClientOptions = new ProtonClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                .setHostnameVerificationAlgorithm("");

        Runnable reconnectFn = () -> {
            // System.out.println("Reconnecting in " + reconnectDelay.get() + " ms");
            Context context = vertx.getOrCreateContext();
            vertx.setTimer(reconnectDelay.get(), id -> {
                context.runOnContext(c -> {
                    reconnects.inc();
                    reconnectDelay.set(Math.min(maxRetryDelay, reconnectDelay.get() * 2));
                    connectAndAttach(client, null);
                });
            });
        };

        // System.out.println("Connecting to " + host + ":" + port);
        client.connect(protonClientOptions, connectionConfig.getHostname(), connectionConfig.getPort(), connectionConfig.getUsername(), connectionConfig.getPassword(),
    		connectResult -> {
            if (connectResult.succeeded()) {
                connectSuccesses.inc();
                if (startPromise == null) { //coming from reconnect
                    reconnectSuccesses.inc();
                }
                ProtonConnection connection = connectResult.result();
                connection.openHandler(openResult -> {
                    if (openResult.succeeded()) {
                        if (startPromise != null) {
                            startPromise.complete();
                        }
                        log.info(linkType + " connected to " + connection.getRemoteContainer() + " on " + connectionConfig.getHostname() + ":" + connectionConfig.getPort());

                        // If we've been reconnected. Record how long it took
                        if (lastDisconnect.get() > 0) {
                            long duration = System.nanoTime() - lastDisconnect.get();
                            log.info("Reconnection of " + linkType + " took " + TimeUnit.NANOSECONDS.toMillis(duration) + " ms");
                            reconnectDelay.set(1);
                            reconnectTime.get(addressType).recordValue(TimeUnit.NANOSECONDS.toMillis(duration));
                            reconnectHist.observe(toSeconds(duration));
                        }

                        for (String address : addresses) {
                            attachLink(connection, address);
                        }
                    } else {
                        connectFailures.inc();
                        if (startPromise != null) {
                            startPromise.fail(connectResult.cause());
                        } else { //coming from reconnect
                            reconnectFailures.inc();
                            connection.disconnect();
                        }
                    }
                });

                connection.disconnectHandler(conn -> {
                    disconnects.inc();
                    long now = System.nanoTime();
                    lastDisconnect.set(now);
                    for (String address : addresses) {
                        lastDetach.get(address).set(now);
                    }
                    conn.close();
                    log.debug("Disconnected " + linkType + "!");
                    reconnectFn.run();
                });

                connection.closeHandler(closeResult -> {
                    disconnects.inc();
                    long now = System.nanoTime();
                    lastDisconnect.set(now);
                    for (String address : addresses) {
                        lastDetach.get(address).set(now);
                    }
                    log.debug("Closed " + linkType + "!");
                    reconnectFn.run();
                });

                connection.open();

            } else {
                connectFailures.inc();
                if (startPromise != null) {
                    startPromise.fail(connectResult.cause());
                } else { //coming from reconnect
                    reconnectFailures.inc();
                    reconnectFn.run();
                }
            }
        });
    }

    private static double toSeconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private void attachLink(ProtonConnection connection, String address) {
        Runnable reattachFn = () -> {
            log.info("Reattaching " + linkType + " to " + address + " in " + reattachDelay.get(address).get() + " ms");
            Context context = vertx.getOrCreateContext();
            vertx.setTimer(reattachDelay.get(address).get(), handler -> {
                context.runOnContext(c -> {
                    reattaches.inc();
                    reattachDelay.get(address).set(Math.min(maxRetryDelay, reattachDelay.get(address).get() * 2));
                    attachLink(connection, address);
                });
            });
        };

        Runnable handleAttachFn = () -> {
            // System.out.println("Attached " + linkType + " to " + address);
            attaches.inc();
            // We've been reattached. Record how long it took
            if (lastDetach.get(address).get() > 0) {
                long duration = System.nanoTime() - lastDetach.get(address).get();
                log.info("Reattach of " + linkType + " to " + address + " took " + TimeUnit.NANOSECONDS.toMillis(duration) + " ms");
                reattachDelay.get(address).set(1);
                reattachTime.get(addressType).recordValue(TimeUnit.NANOSECONDS.toMillis(duration));
                reattachHist.labels(addressType.name()).observe(toSeconds(duration));
            }
        };

        // System.out.println("Attaching " + linkType + " to " + address);

        if (linkType.equals(LinkType.receiver)) {
            ProtonReceiver receiver = connection.createReceiver(address);
            receiver.openHandler(receiverAttachResult -> {
                if (receiverAttachResult.succeeded()) {
                    handleAttachFn.run();
                } else {
                    reattachFn.run();
                }
            });
            receiver.detachHandler(detachResult -> {
                log.debug("Detached " + linkType + " for " + address + "!");
                detaches.inc();
                lastDetach.get(address).set(System.nanoTime());
                reattachFn.run();
            });
            receiver.closeHandler(closeResult -> {
                log.debug("Closed " + linkType + " for " + address + "!");
                detaches.inc();
                lastDetach.get(address).set(System.nanoTime());
                reattachFn.run();
            });
            receiver.handler((protonDelivery, message) -> {
                //System.out.println("Received message from " + address);
                numReceived.labels(addressType.name()).inc();
            });
            receiver.open();
        } else {
            ProtonSender sender = connection.createSender(address);
            sender.openHandler(senderAttachResult -> {
                if (senderAttachResult.succeeded()) {
                    handleAttachFn.run();
                    sendMessage(address, sender);
                } else {
                    reattachFn.run();
                }
            });
            sender.detachHandler(detachResult -> {
                log.debug("Detached " + linkType + " for " + address + "!");
                detaches.inc();
                lastDetach.get(address).set(System.nanoTime());
                reattachFn.run();
            });
            sender.closeHandler(closeResult -> {
                log.debug("Closed " + linkType + " for " + address + "!");
                detaches.inc();
                lastDetach.get(address).set(System.nanoTime());
                reattachFn.run();
            });
            sender.open();
        }
    }

    private void sendMessage(String address, ProtonSender sender) {
        Message message = Proton.message();
        message.setBody(new AmqpValue("HELLO"));
        message.setAddress(address);
        if (addressType.equals(AddressType.queue)) {
            message.setDurable(true);
        }
        //System.out.println("Sending message to " + address);
        Context context = vertx.getOrCreateContext();
        sender.send(message, delivery -> {
            switch (delivery.getRemoteState().getType()) {
                case Accepted:
                    numAccepted.labels(addressType.name()).inc();
                    break;
                case Rejected:
                    numRejected.labels(addressType.name()).inc();
                    break;
                case Modified:
                    numModified.labels(addressType.name()).inc();
                    break;
                case Released:
                    numReleased.labels(addressType.name()).inc();
                    break;
            }
            if (delivery.remotelySettled()) {
                vertx.setTimer(2000, id -> {
                    context.runOnContext(c -> {
                        sendMessage(address, sender);
                    });
                });
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, IOException {

    	MessagingConfiguration config = MessagingConfiguration.fromEnv();

    	List<String> anycastAddresses = Stream.of(config.getAddresses())
    			.filter(a -> a.toLowerCase().contains("anycast"))
    			.collect(Collectors.toList());

    	List<String> queueAddresses = Stream.of(config.getAddresses())
    			.filter(a -> a.toLowerCase().contains("queue"))
    			.collect(Collectors.toList());

        HTTPServer server = new HTTPServer(8080);

        Vertx vertx = Vertx.vertx();

        deployClients(vertx, new ConnectionConfig(config), AddressType.anycast, config.getLinksPerConnection(), anycastAddresses);
        deployClients(vertx, new ConnectionConfig(config), AddressType.queue, config.getLinksPerConnection(), queueAddresses);

        while (true) {
            Thread.sleep(30000);
            try {
                log.info("# Metrics");
                log.info("Successful connects = " + connectSuccesses.get());
                log.info("Failed connects = " + connectFailures.get());
                log.info("Disconnects = " + disconnects.get());
                log.info("Reconnects = " + reconnects.get());
                log.info("Successful reconnects = " + reconnectSuccesses.get());
                log.info("Failed reconnects = " + reconnectFailures.get());
                log.info("Reconnect duration (anycast) 99p = " + reconnectTime.get(AddressType.anycast).getValueAtPercentile(percentile));
                log.info("Reconnect duration (queue) 99p = " + reconnectTime.get(AddressType.queue).getValueAtPercentile(percentile));
                log.info("Reattach duration (anycast) 99p = " + reconnectTime.get(AddressType.anycast).getValueAtPercentile(percentile));
                log.info("Reattach duration (queue) 99p = " + reconnectTime.get(AddressType.queue).getValueAtPercentile(percentile));
                log.info("Num accepted anycast = " + numAccepted.labels(AddressType.anycast.name()).get());
                log.info("Num received anycast = " + numReceived.labels(AddressType.anycast.name()).get());
                log.info("Num rejected anycast = " + numRejected.labels(AddressType.anycast.name()).get());
                log.info("Num modified anycast = " + numModified.labels(AddressType.anycast.name()).get());
                log.info("Num released anycast = " + numReleased.labels(AddressType.anycast.name()).get());
                log.info("Num accepted queue = " + numAccepted.labels(AddressType.queue.name()).get());
                log.info("Num received queue = " + numReceived.labels(AddressType.queue.name()).get());
                log.info("Num rejected queue = " + numRejected.labels(AddressType.queue.name()).get());
                log.info("Num modified queue = " + numModified.labels(AddressType.queue.name()).get());
                log.info("Num released queue = " + numReleased.labels(AddressType.queue.name()).get());
                log.info("##########");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void deployClients(Vertx vertx, ConnectionConfig config, AddressType addressType, int linksPerConnection, List<String> addresses) throws InterruptedException {
        List<List<String>> groups = new ArrayList<>();
        for (int i = 0; i < addresses.size() / linksPerConnection; i++) {
            groups.add(addresses.subList(i * linksPerConnection, (i + 1) * linksPerConnection));
        }

        for (List<String> addressList : groups) {

            vertx.deployVerticle(new MessagingClient(config, addressType, LinkType.receiver, addressList), result -> {
                if (result.succeeded()) {
                    log.info("Started receiver client for addresses " + addressList);
                } else {
                    log.warn("Failed starting receiver client for addresses " + addressList);
                }
            });

            Thread.sleep(10);

            vertx.deployVerticle(new MessagingClient(config, addressType, LinkType.sender, addressList), result -> {
                if (result.succeeded()) {
                    log.info("Started sender client for addresses " + addressList);
                } else {
                    log.warn("Failed starting sender client for addresses " + addressList);
                }
            });
        }

    }
}
