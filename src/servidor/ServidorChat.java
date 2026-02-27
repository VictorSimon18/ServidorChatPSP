package servidor;

import com.sun.net.httpserver.*;
import comun.Mensaje;
import comun.TipoMensaje;
import seguridad.ValidadorEntrada;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Punto de entrada del servidor. Arquitectura híbrida HTTP + TCP:
 *
 * <ul>
 *   <li><b>HTTP (puerto 12345)</b>: login, registro, envío de mensajes, desconexión.</li>
 *   <li><b>TCP  (puerto 12346)</b>: canal persistente para push de mensajes en tiempo real.</li>
 * </ul>
 *
 * <p>Flujo de conexión:
 * <ol>
 *   <li>El cliente hace login/registro por HTTP.</li>
 *   <li>El servidor responde con {@code OK|puertoTCP|mensaje}.</li>
 *   <li>El cliente abre un socket TCP a {@code puertoTCP} y envía su nombre de usuario.</li>
 *   <li>{@link ManejadorClienteTCP} registra el socket en {@link GestorMensajes}.</li>
 *   <li>Desde ese momento, el servidor hace push de mensajes por TCP.</li>
 * </ol>
 */
public class ServidorChat {

    private static final int PUERTO_HTTP_DEFAULT = 12345;
    private static final int PUERTO_TCP_DEFAULT  = 12346;

    public static void main(String[] args) throws IOException {
        int puertoHttp = PUERTO_HTTP_DEFAULT;
        int puertoTcp  = PUERTO_TCP_DEFAULT;

        if (args.length > 0) {
            try { puertoHttp = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { System.out.println("Puerto HTTP inválido. Usando " + PUERTO_HTTP_DEFAULT); }
        }
        if (args.length > 1) {
            try { puertoTcp = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { System.out.println("Puerto TCP inválido. Usando " + PUERTO_TCP_DEFAULT); }
        }

        // ── Proceso hijo para historial ──────────────────────────────────────
        Process procesoHistorial = lanzarProcesoHistorial();
        if (procesoHistorial != null) {
            GestorMensajes.getInstancia().setPipeHistorial(procesoHistorial.getOutputStream());
            System.out.println("Proceso de historial iniciado.");
        } else {
            System.err.println("No se pudo iniciar el historial. Continuando sin él.");
        }

        // ── Servidor TCP: acepta conexiones persistentes de clientes ─────────
        HiloAceptadorTCP hiloTCP = new HiloAceptadorTCP(puertoTcp);
        hiloTCP.setDaemon(true);
        hiloTCP.start();

        // ── Servidor HTTP: gestiona operaciones request-response ─────────────
        final int finalPuertoTcp = puertoTcp;
        HttpServer server = HttpServer.create(new InetSocketAddress(puertoHttp), 0);
        server.createContext("/login",       new LoginHandler(finalPuertoTcp));
        server.createContext("/register",    new RegisterHandler());
        server.createContext("/mensaje",     new MensajeHandler());
        server.createContext("/desconectar", new DesconectarHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("=== Servidor HTTP iniciado en puerto " + puertoHttp + " ===");
        System.out.println("=== Servidor TCP  iniciado en puerto " + puertoTcp  + " ===");
        System.out.println("Esperando conexiones...");
    }

    // ── Hilo aceptador TCP ───────────────────────────────────────────────────

    /**
     * Hilo daemon que escucha en el puerto TCP y crea un {@link ManejadorClienteTCP}
     * por cada nueva conexión entrante.
     */
    static class HiloAceptadorTCP extends Thread {

        private final int puerto;

        HiloAceptadorTCP(int puerto) {
            this.puerto = puerto;
            setName("AceptadorTCP");
            setDaemon(true);
        }

        @Override
        public void run() {
            try (ServerSocket serverSocket = new ServerSocket(puerto)) {
                System.out.println("[TCP] Escuchando en puerto " + puerto);
                while (!Thread.interrupted()) {
                    Socket socketCliente = serverSocket.accept(); // bloquea hasta nueva conexión
                    ManejadorClienteTCP manejador = new ManejadorClienteTCP(socketCliente);
                    manejador.start();
                }
            } catch (IOException e) {
                System.err.println("[TCP] Error en servidor TCP: " + e.getMessage());
            }
        }
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    static Map<String, String> parsearCuerpo(InputStream is) throws IOException {
        return parsearPares(new String(is.readAllBytes(), StandardCharsets.UTF_8));
    }

    static Map<String, String> parsearQuery(String query) {
        if (query == null || query.isEmpty()) return new HashMap<>();
        return parsearPares(query);
    }

    private static Map<String, String> parsearPares(String s) {
        Map<String, String> map = new HashMap<>();
        for (String par : s.split("&")) {
            String[] kv = par.split("=", 2);
            if (kv.length == 2) {
                try {
                    map.put(URLDecoder.decode(kv[0], "UTF-8"),
                            URLDecoder.decode(kv[1], "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ── Handlers HTTP ────────────────────────────────────────────────────────

    /**
     * POST /login — autentica al usuario y devuelve el puerto TCP.
     * Formato de respuesta exitosa: {@code OK|puertoTCP|mensaje}.
     */
    static class LoginHandler implements HttpHandler {

        private final int puertoTcp;

        LoginHandler(int puertoTcp) { this.puertoTcp = puertoTcp; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responder(ex, 405, "ERROR|Método no permitido"); return;
            }
            Map<String, String> p = parsearCuerpo(ex.getRequestBody());
            String nombre   = p.getOrDefault("usuario",  "");
            String password = p.getOrDefault("password", "");
            GestorMensajes g = GestorMensajes.getInstancia();

            if (g.estaConectado(nombre)) {
                responder(ex, 400, "ERROR|El usuario ya está conectado."); return;
            }
            String error = g.autenticarUsuario(nombre, password);
            if (error != null) {
                responder(ex, 401, "ERROR|" + error);
            } else {
                // Marcar como autenticado; el broadcast de "se unió" se hace al registrar el TCP
                g.registrarCliente(nombre);
                // Incluir el puerto TCP en la respuesta para que el cliente se conecte
                responder(ex, 200, "OK|" + puertoTcp + "|Login correcto. Bienvenido " + nombre + "!");
            }
        }
    }

    /**
     * POST /register — crea la cuenta de usuario.
     * El registro no abre sesión; el usuario debe hacer login a continuación.
     * Formato de respuesta exitosa: {@code OK|mensaje}.
     */
    static class RegisterHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responder(ex, 405, "ERROR|Método no permitido"); return;
            }
            Map<String, String> p = parsearCuerpo(ex.getRequestBody());
            String nombre   = p.getOrDefault("usuario",  "");
            String password = p.getOrDefault("password", "");
            GestorMensajes g = GestorMensajes.getInstancia();

            if (g.estaConectado(nombre)) {
                responder(ex, 400, "ERROR|El usuario ya está conectado."); return;
            }
            String error = g.registrarUsuario(nombre, password);
            if (error != null) {
                responder(ex, 400, "ERROR|" + error);
            } else {
                // El registro no abre sesión; el cliente debe hacer login después
                responder(ex, 200, "OK|Registro exitoso. Por favor inicia sesión.");
            }
        }
    }

    /**
     * POST /mensaje — el usuario autenticado envía un mensaje o ejecuta un comando.
     */
    static class MensajeHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responder(ex, 405, "ERROR|Método no permitido"); return;
            }
            Map<String, String> p = parsearCuerpo(ex.getRequestBody());
            String nombre    = p.getOrDefault("usuario",   "");
            String contenido = p.getOrDefault("contenido", "");
            GestorMensajes g = GestorMensajes.getInstancia();

            if (!g.estaConectado(nombre)) {
                responder(ex, 401, "ERROR|No autenticado"); return;
            }
            contenido = ValidadorEntrada.sanitizarMensaje(contenido);
            if (contenido.isEmpty()) {
                responder(ex, 400, "ERROR|Mensaje vacío"); return;
            }
            if (contenido.startsWith("/")) {
                procesarComando(nombre, contenido, g);
            } else {
                g.broadcast(new Mensaje(TipoMensaje.MESSAGE, contenido, nombre));
            }
            responder(ex, 200, "OK");
        }

        private void procesarComando(String nombre, String contenido, GestorMensajes g) {
            String[] partes = contenido.split("\\s+", 3);
            switch (partes[0].toLowerCase()) {
                case "/users" -> g.enviarA(nombre, new Mensaje(TipoMensaje.MESSAGE,
                        "Usuarios conectados: " + String.join(", ", g.getUsuariosConectados()), "Servidor"));
                case "/private" -> {
                    if (partes.length < 3) {
                        g.enviarA(nombre, new Mensaje(TipoMensaje.ERROR, "Uso: /private <usuario> <mensaje>", "Servidor"));
                        return;
                    }
                    Mensaje priv = new Mensaje(TipoMensaje.PRIVATE, partes[2], nombre, partes[1]);
                    if (!g.enviarPrivado(priv))
                        g.enviarA(nombre, new Mensaje(TipoMensaje.ERROR, "Usuario '" + partes[1] + "' no encontrado.", "Servidor"));
                }
                case "/help" -> g.enviarA(nombre, new Mensaje(TipoMensaje.HELP,
                        "Comandos: /users  /private <usuario> <mensaje>  /help  /quit", "Servidor"));
                case "/quit" -> g.desconectarCliente(nombre); // cierra TCP y difunde "ha salido"
                default      -> g.enviarA(nombre, new Mensaje(TipoMensaje.ERROR, "Comando desconocido. Usa /help.", "Servidor"));
            }
        }
    }

    /**
     * POST /desconectar — el cliente cierra sesión de forma controlada.
     */
    static class DesconectarHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> p = parsearCuerpo(ex.getRequestBody());
            GestorMensajes.getInstancia().desconectarCliente(p.getOrDefault("usuario", ""));
            responder(ex, 200, "OK");
        }
    }

    // ── Proceso historial ────────────────────────────────────────────────────

    /**
     * Lanza el proceso hijo {@link ProcesoHistorial} en una JVM separada.
     * La comunicación se realiza mediante un pipe (stdout del padre → stdin del hijo).
     */
    private static Process lanzarProcesoHistorial() {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp",
                    System.getProperty("java.class.path"), "servidor.ProcesoHistorial");
            pb.redirectErrorStream(false);
            pb.directory(new File(System.getProperty("user.dir")));
            return pb.start();
        } catch (IOException e) {
            System.err.println("Error lanzando historial: " + e.getMessage());
            return null;
        }
    }
}
