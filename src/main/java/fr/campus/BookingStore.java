package fr.campus;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class BookingStore implements AutoCloseable {
    record Room(String id, String name, String type, String location, int capacity, String equipment) {}
    record Booking(String id, String roomId, String date, String start, String end,
                   String title, String organizer, String seriesId) {}
    record Request(String roomId, String date, String start, String end, String title, String organizer, int weeks) {}

    static final class InvalidBooking extends RuntimeException {
        final int status;
        InvalidBooking(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final Gson JSON = new Gson();
    static final List<Room> ROOMS = List.of(
        new Room("A101", "Salle A101", "Salle", "Bâtiment A · RDC", 32, "Vidéoprojecteur · Tableau blanc"),
        new Room("A204", "Salle A204", "Salle", "Bâtiment A · 2e étage", 48, "Vidéoprojecteur · Sonorisation"),
        new Room("B102", "Labo informatique", "Laboratoire", "Bâtiment B · 1er étage", 24, "24 postes · Écran interactif"),
        new Room("C001", "Labo de sciences", "Laboratoire", "Bâtiment C · RDC", 20, "Paillasses · Point d’eau"),
        new Room("D010", "Salle D010", "Salle", "Bâtiment D · RDC", 16, "Écran HDMI · Tableau blanc")
    );
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Path data;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private List<Booking> bookings;

    BookingStore(Path data, Clock clock) throws IOException {
        this.data = data;
        this.clock = clock;
        Files.createDirectories(data.getParent());
        lockChannel = FileChannel.open(data.resolveSibling(data.getFileName() + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("Les données sont déjà ouvertes par une autre instance.");
            }
            if (Files.exists(data)) {
                List<Booking> saved = JSON.fromJson(Files.readString(data), new TypeToken<List<Booking>>() {}.getType());
                if (saved == null) {
                    throw new IOException("Fichier de réservations vide ou invalide.");
                }
                bookings = List.copyOf(saved);
            } else {
                String date = today().toString();
                save(List.of(
                    demo("A101", date, "09:00", "10:30", "Mathématiques appliquées", "Équipe de mathématiques"),
                    demo("B102", date, "10:00", "12:00", "TP de programmation", "Équipe informatique"),
                    demo("A204", date, "13:30", "15:00", "Atelier de présentation", "Club débat"),
                    demo("C001", date, "14:30", "16:00", "TP de chimie", "Équipe de sciences")
                ));
            }
        } catch (IOException | RuntimeException failure) {
            lockChannel.close();
            throw failure;
        }
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    synchronized List<Booking> all() {
        return bookings.stream()
            .sorted(Comparator.comparing(Booking::date).thenComparing(Booking::start).thenComparing(Booking::roomId))
            .toList();
    }

    synchronized List<Booking> create(Request request) throws IOException {
        if (request == null || ROOMS.stream().noneMatch(room -> room.id().equals(request.roomId()))) {
            throw invalid("Sélectionnez une salle ou un laboratoire.");
        }
        String title = required(request.title(), 80, "activité");
        String organizer = required(request.organizer(), 60, "responsable");
        LocalDate date;
        LocalTime start;
        LocalTime end;
        try {
            date = LocalDate.parse(request.date() == null ? "" : request.date());
            start = LocalTime.parse(request.start() == null ? "" : request.start());
            end = LocalTime.parse(request.end() == null ? "" : request.end());
        } catch (DateTimeParseException failure) {
            throw invalid("Saisissez une date et des heures valides.");
        }
        if (start.isBefore(LocalTime.of(8, 0)) || end.isAfter(LocalTime.of(18, 0))
                || !start.isBefore(end) || !onQuarterHour(start) || !onQuarterHour(end)) {
            throw invalid("Choisissez un créneau de 8 h à 18 h, par tranches de 15 minutes.");
        }
        int weeks = request.weeks();
        LocalDate today = today();
        if (weeks < 1 || weeks > 12) {
            throw invalid("Choisissez entre 1 et 12 occurrences.");
        }
        LocalDate limit = today.plusYears(1);
        if (date.isBefore(today) || date.isAfter(limit) || date.plusWeeks(weeks - 1L).isAfter(limit)) {
            throw invalid("Toutes les dates doivent se situer dans les 12 prochains mois.");
        }
        String from = start.format(TIME);
        String until = end.format(TIME);
        String seriesId = weeks > 1 ? id() : null;
        List<Booking> additions = new ArrayList<>();
        for (int week = 0; week < weeks; week++) {
            LocalDate day = date.plusWeeks(week);
            String dayText = day.toString();
            boolean overlaps = bookings.stream().anyMatch(existing -> existing.roomId().equals(request.roomId())
                && existing.date().equals(dayText) && from.compareTo(existing.end()) < 0
                && until.compareTo(existing.start()) > 0);
            if (overlaps) {
                throw new InvalidBooking(409, "La salle est déjà réservée le "
                    + day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " pendant ce créneau.");
            }
            additions.add(new Booking(id(), request.roomId(), dayText, from, until, title, organizer, seriesId));
        }
        List<Booking> updated = new ArrayList<>(bookings);
        updated.addAll(additions);
        save(updated);
        return List.copyOf(additions);
    }

    synchronized void cancel(String id, boolean wholeSeries) throws IOException {
        Booking selected = bookings.stream().filter(item -> item.id().equals(id)).findFirst()
            .orElseThrow(() -> new InvalidBooking(404, "Cette réservation a déjà été supprimée."));
        String seriesId = selected.seriesId();
        save(bookings.stream().filter(item -> !item.id().equals(id)
            && !(wholeSeries && seriesId != null && seriesId.equals(item.seriesId()))).toList());
    }

    private void save(List<Booking> updated) throws IOException {
        Path temporary = Files.createTempFile(data.getParent(), "reservations-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(updated));
            Files.move(temporary, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            bookings = List.copyOf(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw invalid("Renseignez le champ « " + field + " » (" + max + " caractères maximum).");
        }
        return value.strip();
    }

    private static boolean onQuarterHour(LocalTime time) {
        return time.getMinute() % 15 == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }

    private static InvalidBooking invalid(String message) {
        return new InvalidBooking(400, message);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Booking demo(String room, String date, String from, String until, String title, String organizer) {
        return new Booking(id(), room, date, from, until, title, organizer, null);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            lockChannel.close();
        }
    }
}
