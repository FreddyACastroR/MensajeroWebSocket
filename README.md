# Mensajero instantáneo con WebSockets

**Materia:** Sistemas Distribuidos
**Equipo:** Freddy Ali Castro Roman · Katia Ximena Návarez Espinoza · Jesús Rodrigo Villegas Arguelles · Ethan Gael Valdez Romero

Mensajero basado en el ejemplo de clase (Spring Boot + STOMP sobre WebSocket). Los clientes web envían
todo al servidor y **el servidor es quien redirige** cada mensaje: a todos o sólo a un usuario.

| Requerimiento | Dónde se cumple |
|---|---|
| Lista de usuarios conectados | Panel izquierdo. Se actualiza cuando alguien entra o sale (`/tema/usuarios`). |
| Mensajes para todos | Elegir **Todos** y escribir en la pestaña *Chat* (`/tema/chat`). |
| Mensaje exclusivo a un usuario | Clic en su nombre (o elegirlo en «Para») y escribir. Sólo él lo recibe (`/usuario/cola/chat`). |
| Entidad de negocio en JSON que se agrega a una tabla | Pestaña *Alumnos*: se llena el formulario, se elige **Todos** o un usuario en «Enviar a», y al llegar el `Alumno` se agrega como fila a la tabla HTML. |

## Cómo ejecutarlo

Requisitos: **JDK 17 o superior**. No hace falta instalar Maven, el proyecto trae el *wrapper*.

```bash
# Windows
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

Luego abrir **http://localhost:8080** en el navegador. Para probarlo solo, abre dos o tres pestañas
y entra con nombres distintos.

También se puede generar el `.jar` y correrlo directamente:

```bash
mvnw.cmd package
java -jar target/mensajero-1.0.0.jar
```

### En red local (varias computadoras)

1. Quien corre el servidor busca su IP con `ipconfig` (por ejemplo `192.168.1.50`).
2. Los demás abren `http://192.168.1.50:8080` en su navegador. La página ya se conecta sola a ese servidor.
3. Si no conecta, hay que permitir Java en el Firewall de Windows (la primera vez Windows pregunta;
   hay que marcar «Redes privadas»).

> El cliente no necesita internet: la librería StompJs viene incluida en `static/js/vendor/`.
> También se puede abrir `index.html` directo desde el disco y escribir la IP del servidor en el campo «Servidor».

## Estructura

```
src/main/java/com/sd/mensajero/
├── MensajeroApplication.java
├── config/
│   ├── WebSocketConfig.java        Broker STOMP: /app, /tema, /cola y prefijo de usuario /usuario
│   ├── InterceptorConexion.java    Valida el nombre al conectarse y bloquea publicar directo en temas
│   └── Destinos.java               Constantes con los destinos
├── controller/
│   └── MensajeroController.java    Recibe /app/chat y /app/alumno y los redirige
├── eventos/
│   └── EventosSesion.java          Avisa a todos cuando alguien entra o sale
├── servicio/
│   └── RegistroUsuarios.java       Usuarios conectados (id de sesión → nombre)
└── model/                          Records que viajan como JSON
    ├── Alumno.java                 La entidad de negocio
    ├── MensajeEntrante.java / MensajeChat.java
    ├── EnvioAlumnoEntrante.java / EnvioAlumno.java
    ├── Aviso.java
    └── UsuarioConectado.java       Principal de la sesión (para mensajes privados)

src/main/resources/static/          Cliente web (lo sirve el mismo servidor)
├── index.html
├── css/estilos.css
└── js/app.js                       Conexión STOMP, chat y tabla de alumnos
```

## Cómo funciona

### 1. Conexión e identificación

El cliente abre `ws://<servidor>:8080/websocket` y manda el frame `CONNECT` con un encabezado `usuario`:

```js
new StompJs.Client({
    webSocketFactory: () => new WebSocket('ws://localhost:8080/websocket'),
    connectHeaders: { usuario: 'Katia' },
});
```

`InterceptorConexion` revisa ese nombre. Si es válido y nadie más lo usa, lo registra y lo asocia a la
sesión como `Principal`. Gracias a eso Spring sabe a qué sesión entregar los mensajes privados. Si el
nombre ya está ocupado, responde un frame `ERROR` y la página muestra el motivo.

### 2. Destinos

| Dirección | Destino | Contenido |
|---|---|---|
| cliente → servidor | `/app/chat` | `{"destinatario": null \| "Katia", "contenido": "Hola"}` |
| cliente → servidor | `/app/alumno` | `{"destinatario": null \| "Katia", "alumno": {...}}` |
| servidor → cliente (una vez al suscribirse) | `/app/usuarios` | `["Ethan", "Freddy", "Katia"]` |
| servidor → todos | `/tema/usuarios` | lista de conectados cada vez que cambia |
| servidor → todos | `/tema/chat` | mensajes públicos y avisos de sistema |
| servidor → todos | `/tema/alumnos` | alumnos enviados a todos |
| servidor → un usuario | `/usuario/cola/chat` | mensajes privados |
| servidor → un usuario | `/usuario/cola/alumnos` | alumnos enviados sólo a ese usuario |
| servidor → un usuario | `/usuario/cola/errores` | errores de validación |

`destinatario: null` significa **todos**. Para un envío privado, el servidor usa
`convertAndSendToUser(destinatario, ...)` y además le manda una copia al remitente para que también lo vea.
El remitente siempre lo pone el servidor a partir de la sesión, así que nadie puede hacerse pasar por otro.

### 3. La entidad `Alumno`

```java
public record Alumno(String matricula, String nombre, String carrera, Integer semestre, Double promedio) { }
```

JSON que recibe cada cliente (en `/tema/alumnos` o `/usuario/cola/alumnos`):

```json
{
  "remitente": "Freddy",
  "destinatario": "Katia",
  "alumno": {
    "matricula": "00000123456",
    "nombre": "Ana Sofía López",
    "carrera": "Ingeniería en Software",
    "semestre": 7,
    "promedio": 93.5
  },
  "fecha": "2026-11-10T22:25:41.120Z"
}
```

Al llegar, `agregarAlumno()` en `app.js` crea una fila `<tr>` y la agrega a la tabla. En la misma pestaña
se ve el JSON que se va a enviar y el último JSON recibido. El servidor valida los datos (campos
obligatorios, semestre de 1 a 12, promedio de 0 a 100). Si algo está mal, sólo el remitente recibe el aviso.

## Pruebas

```bash
mvnw.cmd test
```

`MensajeroIntegrationTests` levanta el servidor y conecta clientes STOMP reales. Revisa que:
la lista de conectados se actualice, se rechacen nombres repetidos, los mensajes públicos lleguen a todos,
los privados lleguen sólo al destinatario, los alumnos se reenvíen a todos o a uno, se avise de datos
inválidos y nadie pueda publicar directo en un tema saltándose al servidor.
