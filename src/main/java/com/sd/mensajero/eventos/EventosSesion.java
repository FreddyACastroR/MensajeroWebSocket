package com.sd.mensajero.eventos;

import com.sd.mensajero.model.MensajeChat;
import com.sd.mensajero.servicio.RegistroUsuarios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

import static com.sd.mensajero.config.Destinos.TEMA_CHAT;
import static com.sd.mensajero.config.Destinos.TEMA_USUARIOS;

/**
 * Avisa a todos cuando alguien entra o sale: publica la nueva lista de conectados
 * y un mensaje de sistema en el chat.
 */
@Component
public class EventosSesion {

    private static final Logger log = LoggerFactory.getLogger(EventosSesion.class);

    private final SimpMessagingTemplate broker;
    private final RegistroUsuarios registro;

    public EventosSesion(SimpMessagingTemplate broker, RegistroUsuarios registro) {
        this.broker = broker;
        this.registro = registro;
    }

    @EventListener
    public void alConectarse(SessionConnectedEvent evento) {
        Principal usuario = evento.getUser();
        if (usuario == null) {
            return;
        }
        log.info("[conexión] {} entró. Conectados: {}", usuario.getName(), registro.nombres());
        publicarCambio(usuario.getName() + " se unió al chat.");
    }

    @EventListener
    public void alDesconectarse(SessionDisconnectEvent evento) {
        // Spring puede avisar dos veces de la misma desconexión; eliminar() sólo devuelve el nombre la primera.
        registro.eliminar(evento.getSessionId()).ifPresent(nombre -> {
            log.info("[conexión] {} salió. Conectados: {}", nombre, registro.nombres());
            publicarCambio(nombre + " salió del chat.");
        });
    }

    private void publicarCambio(String aviso) {
        broker.convertAndSend(TEMA_CHAT, MensajeChat.sistema(aviso));
        broker.convertAndSend(TEMA_USUARIOS, registro.nombres());
    }
}
