package tftp.server.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import tftp.common.MessageEncoderDecoder;

/** A TCP server that gives every client its own thread, protocol and encoder/decoder. */
public class Server<T> implements Closeable {

    private final int port;
    private final Supplier<BidiMessagingProtocol<T>> protocolFactory;
    private final Supplier<MessageEncoderDecoder<T>> encdecFactory;
    private final Connections<T> connections = new ConnectionsImpl<>();
    private final CountDownLatch listening = new CountDownLatch(1);
    private volatile ServerSocket sock;
    private int nextId = 0;

    /** Port 0 picks a free port; {@link #awaitPort()} tells you which. */
    public Server(int port, Supplier<BidiMessagingProtocol<T>> protocolFactory,
            Supplier<MessageEncoderDecoder<T>> encdecFactory) {
        this.port = port;
        this.protocolFactory = protocolFactory;
        this.encdecFactory = encdecFactory;
    }

    /** Accepts clients until {@link #close()} is called. */
    public void serve() throws IOException {
        try (ServerSocket serverSock = new ServerSocket(port)) {
            sock = serverSock;
            listening.countDown();

            while (!serverSock.isClosed()) {
                Socket clientSock;
                try {
                    clientSock = serverSock.accept();
                } catch (IOException closed) {
                    break;
                }
                int id = nextId++;
                BidiMessagingProtocol<T> protocol = protocolFactory.get();
                BlockingConnectionHandler<T> handler = new BlockingConnectionHandler<>(
                        id, clientSock, encdecFactory.get(), protocol, connections);
                connections.connect(id, handler);
                protocol.start(id, connections);
                new Thread(handler, "client-" + id).start();
            }
        }
    }

    public int awaitPort() throws InterruptedException {
        listening.await();
        return sock.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        if (sock != null)
            sock.close();
    }
}
