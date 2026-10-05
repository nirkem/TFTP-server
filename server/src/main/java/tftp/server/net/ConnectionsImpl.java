package tftp.server.net;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {

    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> handlers = new ConcurrentHashMap<>();

    @Override
    public void connect(int connectionId, ConnectionHandler<T> handler) {
        handlers.put(connectionId, handler);
    }

    @Override
    public boolean send(int connectionId, T message) {
        ConnectionHandler<T> handler = handlers.get(connectionId);
        if (handler == null)
            return false;
        handler.send(message);
        return true;
    }

    @Override
    public void disconnect(int connectionId) {
        ConnectionHandler<T> handler = handlers.remove(connectionId);
        if (handler == null)
            return;
        try {
            handler.close();
        } catch (IOException ignored) {
        }
    }
}
