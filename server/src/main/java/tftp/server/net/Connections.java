package tftp.server.net;

/** Every open connection, by id. Lets a protocol send to any client, not just its own. */
public interface Connections<T> {

    void connect(int connectionId, ConnectionHandler<T> handler);

    /** Returns false if that client is no longer connected. */
    boolean send(int connectionId, T message);

    void disconnect(int connectionId);
}
