package tftp.server.net;

/**
 * The per-client protocol. Unlike a request/response protocol, it does not return a reply:
 * it sends through {@link Connections}, so it can answer its own client and message others.
 */
public interface BidiMessagingProtocol<T> {

    /** Called once, before the first message, with this client's id. */
    void start(int connectionId, Connections<T> connections);

    void process(T message);

    /** True once the client has asked to disconnect. */
    boolean shouldTerminate();

    /** Called once when the connection ends, whether the client said goodbye or just vanished. */
    void closed();
}
