package com.sd.mensajero.controller;

import com.sd.mensajero.model.Alumno;
import com.sd.mensajero.model.Aviso;
import com.sd.mensajero.model.EnvioAlumno;
import com.sd.mensajero.model.EnvioAlumnoEntrante;
import com.sd.mensajero.model.MensajeChat;
import com.sd.mensajero.model.MensajeEntrante;
import com.sd.mensajero.servicio.RegistroUsuarios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;

import static com.sd.mensajero.config.Destinos.COLA_ALUMNOS;
import static com.sd.mensajero.config.Destinos.COLA_CHAT;
import static com.sd.mensajero.config.Destinos.COLA_ERRORES;
import static com.sd.mensajero.config.Destinos.TEMA_ALUMNOS;
import static com.sd.mensajero.config.Destinos.TEMA_CHAT;

/**
 * Recibe lo que mandan los clientes y lo redirige: a todos (tema) o a un solo usuario (cola privada).
 */
@Controller
public class MensajeroController {

    private static final Logger log = LoggerFactory.getLogger(MensajeroController.class);
    private static final int MAX_CONTENIDO = 1000;

    private final SimpMessagingTemplate broker;
    private final RegistroUsuarios registro;

    public MensajeroController(SimpMessagingTemplate broker, RegistroUsuarios registro) {
        this.broker = broker;
        this.registro = registro;
    }

    /**
     * El cliente se suscribe a {@code /app/usuarios} al conectarse y recibe una sola vez la lista actual.
     * Los cambios posteriores le llegan por {@code /tema/usuarios}.
     */
    @SubscribeMapping("/usuarios")
    public List<String> usuariosConectados() {
        return registro.nombres();
    }

    @MessageMapping("/chat")
    public void chat(MensajeEntrante entrada, Principal principal) {
        String remitente = principal.getName();
        String contenido = limpiar(entrada.contenido());
        if (contenido.isEmpty()) {
            throw new SolicitudInvalidaException("El mensaje está vacío.");
        }
        if (contenido.length() > MAX_CONTENIDO) {
            throw new SolicitudInvalidaException("El mensaje no puede pasar de " + MAX_CONTENIDO + " caracteres.");
        }

        String destinatario = resolverDestinatario(entrada.destinatario());
        if (destinatario == null) {
            log.info("[chat] {} -> todos: {}", remitente, contenido);
            broker.convertAndSend(TEMA_CHAT, MensajeChat.publico(remitente, contenido));
        } else {
            log.info("[chat] {} -> {} (privado): {}", remitente, destinatario, contenido);
            enviarPrivado(remitente, destinatario, COLA_CHAT,
                    MensajeChat.privado(remitente, destinatario, contenido));
        }
    }

    @MessageMapping("/alumno")
    public void alumno(EnvioAlumnoEntrante entrada, Principal principal) {
        String remitente = principal.getName();
        Alumno alumno = validar(entrada.alumno());

        String destinatario = resolverDestinatario(entrada.destinatario());
        EnvioAlumno envio = EnvioAlumno.de(remitente, destinatario, alumno);
        if (destinatario == null) {
            log.info("[alumno] {} -> todos: {}", remitente, alumno);
            broker.convertAndSend(TEMA_ALUMNOS, envio);
        } else {
            log.info("[alumno] {} -> {} (privado): {}", remitente, destinatario, alumno);
            enviarPrivado(remitente, destinatario, COLA_ALUMNOS, envio);
        }
    }

    /** Errores de validación: se regresan sólo a la sesión que mandó el mensaje. */
    @MessageExceptionHandler(SolicitudInvalidaException.class)
    @SendToUser(destinations = COLA_ERRORES, broadcast = false)
    public Aviso solicitudInvalida(SolicitudInvalidaException ex) {
        return new Aviso(ex.getMessage());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(destinations = COLA_ERRORES, broadcast = false)
    public Aviso errorInesperado(Exception ex) {
        log.warn("No se pudo procesar un mensaje", ex);
        return new Aviso("No se pudo procesar el mensaje. Revisa los datos e inténtalo de nuevo.");
    }

    /** Manda la carga al destinatario y una copia al remitente, para que ambos la vean. */
    private void enviarPrivado(String remitente, String destinatario, String cola, Object carga) {
        broker.convertAndSendToUser(destinatario, cola, carga);
        if (!destinatario.equals(remitente)) {
            broker.convertAndSendToUser(remitente, cola, carga);
        }
    }

    /**
     * Devuelve el nombre exacto del destinatario, o {@code null} si el envío es para todos.
     * Falla si se eligió a alguien que ya no está conectado.
     */
    private String resolverDestinatario(String solicitado) {
        String nombre = limpiar(solicitado);
        if (nombre.isEmpty()) {
            return null;
        }
        return registro.buscar(nombre)
                .orElseThrow(() -> new SolicitudInvalidaException(nombre + " ya no está conectado."));
    }

    private static Alumno validar(Alumno alumno) {
        if (alumno == null) {
            throw new SolicitudInvalidaException("Faltan los datos del alumno.");
        }
        String matricula = limpiar(alumno.matricula());
        String nombre = limpiar(alumno.nombre());
        String carrera = limpiar(alumno.carrera());
        Integer semestre = alumno.semestre();
        Double promedio = alumno.promedio();

        if (matricula.isEmpty() || matricula.length() > 20) {
            throw new SolicitudInvalidaException("La matrícula es obligatoria (máximo 20 caracteres).");
        }
        if (nombre.isEmpty() || nombre.length() > 80) {
            throw new SolicitudInvalidaException("El nombre del alumno es obligatorio (máximo 80 caracteres).");
        }
        if (carrera.isEmpty() || carrera.length() > 80) {
            throw new SolicitudInvalidaException("La carrera es obligatoria (máximo 80 caracteres).");
        }
        if (semestre == null || semestre < 1 || semestre > 12) {
            throw new SolicitudInvalidaException("El semestre debe estar entre 1 y 12.");
        }
        if (promedio == null || promedio.isNaN() || promedio < 0 || promedio > 100) {
            throw new SolicitudInvalidaException("El promedio debe estar entre 0 y 100.");
        }
        return new Alumno(matricula, nombre, carrera, semestre, Math.round(promedio * 100) / 100.0);
    }

    private static String limpiar(String texto) {
        return texto == null ? "" : texto.trim();
    }
}
