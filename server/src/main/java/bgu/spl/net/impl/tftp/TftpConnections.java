package bgu.spl.net.impl.tftp;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.srv.BlockingConnectionHandler;

public class TftpConnections<T> implements Connections<T> {
    
    private final ConcurrentHashMap<Integer, BlockingConnectionHandler<T>> activeClients = new ConcurrentHashMap<>();

    @Override
    public void connect(int connectionId, ConnectionHandler<T> handler) {
        activeClients.put(connectionId, (BlockingConnectionHandler<T>) handler);
    }

    @Override
    public synchronized boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = activeClients.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;
    }

    // talya
    public synchronized boolean isAlradyLog(String name, Integer id) {
        if (name.equals("")) {
            if (activeClients.get(id).getName() == name)
                return true;
        }
        return false;
    }

    public synchronized boolean isExistName(String name) {
        for (BlockingConnectionHandler<T> handler : activeClients.values()) {
            if (handler.getName().equals(name))
                return true;
        }
        return false;
    }

    public synchronized void setName(String name, Integer id) {
        activeClients.get(id).setName(name);
    }

    @Override
    public synchronized void disconnect(int connectionId) { 
        try{
            activeClients.get(connectionId).close();
            activeClients.remove(connectionId);
        }catch (IOException ignored){}
    }

    public synchronized ConcurrentHashMap<Integer, BlockingConnectionHandler<T>> getHandler() {
        // to check if neded to send clone or refreancde
        return activeClients;
    }

    public Set<Integer> getIds ()
    {
        return activeClients.keySet();
    }
}