package com.sd.mensajero;

import com.sd.mensajero.model.Alumno;
import com.sd.mensajero.model.Aviso;
import com.sd.mensajero.model.EnvioAlumno;
import com.sd.mensajero.model.EnvioAlumnoEntrante;
import com.sd.mensajero.model.MensajeChat;
import com.sd.mensajero.model.MensajeEntrante;
import com.sd.mensajero.servicio.RegistroUsuarios;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Levanta el servidor en un puerto aleatorio y lo prueba con clientes STOMP reales.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MensajeroIntegrationTests {

    private static final long ESPERA_MS = 3000;
    private static final Alumno ANA_LOPEZ =
            new Alumno("00000123456", "Ana López", "Ingeniería en Software", 5, 92.5);

    @LocalServerPort
    private int puerto;

    @Autowired
    private RegistroUsuarios registro;

    private WebSocketStompClient stomp;
    private final List<StompSession> sesiones = new ArrayList<>();

    @BeforeEach
    void preparar() {
        stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void cerrar() throws InterruptedException {
        sesiones.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        // El servidor se comparte entre pruebas: esperamos a que libere los nombres antes de seguir.
        long limite = System.currentTimeMillis() + ESPERA_MS;
        while (!registro.nombres().isEmpty() && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
        }
        assertThat(registro.nombres()).as("usuarios que quedaron conectados").isEmpty();
    }

    @Test
    void laListaDeConectadosSeActualizaAlEntrarYSalir() throws Exception {
        StompSession ana = conectar("Ana");
        BlockingQueue<String[]> listas = suscribir(ana, "/tema/usuarios", String[].class);
        sincronizar(ana);

        StompSession beto = conectar("Beto");
        esperar(listas, l -> List.of(l).equals(List.of("Ana", "Beto")));

        beto.disconnect();
        esperar(listas, l -> List.of(l).equals(List.of("Ana")));
    }

    @Test
    void alSuscribirseRecibeLaListaActual() throws Exception {
        conectar("Ana");
        StompSession beto = conectar("Beto");
        BlockingQueue<String[]> inicial = suscribir(beto, "/app/usuarios", String[].class);
        esperar(inicial, l -> List.of(l).equals(List.of("Ana", "Beto")));
    }

    @Test
    void unNombreRepetidoEsRechazado() throws Exception {
        conectar("Ana");
        String error = conectarEsperandoError("ana");
        assertThat(error).contains("ya está en uso");
    }

    @Test
    void elMensajePublicoLlegaATodos() throws Exception {
        StompSession ana = conectar("Ana");
        StompSession beto = conectar("Beto");
        BlockingQueue<MensajeChat> chatAna = suscribir(ana, "/tema/chat", MensajeChat.class);
        BlockingQueue<MensajeChat> chatBeto = suscribir(beto, "/tema/chat", MensajeChat.class);
        sincronizar(ana);
        sincronizar(beto);

        ana.send("/app/chat", new MensajeEntrante(null, "Hola a todos"));

        Predicate<MensajeChat> esElSaludo = m -> m.tipo() == MensajeChat.Tipo.PUBLICO
                && m.remitente().equals("Ana") && m.contenido().equals("Hola a todos");
        esperar(chatAna, esElSaludo);
        esperar(chatBeto, esElSaludo);
    }

    @Test
    void elMensajePrivadoSoloLoRecibeElDestinatario() throws Exception {
        StompSession ana = conectar("Ana");
        StompSession beto = conectar("Beto");
        StompSession carla = conectar("Carla");
        BlockingQueue<MensajeChat> privadosAna = suscribir(ana, "/usuario/cola/chat", MensajeChat.class);
        BlockingQueue<MensajeChat> privadosBeto = suscribir(beto, "/usuario/cola/chat", MensajeChat.class);
        BlockingQueue<MensajeChat> privadosCarla = suscribir(carla, "/usuario/cola/chat", MensajeChat.class);
        BlockingQueue<MensajeChat> publicosCarla = suscribir(carla, "/tema/chat", MensajeChat.class);
        sincronizar(ana);
        sincronizar(beto);
        sincronizar(carla);

        ana.send("/app/chat", new MensajeEntrante("beto", "Sólo para ti"));

        Predicate<MensajeChat> esElSecreto = m -> m.tipo() == MensajeChat.Tipo.PRIVADO
                && m.remitente().equals("Ana") && m.destinatario().equals("Beto");
        esperar(privadosBeto, esElSecreto);
        esperar(privadosAna, esElSecreto);   // copia para el remitente
        assertThat(privadosCarla.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(publicosCarla).noneMatch(m -> "Sólo para ti".equals(m.contenido()));
    }

    @Test
    void elAlumnoSeReenviaATodosOAUnoSolo() throws Exception {
        StompSession ana = conectar("Ana");
        StompSession beto = conectar("Beto");
        StompSession carla = conectar("Carla");
        BlockingQueue<EnvioAlumno> temaBeto = suscribir(beto, "/tema/alumnos", EnvioAlumno.class);
        BlockingQueue<EnvioAlumno> temaCarla = suscribir(carla, "/tema/alumnos", EnvioAlumno.class);
        BlockingQueue<EnvioAlumno> colaBeto = suscribir(beto, "/usuario/cola/alumnos", EnvioAlumno.class);
        BlockingQueue<EnvioAlumno> colaCarla = suscribir(carla, "/usuario/cola/alumnos", EnvioAlumno.class);
        sincronizar(ana);
        sincronizar(beto);
        sincronizar(carla);

        ana.send("/app/alumno", new EnvioAlumnoEntrante(null, ANA_LOPEZ));
        EnvioAlumno aTodos = esperar(temaBeto, e -> e.alumno().equals(ANA_LOPEZ));
        assertThat(aTodos.remitente()).isEqualTo("Ana");
        assertThat(aTodos.destinatario()).isNull();
        esperar(temaCarla, e -> e.alumno().equals(ANA_LOPEZ));

        ana.send("/app/alumno", new EnvioAlumnoEntrante("Carla", ANA_LOPEZ));
        EnvioAlumno privado = esperar(colaCarla, e -> e.alumno().equals(ANA_LOPEZ));
        assertThat(privado.destinatario()).isEqualTo("Carla");
        assertThat(colaBeto.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unAlumnoInvalidoRegresaUnAvisoSoloAlRemitente() throws Exception {
        StompSession ana = conectar("Ana");
        BlockingQueue<Aviso> errores = suscribir(ana, "/usuario/cola/errores", Aviso.class);
        sincronizar(ana);

        Alumno semestreInvalido = new Alumno("123", "Ana", "Software", 20, 90.0);
        ana.send("/app/alumno", new EnvioAlumnoEntrante(null, semestreInvalido));

        esperar(errores, a -> a.texto().contains("semestre"));
    }

    @Test
    void unDestinatarioDesconectadoRegresaUnAviso() throws Exception {
        StompSession ana = conectar("Ana");
        BlockingQueue<Aviso> errores = suscribir(ana, "/usuario/cola/errores", Aviso.class);
        sincronizar(ana);

        ana.send("/app/chat", new MensajeEntrante("Fantasma", "¿hola?"));

        esperar(errores, a -> a.texto().contains("Fantasma ya no está conectado"));
    }

    @Test
    void nadiePuedePublicarDirectoEnUnTemaSaltandoseAlServidor() throws Exception {
        StompSession ana = conectar("Ana");
        StompSession beto = conectar("Beto");
        BlockingQueue<MensajeChat> chatBeto = suscribir(beto, "/tema/chat", MensajeChat.class);
        sincronizar(beto);

        ana.send("/tema/chat", new MensajeChat(MensajeChat.Tipo.PUBLICO, "Carla", null, "suplantación", "x"));

        Thread.sleep(500);
        assertThat(chatBeto).noneMatch(m -> "suplantación".equals(m.contenido()));
    }

    // ---------------------------------------------------------------- utilidades

    private String url() {
        return "ws://localhost:" + puerto + "/websocket";
    }

    private StompSession conectar(String nombre) throws Exception {
        StompHeaders encabezados = new StompHeaders();
        encabezados.add("usuario", nombre);
        StompSession sesion = stomp
                .connectAsync(url(), new WebSocketHttpHeaders(), encabezados, new StompSessionHandlerAdapter() {})
                .get(ESPERA_MS, TimeUnit.MILLISECONDS);
        sesiones.add(sesion);
        return sesion;
    }

    /** Intenta conectarse y devuelve el texto del frame ERROR que manda el servidor. */
    private String conectarEsperandoError(String nombre) throws Exception {
        StompHeaders encabezados = new StompHeaders();
        encabezados.add("usuario", nombre);
        CompletableFuture<String> error = new CompletableFuture<>();
        stomp.connectAsync(url(), new WebSocketHttpHeaders(), encabezados, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                error.completeExceptionally(new AssertionError("La conexión debió rechazarse"));
            }

            // El frame ERROR llega sin cuerpo JSON, así que el cliente de Spring lo entrega aquí.
            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                        byte[] payload, Throwable exception) {
                if (command == StompCommand.ERROR) {
                    error.complete(headers.getFirst("message"));
                }
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                error.complete(headers.getFirst("message"));
            }
        });
        return error.get(ESPERA_MS, TimeUnit.MILLISECONDS);
    }

    private <T> BlockingQueue<T> suscribir(StompSession sesion, String destino, Class<T> tipo) {
        BlockingQueue<T> recibidos = new LinkedBlockingQueue<>();
        sesion.subscribe(destino, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return tipo;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                recibidos.add(tipo.cast(payload));
            }
        });
        return recibidos;
    }

    /**
     * Pide la lista de usuarios y espera la respuesta. Como el servidor procesa en orden los
     * mensajes de cada sesión, al recibirla ya quedaron registradas las suscripciones anteriores.
     */
    private void sincronizar(StompSession sesion) throws InterruptedException {
        BlockingQueue<String[]> respuesta = suscribir(sesion, "/app/usuarios", String[].class);
        if (respuesta.poll(ESPERA_MS, TimeUnit.MILLISECONDS) == null) {
            fail("El servidor no respondió a /app/usuarios");
        }
    }

    /** Espera hasta que llegue un elemento que cumpla la condición (ignora los demás). */
    private static <T> T esperar(BlockingQueue<T> cola, Predicate<T> condicion) throws InterruptedException {
        long limite = System.currentTimeMillis() + ESPERA_MS;
        while (System.currentTimeMillis() < limite) {
            T elemento = cola.poll(limite - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (elemento != null && condicion.test(elemento)) {
                return elemento;
            }
        }
        return fail("No llegó el mensaje esperado a tiempo");
    }
}
