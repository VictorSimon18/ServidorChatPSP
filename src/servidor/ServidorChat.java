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
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import javax.net.ssl.*;

/**
 * Punto de entrada del servidor. Arquitectura híbrida HTTPS + TCP/TLS:
 *
 * <ul>
 *   <li><b>HTTPS (puerto 12345)</b>: login, registro, envío de mensajes, desconexión.</li>
 *   <li><b>TCP/TLS  (puerto 12346)</b>: canal persistente cifrado para push de mensajes
 *       en tiempo real.</li>
 * </ul>
 *
 * <p>Toda la comunicación está cifrada con TLS usando un certificado autofirmado
 * almacenado en {@code certs/keystore.jks}. Genera los certificados con el script
 * {@code gen_certs.sh} antes de iniciar el servidor por primera vez.
 *
 * <p>Flujo de conexión:
 * <ol>
 *   <li>El cliente hace login/registro por HTTPS.</li>
 *   <li>El servidor responde con {@code OK|puertoTCP|mensaje}.</li>
 *   <li>El cliente abre un {@link SSLSocket} al {@code puertoTCP}.</li>
 *   <li>{@link ManejadorClienteTCP} registra el socket en {@link GestorMensajes}.</li>
 *   <li>Desde ese momento, el servidor hace push cifrado de mensajes por TCP/TLS.</li>
 * </ol>
 */
public class ServidorChat {

    private static final int PUERTO_HTTP_DEFAULT = 12345;
    private static final int PUERTO_TCP_DEFAULT  = 12346;

    /** Ruta al keystore JKS que contiene el certificado y la clave privada del servidor. */
    private static final String KEYSTORE_PATH = "certs/keystore.jks";

    public static void main(String[] args) {
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

        // ── Contexto SSL/TLS: carga keystore y configura TLS ─────────────────
        SSLContext sslContext;
        try {
            sslContext = crearSSLContext();
            System.out.println("=== TLS habilitado (certificado cargado desde " + KEYSTORE_PATH + ") ===");
        } catch (FileNotFoundException e) {
            System.err.println("ERROR: No se encontró el keystore en '" + KEYSTORE_PATH + "'.");
            System.err.println("       Ejecuta gen_certs.sh para generar los certificados.");
            return;
        } catch (Exception e) {
            System.err.println("ERROR al inicializar TLS: " + e.getMessage());
            return;
        }

        // ── Proceso hijo para historial ──────────────────────────────────────
        Process procesoHistorial = lanzarProcesoHistorial();
        if (procesoHistorial != null) {
            GestorMensajes.getInstancia().setPipeHistorial(procesoHistorial.getOutputStream());
            System.out.println("Proceso de historial iniciado.");
        } else {
            System.err.println("No se pudo iniciar el historial. Continuando sin él.");
        }

        // ── Servidor TCP/TLS: acepta conexiones persistentes cifradas ─────────
        HiloAceptadorTCP hiloTCP = new HiloAceptadorTCP(puertoTcp, sslContext.getServerSocketFactory());
        hiloTCP.setDaemon(true);
        hiloTCP.start();

        // ── Servidor HTTPS: gestiona operaciones request-response ─────────────
        try {
            final int finalPuertoTcp = puertoTcp;
            HttpsServer server = HttpsServer.create(new InetSocketAddress(puertoHttp), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                /**
                 * Configura TLS por conexión usando un {@link SSLEngine} real para
                 * obtener los cipher suites y protocolos efectivamente habilitados,
                 * evitando cuelgues en el handshake por parámetros incompatibles.
                 */
                @Override
                public void configure(HttpsParameters params) {
                    SSLContext c = getSSLContext();
                    SSLEngine  engine = c.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());
                    params.setSSLParameters(c.getDefaultSSLParameters());
                }
            });
            server.createContext("/login",       new LoginHandler(finalPuertoTcp));
            server.createContext("/register",    new RegisterHandler());
            server.createContext("/mensaje",     new MensajeHandler());
            server.createContext("/desconectar", new DesconectarHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            System.out.println("=== Servidor HTTPS iniciado en puerto " + puertoHttp + " ===");
            System.out.println("=== Servidor TCP/TLS iniciado en puerto " + puertoTcp  + " ===");
            System.out.println("Esperando conexiones...");
        } catch (IOException e) {
            System.err.println("ERROR al iniciar el servidor HTTPS: " + e.getMessage());
        }
    }

    // ── SSL/TLS ───────────────────────────────────────────────────────────────

    /**
     * Crea y configura el {@link SSLContext} del servidor a partir del keystore JKS.
     *
     * <p>El keystore se carga desde {@value #KEYSTORE_PATH}. La contraseña puede
     * sobreescribirse con la propiedad de sistema {@code ssl.keystore.password}
     * (valor por defecto: {@code changeit}, generado por {@code gen_certs.sh}).
     *
     * @return {@link SSLContext} listo para configurar {@link HttpsServer} y
     *         {@link SSLServerSocket}.
     * @throws FileNotFoundException si el keystore no existe en la ruta esperada.
     * @throws Exception             si el keystore está dañado o la contraseña es incorrecta.
     */
    private static SSLContext crearSSLContext() throws Exception {
        char[] password = System.getProperty("ssl.keystore.password", "changeit").toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(KEYSTORE_PATH)) {
            keyStore.load(fis, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    // ── Hilo aceptador TCP/TLS ────────────────────────────────────────────────

    /**
     * Hilo daemon que escucha en el puerto TCP/TLS y crea un {@link ManejadorClienteTCP}
     * por cada nueva conexión cifrada entrante.
     *
     * <p>El {@link SSLServerSocketFactory} inyectado proviene del mismo {@link SSLContext}
     * que el servidor HTTPS, garantizando una configuración TLS uniforme en ambos canales.
     */
    static class HiloAceptadorTCP extends Thread {

        private final int                    puerto;
        /** Factoría SSL compartida con el servidor HTTPS para crear sockets TLS. */
        private final SSLServerSocketFactory socketFactory;

        HiloAceptadorTCP(int puerto, SSLServerSocketFactory socketFactory) {
            this.puerto        = puerto;
            this.socketFactory = socketFactory;
            setName("AceptadorTCP");
            setDaemon(true);
        }

        @Override
        public void run() {
            try (ServerSocket serverSocket = socketFactory.createServerSocket(puerto)) {
                System.out.println("[TCP/TLS] Escuchando en puerto " + puerto);
                while (!Thread.interrupted()) {
                    Socket socketCliente = serverSocket.accept(); // bloquea hasta nueva conexión TLS
                    ManejadorClienteTCP manejador = new ManejadorClienteTCP(socketCliente);
                    manejador.start();
                }
            } catch (IOException e) {
                System.err.println("[TCP/TLS] Error en servidor TCP: " + e.getMessage());
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
