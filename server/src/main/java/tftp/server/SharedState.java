package tftp.server;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every client thread shares: who is logged in and which file names are being uploaded.
 * Each check-and-claim is a single atomic map operation, so two clients can never both win
 * the same user name or the same file name.
 */
final class SharedState {

    final Path files;
    private final ConcurrentHashMap<String, Integer> users = new ConcurrentHashMap<>();
    private final Set<String> uploading = ConcurrentHashMap.newKeySet();

    SharedState(Path files) {
        this.files = files;
    }

    /** Claims a user name. False if someone else is connected with it. */
    boolean login(String name, int connectionId) {
        return users.putIfAbsent(name, connectionId) == null;
    }

    void logout(String name) {
        users.remove(name);
    }

    Collection<Integer> loggedIn() {
        return users.values();
    }

    /** Claims a file name for an upload. False if another upload already holds it. */
    boolean reserve(String fileName) {
        return uploading.add(fileName);
    }

    void release(String fileName) {
        uploading.remove(fileName);
    }
}
