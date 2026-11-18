// Live demo client for the ticketing-app API. Plain JavaScript, no build step: served by Spring Boot
// from /static. Every call goes through api() so it shows up in the HTTP activity panel.
'use strict';

const SHOW = '00000000-0000-0000-0000-000000000101';
const EVENT = '00000000-0000-0000-0000-000000000001';
const USERS = [
  { name: 'Alice', id: '11111111-1111-4111-8111-111111111111' },
  { name: 'Bob', id: '22222222-2222-4222-8222-222222222222' },
  { name: 'Charlie', id: '33333333-3333-4333-8333-333333333333' },
];
const MAX_SEATS = 8;

const state = {
  user: USERS.find(u => u.id === localStorage.getItem('demo.user')) || USERS[0],
  seats: [],               // last seat map
  selected: new Set(),
  booking: null,           // the booking shown in "Your booking"
  lastStatus: null,
  lastPayment: null,
};

const $ = id => document.getElementById(id);
const rupees = minor => '₹' + (minor / 100).toLocaleString('en-IN', { minimumFractionDigits: 2 });
const uuid = () => crypto.randomUUID();
const time = d => new Date(d).toLocaleTimeString();

// ---------------------------------------------------------------- HTTP

async function api(method, path, { body, user = state.user.id, key, quiet = false } = {}) {
  const headers = { 'X-User-Id': user };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const started = performance.now();
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const ms = Math.round(performance.now() - started);
  let data = null;
  try { data = await response.json(); } catch { /* empty body */ }
  if (!quiet) log(method, path, response.status, ms, response.headers.get('Idempotent-Replayed'));
  return { status: response.status, data, replayed: response.headers.get('Idempotent-Replayed') === 'true', ms };
}

function log(method, path, status, ms, replayed) {
  const li = document.createElement('li');
  li.innerHTML = `<span class="muted">${new Date().toLocaleTimeString()}</span><b>${method}</b>` +
    `<span>${path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, m => m.slice(0, 8) + '…')}${replayed ? ' <i>(replayed)</i>' : ''}</span>` +
    `<span class="s${String(status)[0]}">${status}</span><span class="muted">${ms} ms</span>`;
  $('log').prepend(li);
  while ($('log').children.length > 200) $('log').lastChild.remove();
}

// ---------------------------------------------------------------- seat map

async function loadCatalog() {
  const events = await api('GET', '/api/v1/events', { quiet: true });
  const event = events.data?.items?.find(e => e.eventId === EVENT) || events.data?.items?.[0];
  const shows = await api('GET', `/api/v1/events/${EVENT}/shows`, { quiet: true });
  const show = shows.data?.[0];
  $('event-name').textContent = event ? event.name : 'Event';
  if (show) $('show-info').textContent = `${show.venueName} · ${new Date(show.startsAt).toLocaleString()} · ${show.status.replace('_', ' ')}`;
}

async function refreshSeats() {
  const r = await api('GET', `/api/v1/shows/${SHOW}/seats`, { quiet: true });
  if (r.status !== 200) return;
  const before = new Map(state.seats.map(s => [s.seatCode, s.status]));
  state.seats = r.data.seats;
  renderSeats(before);
}

function mySeatCodes() {
  const b = state.booking;
  return b && b.status === 'PENDING_PAYMENT' ? new Set(b.seats.map(s => s.seatCode)) : new Set();
}

function renderSeats(before = new Map()) {
  const grid = $('seat-grid');
  const mine = mySeatCodes();
  const counts = { AVAILABLE: 0, HELD: 0, BOOKED: 0 };
  grid.replaceChildren(...state.seats.slice().sort((a, b) => a.row - b.row || a.col - b.col).map(seat => {
    counts[seat.status] = (counts[seat.status] || 0) + 1;
    const el = document.createElement('button');
    el.className = 'seat';
    el.textContent = seat.seatCode;
    el.title = `${seat.seatCode} · ${rupees(seat.priceMinor)} · ${seat.status}`;
    if (seat.status !== 'AVAILABLE') state.selected.delete(seat.seatCode);
    if (mine.has(seat.seatCode) && seat.status === 'HELD') el.classList.add('mine');
    else if (seat.status === 'HELD') el.classList.add('held');
    else if (seat.status === 'BOOKED') el.classList.add('booked');
    else if (state.selected.has(seat.seatCode)) el.classList.add('selected');
    el.disabled = seat.status !== 'AVAILABLE';
    if (before.size && before.get(seat.seatCode) !== seat.status) el.classList.add('flash');
    el.onclick = () => toggle(seat.seatCode);
    return el;
  }));
  $('counts').innerHTML = `<span>${counts.AVAILABLE} available</span><span>${counts.HELD} held</span><span>${counts.BOOKED} booked</span>`;
  renderSelection();
}

function toggle(code) {
  if (state.selected.has(code)) state.selected.delete(code);
  else if (state.selected.size < MAX_SEATS) state.selected.add(code);
  renderSeats();
}

function renderSelection() {
  const codes = [...state.selected];
  const total = state.seats.filter(s => state.selected.has(s.seatCode)).reduce((sum, s) => sum + s.priceMinor, 0);
  $('sel-seats').textContent = codes.length ? codes.join(', ') : 'No seats selected';
  $('sel-total').textContent = codes.length ? ` · ${rupees(total)}` : '';
  $('book-btn').disabled = codes.length === 0;
}

// ---------------------------------------------------------------- booking

async function book() {
  const seatCodes = [...state.selected];
  $('book-btn').disabled = true;
  const r = await api('POST', '/api/v1/bookings', { body: { showId: SHOW, seatCodes }, key: uuid() });
  if (r.status === 201) {
    state.selected.clear();
    showBooking(r.data, true);
    timeline(`Seats ${seatCodes.join(', ')} held, booking created (PENDING_PAYMENT), payment intent ${r.data.payment?.status}`);
  } else {
    alert(`${r.data?.code || r.status}: ${r.data?.message || 'booking failed'}`);
  }
  refreshSeats();
}

function showBooking(b, fresh = false) {
  if (fresh) { $('timeline').replaceChildren(); state.lastStatus = null; state.lastPayment = null; }
  state.booking = b;
  localStorage.setItem('demo.booking.' + state.user.id, b.bookingId);
  $('booking-empty').hidden = true;
  $('booking').hidden = false;
  $('b-status').textContent = b.status.replace('_', ' ');
  $('b-status').className = 'badge ' + b.status;
  $('b-id').textContent = b.bookingId;
  $('b-seats').textContent = b.seats.map(s => s.seatCode).join(', ');
  $('b-amount').textContent = rupees(b.amountMinor) + ' ' + b.currency;
  const pay = b.payment?.status || '—';
  $('b-pay').textContent = pay;
  $('b-pay').className = 'badge small ' + pay;
  $('b-order').textContent = b.payment?.gatewayOrderId || '';
  const payable = b.status === 'PENDING_PAYMENT' && b.payment?.status === 'INITIATED';
  document.querySelectorAll('[data-pay]').forEach(btn => btn.disabled = !payable);
  $('cancel-btn').disabled = b.status !== 'PENDING_PAYMENT';
  if (state.lastStatus && state.lastStatus !== b.status) timeline(`Booking ${state.lastStatus} → ${b.status}`);
  if (state.lastPayment && state.lastPayment !== pay) timeline(`Payment ${state.lastPayment} → ${pay}`);
  state.lastStatus = b.status;
  state.lastPayment = pay;
  explain(b);
  renderTimer();
}

function explain(b) {
  const box = $('b-explain');
  const msg = {
    UNKNOWN: 'The gateway took the money but its answer was lost. The seats stay held: the system never guesses. ' +
      'Payment reconciliation asks the gateway within about 40 s, then the saga confirms the booking (or refunds it if the hold expired).',
    REFUNDED: 'The money arrived after the seats could no longer be given (hold expired or booking cancelled), so the saga refunded it automatically.',
    FAILED: 'The payment failed, so the saga released the seats straight away.',
    EXPIRED: 'Nobody paid before the hold expired: the saga cancelled the payment intent so a late click can no longer charge.',
    CONFIRMED: 'Payment captured → seats confirmed in inventory (BOOKED) → booking CONFIRMED. One saga, three modules.',
    CANCELLED: 'Cancelled: seats released first, then the payment intent was withdrawn.',
  }[b.payment?.status === 'UNKNOWN' && b.status === 'PENDING_PAYMENT' ? 'UNKNOWN' : b.status];
  box.textContent = msg || '';
  box.classList.toggle('show', !!msg);
}

function renderTimer() {
  const b = state.booking;
  if (!b || b.status !== 'PENDING_PAYMENT' || !b.holdExpiresAt) { $('b-timer').textContent = ''; return; }
  const left = Math.max(0, Math.round((new Date(b.holdExpiresAt) - Date.now()) / 1000));
  $('b-timer').textContent = `⏱ ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} to pay`;
}

function timeline(text) {
  const li = document.createElement('li');
  li.innerHTML = `<time>${new Date().toLocaleTimeString()}</time>${text}`;
  $('timeline').append(li);
}

async function refreshBooking() {
  if (!state.booking) return;
  const r = await api('GET', `/api/v1/bookings/${state.booking.bookingId}`, { quiet: true });
  if (r.status === 200) showBooking(r.data);
}

async function pay(instruction) {
  const p = state.booking?.payment;
  if (!p) return;
  timeline(`Checkout: gateway will ${instruction === 'SUCCESS' ? 'succeed' : instruction === 'DECLINE' ? 'decline' : 'take the money and lose the answer'}`);
  document.querySelectorAll('[data-pay]').forEach(btn => btn.disabled = true);
  const r = await api('POST', `/api/v1/payments/${p.paymentId}/checkout`, { body: { instruction }, key: uuid() });
  if (r.status !== 200) alert(`${r.data?.code || r.status}: ${r.data?.message || ''}`);
  await refreshBooking();
  refreshSeats();
  loadHistory();
}

async function cancel() {
  const r = await api('POST', `/api/v1/bookings/${state.booking.bookingId}/cancel`, { key: uuid() });
  if (r.status === 200) showBooking(r.data);
  else alert(`${r.data?.code || r.status}: ${r.data?.message || ''}`);
  refreshSeats();
  loadHistory();
}

// ---------------------------------------------------------------- history

async function loadHistory() {
  const r = await api('GET', '/api/v1/bookings?size=10', { quiet: true });
  const rows = (r.data?.items || []).map(b => `
    <tr>
      <td>${time(b.createdAt)}</td>
      <td>${b.seats.map(s => s.seatCode).join(', ')}</td>
      <td>${rupees(b.amountMinor)}</td>
      <td><span class="badge small ${b.status}">${b.status.replace('_', ' ')}</span></td>
      <td><span class="badge small ${b.payment?.status || ''}">${b.payment?.status || '—'}</span></td>
      <td><button class="ghost small" data-open="${b.bookingId}">Open</button></td>
    </tr>`).join('');
  $('history').innerHTML = rows || '<tr><td colspan="6" class="muted">No bookings yet for this user.</td></tr>';
  document.querySelectorAll('[data-open]').forEach(btn => btn.onclick = async () => {
    const one = await api('GET', `/api/v1/bookings/${btn.dataset.open}`);
    if (one.status === 200) { showBooking(one.data, true); timeline(`Opened booking (${one.data.status})`); renderSeats(); }
  });
}

// ---------------------------------------------------------------- lab

async function stampede() {
  const n = Math.min(1000, Math.max(2, Number($('st-n').value) || 200));
  const seat = $('st-seat').value.trim().toUpperCase();
  $('st-run').disabled = true;
  $('st-result').innerHTML = `Sending ${n} requests for ${seat}…`;
  const started = performance.now();
  const results = await Promise.all(Array.from({ length: n }, () =>
    api('POST', '/api/v1/bookings', { body: { showId: SHOW, seatCodes: [seat] }, user: uuid(), key: uuid(), quiet: true })
      .catch(() => ({ status: 0, data: null }))));
  const ms = Math.round(performance.now() - started);
  const by = {};
  results.forEach(r => { const k = r.status === 201 ? 'WON (seat held, booking created)' : (r.data?.code || r.status); by[k] = (by[k] || 0) + 1; });
  const winners = by['WON (seat held, booking created)'] || 0;
  const colors = { 'WON (seat held, booking created)': 'var(--success)', SEAT_UNAVAILABLE: 'var(--held)' };
  $('st-result').innerHTML = `
    <div class="big ${winners === 1 ? 'ok' : 'bad'}">${winners} winner${winners === 1 ? '' : 's'} out of ${n}</div>
    <div class="bars">${Object.entries(by).map(([k, v]) => `<span style="width:${100 * v / n}%;background:${colors[k] || 'var(--danger)'}"></span>`).join('')}</div>
    ${Object.entries(by).map(([k, v]) => `<div>${k}: <b>${v}</b></div>`).join('')}
    <div class="muted">${ms} ms in total. Losers were refused by the Redis fast path or by Postgres; none got a partial or double booking.</div>`;
  log('POST', `/api/v1/bookings ×${n} (stampede for ${seat})`, winners === 1 ? 201 : 409, ms);
  $('st-run').disabled = false;
  refreshSeats();
}

async function duplicates() {
  const seat = $('dup-seat').value.trim().toUpperCase();
  const key = uuid();
  $('dup-run').disabled = true;
  const results = await Promise.all(Array.from({ length: 20 }, () =>
    api('POST', '/api/v1/bookings', { body: { showId: SHOW, seatCodes: [seat] }, key, quiet: true })));
  const ids = new Set(results.filter(r => r.status === 201).map(r => r.data.bookingId));
  const replayed = results.filter(r => r.replayed).length;
  const waiting = results.filter(r => r.data?.code === 'IDEMPOTENCY_IN_PROGRESS').length;
  const other = results.filter(r => r.status !== 201 && r.data?.code !== 'IDEMPOTENCY_IN_PROGRESS');
  $('dup-result').innerHTML = `
    <div class="big ${ids.size <= 1 ? 'ok' : 'bad'}">${ids.size} booking${ids.size === 1 ? '' : 's'} created from 20 requests</div>
    <div>First answer: <b>${results.filter(r => r.status === 201 && !r.replayed).length}</b> · replayed from the stored answer: <b>${replayed}</b> · told to retry (still in progress): <b>${waiting}</b>${other.length ? ` · other: <b>${other.map(r => r.data?.code || r.status).join(', ')}</b>` : ''}</div>
    <div class="muted mono">Idempotency-Key ${key}</div>`;
  log('POST', `/api/v1/bookings ×20 (same Idempotency-Key)`, ids.size <= 1 ? 201 : 500, 0);
  const booked = results.find(r => r.status === 201);
  if (booked) { showBooking(booked.data, true); timeline(`20 duplicate requests → one booking (${booked.data.bookingId.slice(0, 8)}…)`); }
  $('dup-run').disabled = false;
  refreshSeats();
  loadHistory();
}

// ---------------------------------------------------------------- wiring

async function restoreBooking() {
  state.booking = null;
  $('booking').hidden = true;
  $('booking-empty').hidden = false;
  const id = localStorage.getItem('demo.booking.' + state.user.id);
  if (!id) return;
  const r = await api('GET', `/api/v1/bookings/${id}`, { quiet: true });
  if (r.status === 200) { showBooking(r.data, true); timeline(`Restored booking (${r.data.status})`); }
}

async function health() {
  try {
    const r = await fetch('/actuator/health').then(x => x.json());
    $('health').textContent = r.status === 'UP' ? 'API up' : r.status;
    $('health').className = 'pill ' + (r.status === 'UP' ? 'ok' : 'bad');
  } catch { $('health').textContent = 'API down'; $('health').className = 'pill bad'; }
}

function init() {
  const select = $('user');
  select.innerHTML = USERS.map(u => `<option value="${u.id}">${u.name}</option>`).join('');
  select.value = state.user.id;
  select.onchange = async () => {
    state.user = USERS.find(u => u.id === select.value);
    localStorage.setItem('demo.user', state.user.id);
    state.selected.clear();
    await restoreBooking();
    renderSeats();
    loadHistory();
  };
  $('book-btn').onclick = book;
  document.querySelectorAll('[data-pay]').forEach(btn => btn.onclick = () => pay(btn.dataset.pay));
  $('cancel-btn').onclick = cancel;
  $('st-run').onclick = stampede;
  $('dup-run').onclick = duplicates;
  $('hist-refresh').onclick = loadHistory;
  $('log-clear').onclick = () => $('log').replaceChildren();

  health();
  loadCatalog();
  refreshSeats();
  restoreBooking();
  loadHistory();
  setInterval(refreshSeats, 1500);
  setInterval(refreshBooking, 1500);
  setInterval(renderTimer, 1000);
  setInterval(health, 10000);
}

init();
