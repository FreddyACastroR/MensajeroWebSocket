package com.sd.mensajero.config;

import com.sd.mensajero.model.UsuarioConectado;
import com.sd.mensajero.servicio.RegistroUsuarios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Revisa cada frame STOMP que llega de un cliente antes de procesarlo.
 *
 * <ul>
 *   <li>CONNECT: lee el encabezado {@code usuario}, valida que el nombre sea válido y no esté
 *       ocupado, lo registra y lo asocia a la sesión. Si no, le responde un frame ERROR con el
 *       motivo (el cliente lo lee en {@code frame.headers.message}) y se cierra la conexión.</li>
 *   <li>SEND: sólo se permite enviar a {@code /app/...}; así nadie puede publicar directo en un
 *       tema y saltarse al servidor (por ejemplo, para hacerse pasar por otro usuario).</li>
 * </ul>
 *
 * Nota: el rechazo se manda a mano por {@code clientOutboundChannel} en lugar de lanzar una
 * excepción porque, con {@code setPreserveReceiveOrder(true)}, Spring sólo registra la excepción
 * en el log y el cliente nunca recibe el ERROR (se quedaría esperando).
 */
@Component
public class InterceptorConexion implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InterceptorConexion.class);

    public static final String ENCABEZADO_USUARIO = "usuario";
    private static final Pattern NOMBRE_VALIDO = Pattern.compile("^[\\p{L}\\p{N} ._-]{1,20}$");

    private final RegistroUsuarios registro;
    private final MessageChannel canalHaciaClientes;

    public InterceptorConexion(RegistroUsuarios registro,
                               @Lazy @Qualifier("clientOutboundChannel") MessageChannel canalHaciaClientes) {
        this.registro = registro;
        this.canalHaciaClientes = canalHaciaClientes;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (accessor.getCommand() == StompCommand.CONNECT) {
            String nombre = normalizar(accessor.getFirstNativeHeader(ENCABEZADO_USUARIO));
            if (!NOMBRE_VALIDO.matcher(nombre).matches()) {
                return rechazarConexion(accessor.getSessionId(),
                        "Nombre inválido. Usa de 1 a 20 letras, números, espacios, punto, guion o guion bajo.");
            }
            if (!registro.registrar(accessor.getSessionId(), nombre)) {
                return rechazarConexion(accessor.getSessionId(),
                        "El nombre \"" + nombre + "\" ya está en uso. Elige otro.");
            }
            accessor.setUser(new UsuarioConectado(nombre));
        }

        if (accessor.getCommand() == StompCommand.SEND) {
            String destino = accessor.getDestination();
            if (destino == null || !destino.startsWith("/app/")) {
                log.warn("[rechazado] {} intentó publicar directo en {}", nombreDe(accessor), destino);
                return null; // se descarta el mensaje
            }
        }
        return message;
    }

    /** Responde un frame ERROR (Spring cierra la conexión al enviarlo) y descarta el CONNECT. */
    private Message<?> rechazarConexion(String sesionId, String motivo) {
        log.warn("[conexión rechazada] {}", motivo);
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(motivo);
        error.setSessionId(sesionId);
        canalHaciaClientes.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
        return null;
    }

    private static String nombreDe(StompHeaderAccessor accessor) {
        return accessor.getUser() != null ? accessor.getUser().getName() : "(sin nombre)";
    }

    private static String normalizar(String nombre) {
        return nombre == null ? "" : nombre.trim().replaceAll("\\s+", " ");
    }
}
