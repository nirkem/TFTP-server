package tftp.server.net;

import java.io.Closeable;

public interface ConnectionHandler<T> extends Closeable {

    /** Safe to call from any thread. */
    void send(T message);
}
