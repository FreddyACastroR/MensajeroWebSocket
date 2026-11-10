package com.sd.mensajero.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuración del broker STOMP.
 *
 * <ul>
 *   <li>{@code /app/...}     destinos que atiende el servidor (MensajeroController).</li>
 *   <li>{@code /tema/...}    temas públicos: todos los suscritos reciben el mensaje.</li>
 *   <li>{@code /cola/...}    colas privadas; el cliente se suscribe a {@code /usuario/cola/...}
 *       y Spring entrega sólo a la sesión de ese usuario.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final InterceptorConexion interceptorConexion;
    private TaskScheduler planificadorBroker;

    public WebSocketConfig(InterceptorConexion interceptorConexion) {
        this.interceptorConexion = interceptorConexion;
    }

    // Planificador del propio broker; se usa para los "latidos" (heartbeats) que detectan clientes caídos.
    @Autowired
    public void setPlanificadorBroker(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler planificador) {
        this.planificadorBroker = planificador;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/tema", "/cola")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(planificadorBroker);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/usuario");
        // Garantiza que los mensajes a una misma sesión lleguen en el orden en que se publicaron.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/websocket").setAllowedOriginPatterns("*");
        // Procesa los mensajes de cada cliente en el orden en que los mandó.
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptorConexion);
    }
}
