# Campus Créneaux

Application de réservation de salles et de laboratoires en français. Le planning affiche les réservations et les créneaux libres de l’espace sélectionné.

## Démarrage

Prérequis : Java 17 ou supérieur et Maven. Depuis le dossier du projet, compilez et lancez l’application :

```shell
mvn package
java -jar target/campus-creneaux-1.0.0.jar
```

Ouvrez ensuite [Campus Créneaux](http://127.0.0.1:8080/). Pour arrêter le serveur, utilisez Ctrl+C.

Le serveur accepte dans l’ordre un port, un chemin de fichier de données et un fuseau horaire. Les valeurs par défaut sont `8080`, `data/reservations.json` et `Africa/Casablanca`. Le serveur écoute sur `127.0.0.1`.

## Démonstration

1. Choisissez **Salle A101**. Le jour du premier lancement, une réservation d’exemple occupe le créneau de 09 h à 10 h 30.
2. Essayez de réserver cette salle de 09 h 30 à 10 h. Le serveur signale le conflit.
3. Réservez un créneau libre avec **Chaque semaine · 3 fois**. Consultez les trois dates dans le planning.
4. Depuis une occurrence, choisissez **Annuler**. La case du formulaire permet d’annuler toute la série.

Le planning propose cinq espaces et commence avec quatre réservations d’exemple. Les créneaux durent au moins 15 minutes, entre 08 h et 18 h. Une réservation peut être répétée jusqu’à 12 fois, les occurrences devant toutes tomber dans l’année qui suit la date du jour.

## Architecture

- **Jakarta Servlet 6.1** sur Tomcat embarqué reçoit les appels HTTP et sert l’interface HTML, CSS et JavaScript.
- **BookingStore** valide les demandes et conserve les réservations dans un fichier JSON. La vérification des conflits et l’enregistrement se font sous le même verrou. Si une occurrence entre en conflit, la série entière est refusée.
- L’écriture utilise un fichier temporaire et un remplacement atomique. Le verrou du fichier empêche deux instances d’utiliser les mêmes données. Les réservations restent disponibles après un redémarrage.
- Le mode local de démonstration partage un seul planning entre les personnes qui utilisent l’application. Il fonctionne sans comptes.

| Méthode | URL | Résultat |
|---|---|---|
| GET | `/api/planning` | Liste des espaces et des réservations |
| POST | `/api/reservations` | Création d’une réservation ou d’une série |
| DELETE | `/api/reservations/{id}?serie=true` | Annulation, avec `serie=true` pour inclure toutes les occurrences |

Exemple de corps JSON pour la création. Remplacez la date par une journée à venir.

```json
{
  "roomId": "B102",
  "date": "2026-12-07",
  "start": "08:00",
  "end": "09:30",
  "title": "TP de Java",
  "organizer": "Équipe informatique",
  "weeks": 3
}
```
