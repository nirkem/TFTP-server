package tftp.server.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;

import tftp.common.MessageEncoderDecoder;

/**
 * One client's connection, run on its own thread. The thread only reads; writes can come from
 * any thread (a broadcast runs on the sender's thread), so {@link #send} is synchronized.
 */
public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final int connectionId;
    private final Socket sock;
    private final MessageEncoderDecoder<T> encdec;
    private final BidiMessagingProtocol<T> protocol;
    private final Connections<T> connections;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;

    public BlockingConnectionHandler(int connectionId, Socket sock, MessageEncoderDecoder<T> encdec,
            BidiMessagingProtocol<T> protocol, Connections<T> connections) throws IOException {
        this.connectionId = connectionId;
        this.sock = sock;
        this.encdec = encdec;
        this.protocol = protocol;
        this.connections = connections;
        // Opened here, not in run(), so a broadcast that arrives before this thread starts
        // is written instead of silently dropped.
        this.in = new BufferedInputStream(sock.getInputStream());
        this.out = new BufferedOutputStream(sock.getOutputStream());
    }

    @Override
    public void run() {
        try {
            int read;
            while (!protocol.shouldTerminate() && (read = in.read()) >= 0) {
                T message = encdec.decodeNextByte((byte) read);
                if (message != null)
                    protocol.process(message);
            }
        } catch (IOException ignored) {
            // The client went away. Cleanup is the same as for a clean disconnect.
        } finally {
            protocol.closed();
            connections.disconnect(connectionId);
        }
    }

    @Override
    public synchronized void send(T message) {
        try {
            out.write(encdec.encode(message));
            out.flush();
        } catch (IOException ignored) {
            // The reading thread will see the dead socket and clean up.
        }
    }

    @Override
    public void close() throws IOException {
        sock.close();
    }
}
