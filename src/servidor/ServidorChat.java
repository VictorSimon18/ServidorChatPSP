package servidor;

import com.sun.net.httpserver.*;
import comun.Mensaje;
import comun.TipoMensaje;
import seguridad.ValidadorEntrada;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class ServidorChat {

    private static final int PUERTO_DEFAULT = 12345;

    public static void main(String[] args) throws IOException {
        int puerto = PUERTO_DEFAULT;
        if (args.length > 0) {
            try { puerto = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { System.out.println("Puerto inválido. Usando " + PUERTO_DEFAULT); }
        }

        Process procesoHistorial = lanzarProcesoHistorial();
        if (procesoHistorial != null) {
            GestorMensajes.getInstancia().setPipeHistorial(procesoHistorial.getOutputStream());
            System.out.println("Proceso de historial iniciado.");
        } else {
            System.err.println("No se pudo iniciar el historial. Continuando sin él.");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);
        server.createContext("/login",       new LoginHandler());
        server.createContext("/register",    new RegisterHandler());
        server.createContext("/mensaje",     new MensajeHandler());
        server.createContext("/mensajes",    new PollHandler());
        server.createContext("/desconectar", new DesconectarHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("=== Servidor HTTP iniciado en puerto " + puerto + " ===");
        System.out.println("Esperando conexiones...");
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

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

    // ── Handlers ────────────────────────────────────────────────────────────

    static class LoginHandler implements HttpHandler {
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
                g.registrarCliente(nombre);
                g.broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " se unió al chat.", "Servidor"));
                responder(ex, 200, "OK|Login correcto. Bienvenido " + nombre + "!");
            }
        }
    }

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
                g.registrarCliente(nombre);
                g.broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " se unió al chat.", "Servidor"));
                responder(ex, 200, "OK|Registro exitoso. Bienvenido " + nombre + "!");
            }
        }
    }

    static class MensajeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responder(ex, 405, "ERROR|Método no permitido"); return;
            }
            Map<String, String> p = parsearCuerpo(ex.getRequestBody());
            String nombre   = p.getOrDefault("usuario",   "");
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
                case "/quit" -> g.desconectarCliente(nombre);
                default -> g.enviarA(nombre, new Mensaje(TipoMensaje.ERROR, "Comando desconocido. Usa /help.", "Servidor"));
            }
        }
    }

    static class PollHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                responder(ex, 405, "ERROR|Método no permitido"); return;
            }
            Map<String, String> p = parsearQuery(ex.getRequestURI().getQuery());
            String usuario = p.getOrDefault("usuario", "");
            GestorMensajes g = GestorMensajes.getInstancia();

            if (!g.estaConectado(usuario)) {
                ex.sendResponseHeaders(401, -1); return;
            }
            try {
                Mensaje msg = g.esperarMensaje(usuario, 30_000);
                if (msg != null) {
                    responder(ex, 200, msg.toHttpString());
                } else {
                    ex.sendResponseHeaders(204, -1); // No Content — timeout, el cliente reintenta
                }
            } catch (InterruptedException e) {
                ex.sendResponseHeaders(204, -1);
            }
        }
    }

    static class DesconectarHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> p = parsearCuerpo(ex.getRequestBody());
            GestorMensajes.getInstancia().desconectarCliente(p.getOrDefault("usuario", ""));
            responder(ex, 200, "OK");
        }
    }

    // ── Proceso historial ───────────────────────────────────────────────────

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
