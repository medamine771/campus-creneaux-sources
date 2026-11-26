package fr.campus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import org.apache.catalina.startup.Tomcat;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path data = Path.of(args.length > 1 ? args[1] : "data/reservations.json").toAbsolutePath();
        ZoneId zone = ZoneId.of(args.length > 2 ? args[2] : "Africa/Casablanca");
        try (BookingStore store = new BookingStore(data, Clock.system(zone))) {
            Tomcat server = new Tomcat();
            Path base = Files.createDirectories(data.getParent().resolve("tomcat"));
            server.setBaseDir(base.toString());
            server.setPort(port);
            server.getConnector().setProperty("address", "127.0.0.1");
            var context = server.addContext("", base.toString());
            Tomcat.addServlet(context, "campus", new CampusServlet(store));
            context.addServletMappingDecoded("/", "campus");
            try {
                server.start();
                if (!server.getConnector().getState().isAvailable()) {
                    throw new IllegalStateException("Le serveur HTTP n’a pas pu démarrer sur le port " + port);
                }
                System.out.printf("Campus Créneaux : http://127.0.0.1:%d/%n", server.getConnector().getLocalPort());
                server.getServer().await();
            } finally {
                server.stop();
                server.destroy();
            }
        }
    }
}
