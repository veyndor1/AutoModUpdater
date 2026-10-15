import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;

/** Local fixture server for production-Forge acceptance. No user files or credentials served. */
class AcceptanceServer {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Path file = root.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] bytes = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        Files.writeString(root.resolve("port.txt"), Integer.toString(server.getAddress().getPort()));
        Thread.currentThread().join();
    }
}
