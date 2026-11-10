'use strict';

/*
 * Cliente del mensajero. Se conecta al servidor por WebSocket usando STOMP (StompJs).
 *
 * Publica en:
 *   /app/chat               { destinatario, contenido }          destinatario null = todos
 *   /app/alumno             { destinatario, alumno: {...} }      destinatario null = todos
 *
 * Se suscribe a:
 *   /app/usuarios           lista de conectados al momento de entrar (llega una sola vez)
 *   /tema/usuarios          lista de conectados cada vez que alguien entra o sale
 *   /tema/chat              mensajes para todos y avisos de sistema
 *   /tema/alumnos           alumnos enviados a todos
 *   /usuario/cola/chat      mensajes privados (recibidos y copia de los enviados)
 *   /usuario/cola/alumnos   alumnos enviados sólo a mí (y copia de los que yo envié a alguien)
 *   /usuario/cola/errores   avisos de error del servidor
 */

const NOMBRE_VALIDO = /^[\p{L}\p{N} ._-]{1,20}$/u;

const ICONO_TODOS =
    '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="9" cy="8" r="3.2"/>' +
    '<path d="M3.5 19c.6-3 2.8-4.8 5.5-4.8s4.9 1.8 5.5 4.8"/><circle cx="17" cy="9" r="2.6"/>' +
    '<path d="M15.8 14.4c2.3.1 4.1 1.7 4.7 4.6"/></svg>';

const EJEMPLOS = {
    nombres: ['Ana Sofía López', 'Luis Fernando Ruiz', 'María José Valenzuela', 'Diego Armando Soto',
        'Valeria Guadalupe Ríos', 'Carlos Iván Moreno', 'Fernanda Leyva Ochoa', 'José Ángel Bórquez'],
    carreras: ['Ingeniería en Software', 'Ingeniería en Sistemas Computacionales', 'Ingeniería Industrial',
        'Ingeniería Mecatrónica', 'Licenciatura en Administración', 'Licenciatura en Diseño Gráfico'],
};

const estado = {
    cliente: null,
    yo: null,
    usuarios: [],
    destinatario: '',   // '' = todos
    enLinea: false,
    yaConecto: false,   // distingue "no se pudo conectar" de "se cayó la conexión"
    pestana: 'chat',
    pendientes: {chat: 0, alumnos: 0},
    alumnos: 0,
};

const $ = (id) => document.getElementById(id);

// ======================================================================
// Conexión
// ======================================================================

function urlWebSocket(servidor) {
    const texto = servidor.trim();
    if (/^wss?:\/\//i.test(texto)) {
        return texto;
    }
    const host = texto.replace(/^https?:\/\//i, '').replace(/\/+$/, '');
    const protocolo = location.protocol === 'https:' ? 'wss' : 'ws';
    return `${protocolo}://${host}/websocket`;
}

function conectar(nombre, servidor) {
    const url = urlWebSocket(servidor);
    const cliente = new StompJs.Client({
        webSocketFactory: () => new WebSocket(url),
        connectHeaders: {usuario: nombre},
        reconnectDelay: 3000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
    });

    // Cada callback revisa que siga siendo el cliente actual, por si el usuario ya salió.
    cliente.onConnect = () => {
        if (estado.cliente === cliente) {
            alConectar(cliente);
        }
    };
    cliente.onStompError = (frame) => {
        if (estado.cliente === cliente) {
            terminarSesion(frame.headers.message || 'El servidor rechazó la conexión.');
        }
    };
    cliente.onWebSocketClose = () => {
        if (estado.cliente !== cliente) {
            return;
        }
        if (!estado.yaConecto) {
            terminarSesion(`No se pudo conectar con ${url}. Revisa la dirección y que el servidor esté encendido.`);
        } else {
            // StompJs reintenta solo cada 3 segundos.
            estado.enLinea = false;
            pintarEstado();
        }
    };

    estado.cliente = cliente;
    estado.yo = nombre;
    estado.yaConecto = false;
    cliente.activate();
}

function alConectar(cliente) {
    const reconexion = estado.yaConecto;
    estado.yaConecto = true;
    estado.enLinea = true;
    pintarEstado();
    if (reconexion) {
        agregarSistema('Conexión recuperada.');
    } else {
        mostrarApp();
    }

    const json = (manejador) => (mensaje) => manejador(JSON.parse(mensaje.body), mensaje.body);
    cliente.subscribe('/tema/usuarios', json(actualizarUsuarios));
    cliente.subscribe('/app/usuarios', json(actualizarUsuarios));
    cliente.subscribe('/tema/chat', json(mostrarMensaje));
    cliente.subscribe('/usuario/cola/chat', json(mostrarMensaje));
    cliente.subscribe('/tema/alumnos', json(agregarAlumno));
    cliente.subscribe('/usuario/cola/alumnos', json(agregarAlumno));
    cliente.subscribe('/usuario/cola/errores', json((aviso) => mostrarAviso(aviso.texto, true)));
}

/** Cierra la conexión y regresa a la pantalla de entrada (con un motivo, si lo hay). */
function terminarSesion(motivo) {
    const cliente = estado.cliente;
    estado.cliente = null;
    estado.enLinea = false;
    if (cliente) {
        cliente.deactivate();
    }
    limpiarApp();
    $('pantallaApp').hidden = true;
    $('pantallaEntrada').hidden = false;
    $('btnEntrar').disabled = false;
    $('btnEntrar').textContent = 'Entrar al chat';
    mostrarErrorEntrada(motivo);
    $('txtNombre').focus();
}

function publicar(destino, cuerpo) {
    if (!estado.cliente || !estado.cliente.connected) {
        mostrarAviso('Sin conexión con el servidor. Espera a que se reconecte.', true);
        return false;
    }
    estado.cliente.publish({destination: destino, body: JSON.stringify(cuerpo)});
    return true;
}

// ======================================================================
// Pantallas
// ======================================================================

function mostrarErrorEntrada(texto) {
    const error = $('errorEntrada');
    error.textContent = texto || '';
    error.hidden = !texto;
}

function mostrarApp() {
    $('pantallaEntrada').hidden = true;
    $('pantallaApp').hidden = false;
    $('lblYo').textContent = estado.yo;
    agregarVacioChat();
    pintarUsuarios();
    pintarDestinatario();
    cambiarPestana('chat');
    $('txtMensaje').focus();
}

function limpiarApp() {
    estado.usuarios = [];
    estado.destinatario = '';
    estado.pendientes = {chat: 0, alumnos: 0};
    estado.alumnos = 0;
    $('listaMensajes').replaceChildren();
    $('tablaAlumnos').replaceChildren(filaVacia());
    $('numAlumnos').textContent = '0';
    $('jsonRecibido').textContent = '—';
    pintarBadges();
}

function pintarEstado() {
    const etiqueta = $('estadoConexion');
    etiqueta.textContent = estado.enLinea ? 'En línea' : 'Reconectando…';
    etiqueta.className = 'estado ' + (estado.enLinea ? 'estado--en-linea' : 'estado--reconectando');
}

function cambiarPestana(nombre) {
    estado.pestana = nombre;
    for (const tab of document.querySelectorAll('.pestana')) {
        tab.setAttribute('aria-selected', String(tab.dataset.panel === nombre));
    }
    $('panelChat').hidden = nombre !== 'chat';
    $('panelAlumnos').hidden = nombre !== 'alumnos';
    estado.pendientes[nombre] = 0;
    pintarBadges();
    if (nombre === 'chat') {
        bajarChat();
    }
}

function sumarPendiente(pestana) {
    if (estado.pestana !== pestana) {
        estado.pendientes[pestana]++;
        pintarBadges();
    }
}

function pintarBadges() {
    for (const [pestana, id] of [['chat', 'badgeChat'], ['alumnos', 'badgeAlumnos']]) {
        const n = estado.pendientes[pestana];
        $(id).textContent = n > 99 ? '99+' : String(n);
        $(id).hidden = n === 0;
    }
}

function mostrarAviso(texto, esError = false) {
    const aviso = el('div', esError ? 'aviso aviso--error' : 'aviso', texto);
    $('avisos').append(aviso);
    setTimeout(() => aviso.remove(), 5000);
}

// ======================================================================
// Usuarios conectados y destinatario
// ======================================================================

function actualizarUsuarios(lista) {
    estado.usuarios = lista;
    if (estado.destinatario && !lista.includes(estado.destinatario)) {
        estado.destinatario = '';
        agregarSistema('Tus envíos ahora son para todos.');
    }
    pintarUsuarios();
    pintarDestinatario();
}

function elegirDestinatario(nombre) {
    if (nombre === estado.yo) {
        return;
    }
    if (nombre && !estado.usuarios.includes(nombre)) {
        mostrarAviso(`${nombre} ya no está conectado.`, true);
        return;
    }
    estado.destinatario = nombre;
    pintarUsuarios();
    pintarDestinatario();
    if (estado.pestana === 'chat') {
        $('txtMensaje').focus();
    }
}

function pintarUsuarios() {
    // Orden: Todos, yo, y luego los demás (el servidor ya los manda en orden alfabético).
    const otros = estado.usuarios.filter((n) => n !== estado.yo);
    const lista = $('listaUsuarios');
    lista.replaceChildren(itemUsuario(''), itemUsuario(estado.yo), ...otros.map(itemUsuario));
    $('numConectados').textContent = String(estado.usuarios.length);
}

function itemUsuario(nombre) {
    const esTodos = nombre === '';
    const esYo = nombre === estado.yo;
    const item = el(esYo ? 'div' : 'button', 'usuario');

    if (esYo) {
        item.classList.add('usuario--yo');
    } else {
        item.type = 'button';
        item.title = esTodos ? 'Enviar a todos' : `Enviar sólo a ${nombre}`;
        item.setAttribute('aria-pressed', String(estado.destinatario === nombre));
        item.classList.toggle('usuario--privado', !esTodos);
        item.addEventListener('click', () => elegirDestinatario(nombre));
    }

    item.append(crearAvatar(esTodos ? null : nombre), el('span', 'usuario__nombre', esTodos ? 'Todos' : nombre));
    if (esTodos || esYo) {
        item.append(el('span', 'usuario__nota', esTodos ? 'público' : 'tú'));
    }

    const li = document.createElement('li');
    li.append(item);
    return li;
}

function crearAvatar(nombre) {
    const avatar = el('span', 'avatar');
    avatar.setAttribute('aria-hidden', 'true');
    if (nombre === null) {
        avatar.classList.add('avatar--todos');
        avatar.innerHTML = ICONO_TODOS;
    } else {
        avatar.classList.add('avatar--en-linea');
        avatar.textContent = nombre.charAt(0).toUpperCase();
        avatar.style.setProperty('--tono', String(tono(nombre)));
    }
    return avatar;
}

/** Color estable por nombre, para que cada usuario tenga siempre el mismo avatar. */
function tono(texto) {
    let hash = 0;
    for (const c of texto) {
        hash = (hash * 31 + c.codePointAt(0)) % 360;
    }
    return hash;
}

function pintarDestinatario() {
    const otros = estado.usuarios.filter((n) => n !== estado.yo);
    for (const select of [$('selParaChat'), $('selParaAlumno')]) {
        select.replaceChildren(new Option('Todos', ''), ...otros.map((n) => new Option(n, n)));
        select.value = estado.destinatario;
    }
    const privado = estado.destinatario !== '';
    $('formChat').classList.toggle('redactor--privado', privado);
    $('txtMensaje').placeholder = privado
        ? `Mensaje privado para ${estado.destinatario}…`
        : 'Escribe un mensaje para todos…';
    actualizarJsonEnvio();
}

// ======================================================================
// Chat
// ======================================================================

function mostrarMensaje(m) {
    if (m.tipo === 'SISTEMA') {
        agregarSistema(m.contenido, m.fecha);
        return;
    }
    const propio = m.remitente === estado.yo;
    const privado = m.tipo === 'PRIVADO';

    const li = el('li', 'msg');
    li.classList.toggle('msg--propio', propio);
    li.classList.toggle('msg--privado', privado);

    const meta = el('div', 'msg__meta');
    if (propio) {
        meta.append(el('strong', '', 'Tú'));
    } else {
        const autor = el('button', 'msg__autor', m.remitente);
        autor.type = 'button';
        autor.title = `Responder en privado a ${m.remitente}`;
        autor.addEventListener('click', () => elegirDestinatario(m.remitente));
        meta.append(autor);
    }
    if (privado) {
        meta.append(el('span', 'msg__etiqueta', propio ? `privado para ${m.destinatario}` : 'privado para ti'));
    }
    meta.append(el('time', '', hora(m.fecha)));

    li.append(meta, el('div', 'msg__burbuja', m.contenido));
    agregarAlChat(li, propio);
    if (!propio) {
        sumarPendiente('chat');
    }
}

function agregarSistema(texto, fecha) {
    const marca = hora(fecha || new Date().toISOString());
    agregarAlChat(el('li', 'msg msg--sistema', `${marca} · ${texto}`), false);
}

function agregarVacioChat() {
    $('listaMensajes').replaceChildren(
        el('li', 'mensajes__vacio', 'Aún no hay mensajes. Elige «Todos» o a una persona y escribe abajo.'));
}

function agregarAlChat(li, forzarScroll) {
    const lista = $('listaMensajes');
    const alFinal = lista.scrollHeight - lista.scrollTop - lista.clientHeight < 80;
    lista.querySelector('.mensajes__vacio')?.remove();
    lista.append(li);
    if (alFinal || forzarScroll) {
        bajarChat();
    }
}

function bajarChat() {
    const lista = $('listaMensajes');
    lista.scrollTop = lista.scrollHeight;
}

function enviarMensaje() {
    const caja = $('txtMensaje');
    const contenido = caja.value.trim();
    if (!contenido) {
        return;
    }
    if (publicar('/app/chat', {destinatario: estado.destinatario || null, contenido})) {
        caja.value = '';
    }
    caja.focus();
}

// ======================================================================
// Alumnos (entidad que viaja en JSON)
// ======================================================================

function leerAlumno() {
    const numero = (id) => ($(id).value === '' ? null : Number($(id).value));
    return {
        matricula: $('txtMatricula').value.trim(),
        nombre: $('txtNombreAlumno').value.trim(),
        carrera: $('txtCarrera').value.trim(),
        semestre: numero('txtSemestre'),
        promedio: numero('txtPromedio'),
    };
}

function cuerpoAlumno() {
    return {destinatario: estado.destinatario || null, alumno: leerAlumno()};
}

function actualizarJsonEnvio() {
    $('jsonEnvio').textContent = JSON.stringify(cuerpoAlumno(), null, 2);
}

function enviarAlumno() {
    if (publicar('/app/alumno', cuerpoAlumno())) {
        for (const id of ['txtMatricula', 'txtNombreAlumno', 'txtCarrera', 'txtSemestre', 'txtPromedio']) {
            $(id).value = '';
        }
        actualizarJsonEnvio();
        $('txtMatricula').focus();
    }
}

function llenarEjemplo() {
    const azar = (lista) => lista[Math.floor(Math.random() * lista.length)];
    $('txtMatricula').value = '00000' + String(Math.floor(100000 + Math.random() * 900000));
    $('txtNombreAlumno').value = azar(EJEMPLOS.nombres);
    $('txtCarrera').value = azar(EJEMPLOS.carreras);
    $('txtSemestre').value = String(1 + Math.floor(Math.random() * 9));
    $('txtPromedio').value = (70 + Math.random() * 30).toFixed(1);
    actualizarJsonEnvio();
}

/** Agrega a la tabla HTML el alumno que llegó por el WebSocket. */
function agregarAlumno(envio, json) {
    const a = envio.alumno;
    const tabla = $('tablaAlumnos');
    tabla.querySelector('.tabla__vacia')?.remove();

    const fila = el('tr', 'nueva');
    const celdas = [
        [hora(envio.fecha)],
        [a.matricula, 'mono'],
        [a.nombre],
        [a.carrera],
        [a.semestre, 'num'],
        [Number(a.promedio).toLocaleString('es-MX', {maximumFractionDigits: 2}), 'num'],
        [envio.remitente === estado.yo ? 'Tú' : envio.remitente],
    ];
    for (const [texto, clase] of celdas) {
        fila.append(el('td', clase || '', String(texto)));
    }

    const privado = Boolean(envio.destinatario);
    const para = !privado ? 'Todos' : envio.destinatario === estado.yo ? 'Tú' : envio.destinatario;
    const celdaPara = document.createElement('td');
    celdaPara.append(el('span', privado ? 'etiqueta etiqueta--privado' : 'etiqueta', para));
    fila.append(celdaPara);
    tabla.append(fila);

    estado.alumnos++;
    $('numAlumnos').textContent = String(estado.alumnos);
    $('jsonRecibido').textContent = JSON.stringify(JSON.parse(json), null, 2);
    if (envio.remitente !== estado.yo) {
        sumarPendiente('alumnos');
    }
}

function filaVacia() {
    const fila = el('tr', 'tabla__vacia');
    const celda = el('td', '', 'Todavía no llega ningún alumno.');
    celda.colSpan = 8;
    fila.append(celda);
    return fila;
}

// ======================================================================
// Utilidades
// ======================================================================

/** Crea un elemento; el texto se asigna con textContent para que nadie pueda inyectar HTML. */
function el(etiqueta, clase, texto) {
    const nodo = document.createElement(etiqueta);
    if (clase) {
        nodo.className = clase;
    }
    if (texto !== undefined) {
        nodo.textContent = texto;
    }
    return nodo;
}

function hora(fechaIso) {
    const fecha = new Date(fechaIso);
    return isNaN(fecha) ? '' : fecha.toLocaleTimeString('es-MX', {hour: '2-digit', minute: '2-digit'});
}

function leerPreferencia(clave) {
    try {
        return localStorage.getItem('mensajero.' + clave);
    } catch {
        return null;
    }
}

function guardarPreferencia(clave, valor) {
    try {
        localStorage.setItem('mensajero.' + clave, valor);
    } catch {
        // Sin almacenamiento (modo privado, etc.): no pasa nada.
    }
}

// ======================================================================
// Eventos de la página
// ======================================================================

document.addEventListener('DOMContentLoaded', () => {
    // Si la página la sirve el propio servidor, ya sabemos su dirección.
    const servidoPorHttp = location.protocol === 'http:' || location.protocol === 'https:';
    $('txtServidor').value = servidoPorHttp ? location.host : (leerPreferencia('servidor') || 'localhost:8080');
    $('txtNombre').value = leerPreferencia('nombre') || '';
    $('txtNombre').focus();

    $('formEntrada').addEventListener('submit', (e) => {
        e.preventDefault();
        const nombre = $('txtNombre').value.normalize('NFC').trim().replace(/\s+/g, ' ');
        const servidor = $('txtServidor').value.trim();
        if (!NOMBRE_VALIDO.test(nombre)) {
            mostrarErrorEntrada('Usa de 1 a 20 letras, números, espacios, punto, guion o guion bajo.');
            return;
        }
        guardarPreferencia('nombre', nombre);
        if (!servidoPorHttp) {
            guardarPreferencia('servidor', servidor);
        }
        mostrarErrorEntrada(null);
        $('btnEntrar').disabled = true;
        $('btnEntrar').textContent = 'Conectando…';
        conectar(nombre, servidor);
    });

    $('btnSalir').addEventListener('click', () => terminarSesion(null));

    for (const tab of document.querySelectorAll('.pestana')) {
        tab.addEventListener('click', () => cambiarPestana(tab.dataset.panel));
    }

    for (const select of [$('selParaChat'), $('selParaAlumno')]) {
        select.addEventListener('change', () => elegirDestinatario(select.value));
    }

    $('formChat').addEventListener('submit', (e) => {
        e.preventDefault();
        enviarMensaje();
    });

    $('formAlumno').addEventListener('submit', (e) => {
        e.preventDefault();
        enviarAlumno();
    });
    $('formAlumno').addEventListener('input', actualizarJsonEnvio);
    $('btnEjemplo').addEventListener('click', llenarEjemplo);

    // Al cerrar la pestaña avisamos al servidor para que nos quite de la lista de inmediato.
    window.addEventListener('beforeunload', () => estado.cliente?.deactivate());
});
