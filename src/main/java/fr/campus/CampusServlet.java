package fr.campus;

import com.google.gson.JsonParseException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class CampusServlet extends HttpServlet {
    private final BookingStore store;

    CampusServlet(BookingStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (request.getServletPath()) {
            case "/api/planning" -> json(response, 200, Map.of(
                "rooms", BookingStore.ROOMS, "bookings", store.all(), "today", store.today().toString()));
            case "/" -> asset(response, "index.html", "text/html");
            case "/styles.css" -> asset(response, "styles.css", "text/css");
            case "/app.js" -> asset(response, "app.js", "text/javascript");
            case "/favicon.svg" -> asset(response, "favicon.svg", "image/svg+xml");
            default -> response.sendError(404);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"/api/reservations".equals(request.getServletPath())) {
            response.sendError(404);
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            json(response, 415, Map.of("message", "Les réservations doivent être envoyées au format JSON."));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) {
            json(response, 413, Map.of("message", "La réservation est trop longue."));
            return;
        }
        try {
            var input = BookingStore.JSON.fromJson(new String(body, StandardCharsets.UTF_8), BookingStore.Request.class);
            json(response, 201, Map.of("bookings", store.create(input)));
        } catch (JsonParseException failure) {
            json(response, 400, Map.of("message", "La réservation est au mauvais format."));
        } catch (BookingStore.InvalidBooking failure) {
            json(response, failure.status, Map.of("message", failure.getMessage()));
        } catch (IOException failure) {
            log("Échec d’écriture des réservations", failure);
            json(response, 500, Map.of("message", "Impossible d’enregistrer la réservation."));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getServletPath();
        if (!path.matches("/api/reservations/[a-f0-9-]{36}")) {
            response.sendError(404);
            return;
        }
        try {
            store.cancel(path.substring("/api/reservations/".length()), "true".equals(request.getParameter("serie")));
            response.setStatus(204);
        } catch (BookingStore.InvalidBooking failure) {
            json(response, failure.status, Map.of("message", failure.getMessage()));
        } catch (IOException failure) {
            log("Échec d’écriture des réservations", failure);
            json(response, 500, Map.of("message", "Impossible d’annuler la réservation."));
        }
    }

    private static void json(HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(BookingStore.JSON.toJson(value));
    }

    private static void asset(HttpServletResponse response, String name, String type) throws IOException {
        try (var input = CampusServlet.class.getResourceAsStream("/web/" + name)) {
            if (input == null) {
                response.sendError(404);
                return;
            }
            response.setContentType(type + ";charset=UTF-8");
            input.transferTo(response.getOutputStream());
        }
    }
}
