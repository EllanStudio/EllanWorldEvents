package com.ellanstudio.worldevents;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/** Small Redis RESP client so the plugin does not need another runtime dependency. */
final class RedisBroadcaster implements AutoCloseable {
    private final EllanWorldEventsPlugin plugin;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final String channel;
    private final Consumer<String> messageConsumer;
    private volatile boolean running;
    private volatile Socket subscriptionSocket;
    private Thread listenerThread;
    private boolean connectionWarningLogged;

    RedisBroadcaster(EllanWorldEventsPlugin plugin, String host, int port, String password,
                     int database, String channel, Consumer<String> messageConsumer) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.channel = channel;
        this.messageConsumer = messageConsumer;
    }

    void start() {
        running = true;
        listenerThread = new Thread(this::listenLoop, "EllanWorldEvents-RedisSubscriber");
        listenerThread.setDaemon(true);
        listenerThread.start();
        plugin.getLogger().info("Redis cross-server announcements enabled on " + host + ":" + port + ".");
    }

    void publishAsync(String message) {
        Thread publisher = new Thread(() -> {
            try {
                int subscribers = publish(message);
                if (subscribers <= 0) {
                    throw new IOException("Redis has no active announcement subscribers");
                }
                connectionWarningLogged = false;
                plugin.getLogger().fine("Published cross-server announcement to " + subscribers + " Redis subscriber(s).");
            } catch (Exception exception) {
                if (!connectionWarningLogged) {
                    connectionWarningLogged = true;
                    plugin.getLogger().warning("Redis announcement failed; showing the message locally only: "
                            + exception.getMessage());
                }
                plugin.runOnMainThread(() -> plugin.broadcastLocal(message));
            }
        }, "EllanWorldEvents-RedisPublisher");
        publisher.setDaemon(true);
        publisher.start();
    }

    private int publish(String message) throws IOException {
        try (Socket socket = openSocket()) {
            configure(socket);
            try (OutputStream output = socket.getOutputStream(); InputStream input = socket.getInputStream()) {
                authenticateAndSelect(output, input);
                writeCommand(output, "PUBLISH", channel, message);
                Object response = readResponse(input);
                if (!(response instanceof Long count)) {
                    throw new IOException("Unexpected Redis PUBLISH response: " + response);
                }
                return Math.toIntExact(count);
            }
        }
    }

    private void listenLoop() {
        while (running) {
            try (Socket socket = openSocket()) {
                subscriptionSocket = socket;
                configure(socket);
                try (OutputStream output = socket.getOutputStream(); InputStream input = socket.getInputStream()) {
                    authenticateAndSelect(output, input);
                    writeCommand(output, "SUBSCRIBE", channel);
                    readResponse(input);
                    connectionWarningLogged = false;
                    while (running) {
                        Object response = readResponse(input);
                        if (!(response instanceof List<?> values) || values.size() < 3) {
                            continue;
                        }
                        if ("message".equals(values.get(0)) && values.get(2) instanceof String message) {
                            messageConsumer.accept(message);
                        }
                    }
                }
            } catch (Exception exception) {
                if (running && !connectionWarningLogged) {
                    connectionWarningLogged = true;
                    plugin.getLogger().warning("Redis subscriber disconnected; retrying: " + exception.getMessage());
                }
                closeSubscriptionSocket();
                if (running) {
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                subscriptionSocket = null;
            }
        }
    }

    private Socket openSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 2000);
        socket.setKeepAlive(true);
        socket.setSoTimeout(0);
        return socket;
    }

    private void configure(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
    }

    private void authenticateAndSelect(OutputStream output, InputStream input) throws IOException {
        if (password != null && !password.isBlank()) {
            writeCommand(output, "AUTH", password);
            expectSimpleOk(readResponse(input));
        }
        if (database != 0) {
            writeCommand(output, "SELECT", Integer.toString(database));
            expectSimpleOk(readResponse(input));
        }
    }

    private void expectSimpleOk(Object response) throws IOException {
        if (!(response instanceof String value) || !"OK".equalsIgnoreCase(value)) {
            throw new IOException("Redis command failed: " + response);
        }
    }

    private static void writeCommand(OutputStream output, String... arguments) throws IOException {
        output.write(('*' + Integer.toString(arguments.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            output.write(('$' + Integer.toString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private static Object readResponse(InputStream input) throws IOException {
        int type = input.read();
        if (type < 0) {
            throw new EOFException("Redis connection closed");
        }
        return switch (type) {
            case '+' -> readLine(input);
            case '-' -> throw new IOException("Redis error: " + readLine(input));
            case ':' -> Long.parseLong(readLine(input));
            case '$' -> readBulkString(input);
            case '*' -> readArray(input);
            default -> throw new IOException("Unknown Redis response type: " + (char) type);
        };
    }

    private static Object readBulkString(InputStream input) throws IOException {
        int length = Integer.parseInt(readLine(input));
        if (length < 0) {
            return null;
        }
        byte[] data = input.readNBytes(length);
        if (data.length != length || input.read() != '\r' || input.read() != '\n') {
            throw new EOFException("Incomplete Redis bulk string");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static List<Object> readArray(InputStream input) throws IOException {
        int length = Integer.parseInt(readLine(input));
        if (length < 0) {
            return List.of();
        }
        java.util.ArrayList<Object> values = new java.util.ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(readResponse(input));
        }
        return values;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("Redis connection closed while reading a line");
            }
            if (previous == '\r' && current == '\n') {
                byte[] data = output.toByteArray();
                return new String(data, 0, data.length - 1, StandardCharsets.UTF_8);
            }
            output.write(current);
            previous = current;
        }
    }

    private void closeSubscriptionSocket() {
        Socket socket = subscriptionSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The listener is already reconnecting or shutting down.
            }
        }
    }

    @Override
    public void close() {
        running = false;
        closeSubscriptionSocket();
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }
}
