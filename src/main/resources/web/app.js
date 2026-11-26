"use strict";

const $ = (selector) => document.querySelector(selector);
const bookingDialog = $("#booking-dialog");
const bookingForm = $("#booking-form");
const cancelDialog = $("#cancel-dialog");
const cancelForm = $("#cancel-form");
const dayFormat = new Intl.DateTimeFormat("fr-FR", { weekday: "long", day: "numeric", month: "long", year: "numeric", timeZone: "UTC" });
const weekdayFormat = new Intl.DateTimeFormat("fr-FR", { weekday: "short", timeZone: "UTC" });
const state = { rooms: [], bookings: [], today: "", date: "", type: "", roomId: "", cancellation: null };

function calendarDate(value) {
  return new Date(value + "T00:00:00Z");
}

function shift(date, days) {
  const result = calendarDate(date);
  result.setUTCDate(result.getUTCDate() + days);
  return result.toISOString().slice(0, 10);
}

function showMessage(element, message, failed = false) {
  element.textContent = message;
  element.hidden = !message;
  element.classList.toggle("failure", failed);
}

async function api(path, options) {
  const response = await fetch(path, options);
  if (response.status === 204) return;
  const result = await response.json();
  if (!response.ok) throw new Error(result.message ?? "La demande a échoué.");
  return result;
}

function chooseDate(date) {
  if (!date) return;
  state.date = date;
  render();
}

function render() {
  const selectedDate = calendarDate(state.date);
  $("#date-label").textContent = dayFormat.format(selectedDate);
  $("#date-picker").value = state.date;
  $("#new-booking").disabled = state.date < state.today;
  const monday = shift(state.date, -((selectedDate.getUTCDay() + 6) % 7));
  const week = $("#week");
  week.replaceChildren();
  for (let index = 0; index < 7; index++) {
    const date = shift(monday, index);
    const local = calendarDate(date);
    const button = document.createElement("button");
    const label = document.createElement("span");
    const number = document.createElement("strong");
    button.className = "day";
    button.setAttribute("aria-label", dayFormat.format(local));
    if (date === state.date) button.setAttribute("aria-current", "date");
    label.textContent = weekdayFormat.format(local);
    number.textContent = local.getUTCDate();
    button.append(label, number);
    button.addEventListener("click", () => {
      chooseDate(date);
      $("#week [aria-current]").focus();
    });
    week.append(button);
  }

  const rooms = state.rooms.filter((room) => !state.type || state.type === room.type);
  $("#room-count").textContent = rooms.length + (rooms.length === 1 ? " espace" : " espaces");
  const roomList = $("#rooms");
  roomList.replaceChildren();
  for (const room of rooms) {
    const button = $("#room-template").content.firstElementChild.cloneNode(true);
    button.dataset.room = room.id;
    button.setAttribute("aria-pressed", String(room.id === state.roomId));
    button.querySelector(".space-icon").textContent = room.type === "Laboratoire" ? "◇" : "▤";
    button.querySelector("strong").textContent = room.name;
    button.querySelector(".location").textContent = room.location;
    button.querySelector(".equipment").textContent = room.equipment;
    button.querySelector(".capacity").textContent = room.capacity + " places";
    button.addEventListener("click", () => {
      state.roomId = state.roomId === room.id ? "" : room.id;
      render();
      $("#rooms [data-room='" + room.id + "']").focus();
    });
    roomList.append(button);
  }

  const selected = rooms.find((room) => room.id === state.roomId);
  const visible = state.bookings
    .filter((booking) => booking.date === state.date
      && (selected ? booking.roomId === selected.id : rooms.some((room) => room.id === booking.roomId)))
    .sort((first, second) => first.start.localeCompare(second.start));
  $("#agenda-title").textContent = selected ? selected.name : "Créneaux du jour";
  $("#booking-count").textContent = visible.length + (visible.length === 1 ? " réservation" : " réservations");

  const agenda = $("#agenda");
  agenda.replaceChildren();
  if (selected) agenda.append(availability(visible));
  if (!visible.length) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "Aucune réservation pour cette journée.";
    agenda.append(empty);
  }
  for (const booking of visible) {
    const room = rooms.find((item) => item.id === booking.roomId);
    const card = $("#booking-template").content.firstElementChild.cloneNode(true);
    card.classList.toggle("lab", room.type === "Laboratoire");
    card.querySelector(".time strong").textContent = booking.start;
    card.querySelector(".time span").textContent = booking.end;
    card.querySelector("h3").textContent = booking.title;
    card.querySelector(".booking-info p").textContent = room.name + " · " + booking.organizer;
    card.querySelector(".series").hidden = !booking.seriesId;
    const cancel = card.querySelector(".cancel-button");
    cancel.setAttribute("aria-label", "Annuler " + booking.title + " à " + booking.start);
    cancel.addEventListener("click", () => openCancellation(booking, room));
    agenda.append(card);
  }
}

function availability(bookings) {
  const windows = [];
  let time = "08:00";
  for (const booking of bookings) {
    if (time < booking.start) windows.push(time + " – " + booking.start);
    if (time < booking.end) time = booking.end;
  }
  if (time < "18:00") windows.push(time + " – 18:00");
  const container = document.createElement("div");
  const title = document.createElement("strong");
  const list = document.createElement("p");
  container.className = "availability";
  title.textContent = "Créneaux disponibles";
  list.textContent = windows.join("  ·  ") || "Aucun créneau libre.";
  container.append(title, list);
  return container;
}

function setupForm() {
  const { roomId, date, weeks, start, end } = bookingForm.elements;
  for (const room of state.rooms) roomId.add(new Option(room.name, room.id));
  date.min = state.today;
  weeks.add(new Option("Une seule fois", "1"));
  for (let count = 2; count <= 12; count++) {
    weeks.add(new Option("Chaque semaine · " + count + " fois", String(count)));
  }
  for (let minute = 480; minute <= 1080; minute += 15) {
    const time = String(Math.floor(minute / 60)).padStart(2, "0") + ":" + String(minute % 60).padStart(2, "0");
    if (minute < 1080) start.add(new Option(time, time));
    if (minute > 480) end.add(new Option(time, time));
  }
  start.addEventListener("change", () => {
    if (end.value > start.value) return;
    end.selectedIndex = Math.min(start.selectedIndex + 1, end.options.length - 1);
  });
}

function openBooking() {
  bookingForm.reset();
  const { roomId, date, start, end } = bookingForm.elements;
  roomId.value = state.roomId || (state.rooms.find((room) => !state.type || room.type === state.type)?.id ?? "");
  date.value = state.date;
  start.value = "08:00";
  end.value = "09:00";
  showMessage($("#booking-error"), "");
  bookingDialog.showModal();
}

function openCancellation(booking, room) {
  state.cancellation = booking;
  cancelForm.reset();
  $("#cancel-description").textContent = booking.title + " · " + room.name + " · " + booking.start + "–" + booking.end;
  $("#series-option").hidden = !booking.seriesId;
  showMessage($("#cancel-error"), "");
  cancelDialog.showModal();
}

bookingForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = event.submitter;
  const data = Object.fromEntries(new FormData(bookingForm));
  data.weeks = Number(data.weeks);
  submit.disabled = true;
  showMessage($("#booking-error"), "");
  try {
    const saved = await api("api/reservations", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data)
    });
    state.bookings.push(...saved.bookings);
    state.roomId = data.roomId;
    state.type = "";
    for (const button of $("#filters").querySelectorAll("button")) {
      button.setAttribute("aria-pressed", String(button.dataset.type === ""));
    }
    bookingDialog.close();
    chooseDate(data.date);
    showMessage($("#notification"), data.weeks === 1 ? "Créneau réservé." : data.weeks + " créneaux hebdomadaires réservés.");
  } catch (error) {
    showMessage($("#booking-error"), error.message, true);
  } finally {
    submit.disabled = false;
  }
});

cancelForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = event.submitter;
  const booking = state.cancellation;
  const entireSeries = cancelForm.elements.series.checked;
  submit.disabled = true;
  showMessage($("#cancel-error"), "");
  try {
    await api("api/reservations/" + booking.id + "?serie=" + entireSeries, { method: "DELETE" });
    state.bookings = state.bookings.filter((item) => item.id !== booking.id
      && !(entireSeries && booking.seriesId && item.seriesId === booking.seriesId));
    cancelDialog.close();
    render();
    showMessage($("#notification"), entireSeries ? "Série annulée." : "Réservation annulée.");
  } catch (error) {
    showMessage($("#cancel-error"), error.message, true);
  } finally {
    submit.disabled = false;
  }
});

for (const button of document.querySelectorAll("[data-close]")) {
  button.addEventListener("click", () => document.getElementById(button.dataset.close).close());
}
$("#filters").addEventListener("click", (event) => {
  const button = event.target.closest("[data-type]");
  if (!button || !state.date) return;
  state.type = button.dataset.type;
  state.roomId = "";
  for (const item of $("#filters").querySelectorAll("button")) item.setAttribute("aria-pressed", String(item === button));
  render();
});
$("#today").addEventListener("click", () => chooseDate(state.today));
$("#previous").addEventListener("click", () => chooseDate(shift(state.date, -1)));
$("#next").addEventListener("click", () => chooseDate(shift(state.date, 1)));
$("#date-picker").addEventListener("change", (event) => chooseDate(event.target.value));
$("#new-booking").addEventListener("click", openBooking);

async function initialize() {
  try {
    Object.assign(state, await api("api/planning"));
    setupForm();
    for (const id of ["today", "previous", "next", "date-picker"]) $("#" + id).disabled = false;
    chooseDate(state.today);
  } catch (error) {
    $("#date-label").textContent = "Planning indisponible";
    showMessage($("#notification"), "Actualisez la page pour réessayer. " + error.message, true);
  }
}
initialize();
