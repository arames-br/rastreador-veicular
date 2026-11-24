import { initializeApp } from 'https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js';
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js';
import { getFirestore, doc, onSnapshot } from 'https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js';
import { firebaseConfig } from './firebase-config.js';

const OWNER = 'aramesbrasil40@gmail.com';
const $ = (id) => document.getElementById(id);
const authCard = $('authCard');
const appSection = $('app');
const authMsg = $('authMsg');
let auth, db, unsubscribe = null, map = null, marker = null;

try {
  if (!firebaseConfig.apiKey || firebaseConfig.apiKey.startsWith('COLE_')) throw new Error('Configure firebase-config.js primeiro.');
  const firebase = initializeApp(firebaseConfig);
  auth = getAuth(firebase);
  db = getFirestore(firebase);
} catch (error) {
  authMsg.textContent = error.message;
  $('loginBtn').disabled = true;
}

$('loginForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!auth) return;
  const email = $('email').value.trim().toLowerCase();
  const password = $('password').value;
  if (email !== OWNER) { authMsg.textContent = `Acesso restrito à conta ${OWNER}.`; return; }
  $('loginBtn').disabled = true;
  authMsg.textContent = 'Entrando…';
  try { await signInWithEmailAndPassword(auth, email, password); $('password').value = ''; }
  catch (error) { authMsg.textContent = friendlyAuthError(error); }
  finally { $('loginBtn').disabled = false; }
});

$('logout').addEventListener('click', () => signOut(auth));

if (auth && db) onAuthStateChanged(auth, (user) => {
  if (unsubscribe) { unsubscribe(); unsubscribe = null; }
  if (!user) { appSection.style.display = 'none'; authCard.style.display = 'block'; authMsg.textContent = 'Entre com a conta autorizada para ver a posição do veículo.'; return; }
  if (user.email?.toLowerCase() !== OWNER || !user.emailVerified) {
    signOut(auth);
    authMsg.textContent = 'A conta precisa ser a autorizada e ter o e-mail verificado.';
    return;
  }
  authCard.style.display = 'none'; appSection.style.display = 'block'; $('signedIn').textContent = user.email;
  initializeMap();
  $('status').className = '';
  $('statusText').textContent = 'Aguardando dados do rastreador…';
  unsubscribe = onSnapshot(doc(db, 'vehicles', 'vehicle-1'), { includeMetadataChanges: true }, (snapshot) => {
    if (!snapshot.exists()) { setStatus('stale', 'Ainda não há posição recebida. Inicie o rastreamento no celular.'); return; }
    const data = snapshot.data();
    if (typeof data.latitude !== 'number' || typeof data.longitude !== 'number' || !Number.isFinite(data.latitude) || !Number.isFinite(data.longitude)) {
      setStatus('error', 'Os dados recebidos não têm coordenadas válidas.'); return;
    }
    const capturedAt = new Date(data.capturedAtClientMs);
    const receivedAt = data.updatedAt?.toDate ? data.updatedAt.toDate() : null;
    const now = Date.now();
    const age = receivedAt ? now - receivedAt.getTime() : now - capturedAt.getTime();
    const onCache = snapshot.metadata.fromCache;
    const ageText = receivedAt ? formatDate(receivedAt) : 'Aguardando confirmação do servidor';
    $('captured').textContent = formatDate(capturedAt);
    $('received').textContent = ageText + (onCache ? ' (cache deste navegador)' : '');
    $('accuracy').textContent = Number.isFinite(data.accuracyMeters) ? `± ${Math.round(data.accuracyMeters)} m` : '—';
    $('speed').textContent = Number.isFinite(data.speedMetersPerSecond) ? `${(data.speedMetersPerSecond * 3.6).toFixed(1)} km/h` : '—';
    $('coords').textContent = `${data.latitude.toFixed(6)}, ${data.longitude.toFixed(6)}`;
    const point = [data.latitude, data.longitude];
    marker.setLatLng(point);
    map.setView(point, Math.max(map.getZoom(), 15));
    const mapsLink = $('mapsLink');
    mapsLink.href = `https://www.google.com/maps?q=${encodeURIComponent(`${data.latitude},${data.longitude}`)}`;
    mapsLink.hidden = false;
    if (onCache) setStatus('stale', 'Sem confirmação online — exibindo dados em cache; a posição pode estar desatualizada.');
    else if (age > 5 * 60 * 1000) setStatus('stale', `Última posição há ${humanAge(age)}. Pode estar sem sinal, sem internet ou com rastreamento parado.`);
    else setStatus('online', 'Posição recente recebida pela internet.');
  }, (error) => setStatus('error', `Não foi possível ler os dados: ${error.code || error.message}. Confira as regras e a conexão.`));
});

function initializeMap() {
  if (map) { map.invalidateSize(); return; }
  map = L.map('map').setView([-23.55, -46.63], 10);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);
  marker = L.marker([-23.55, -46.63]).addTo(map).bindPopup('Última posição conhecida');
}
function setStatus(kind, text) { $('status').className = kind; $('statusText').textContent = text; }
function formatDate(date) { return Number.isNaN(date.getTime()) ? 'Horário inválido' : date.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'medium' }); }
function humanAge(ms) { const minutes = Math.max(1, Math.floor(ms / 60000)); return minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`; }
function friendlyAuthError(error) {
  if (error.code === 'auth/invalid-credential' || error.code === 'auth/wrong-password') return 'E-mail ou senha inválidos.';
  if (error.code === 'auth/too-many-requests') return 'Muitas tentativas. Aguarde um pouco e tente novamente.';
  if (error.code === 'auth/network-request-failed') return 'Sem conexão com a internet.';
  return `Não foi possível entrar: ${error.message}`;
}
