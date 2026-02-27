package servidor;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Hilo que gestiona la conexión TCP persistente de un cliente.
 *
 * <p>Responsabilidades:
 * <ol>
 *   <li>Leer el nombre de usuario que el cliente envía al conectarse.</li>
 *   <li>Verificar que el usuario realizó el login HTTP previamente.</li>
 *   <li>Registrar el {@link PrintWriter} del socket en {@link GestorMensajes}
 *       para que el servidor pueda hacer push de mensajes.</li>
 *   <li>Mantener el hilo bloqueado en lectura para detectar la desconexión
 *       del cliente (EOF o excepción de E/S).</li>
 *   <li>Limpiar recursos y notificar la desconexión al {@link GestorMensajes}.</li>
 * </ol>
 *
 * <p>El canal TCP es principalmente unidireccional (servidor → cliente).
 * El único dato que el cliente envía por TCP es su nombre de usuario
 * como primera línea al conectarse.
 */
public class ManejadorClienteTCP extends Thread {

    private final Socket socket;

    public ManejadorClienteTCP(Socket socket) {
        this.socket = socket;
        setDaemon(true);
        setName("TCP-" + socket.getRemoteSocketAddress());
    }

    @Override
    public void run() {
        String nombreUsuario = null;
        GestorMensajes gestor = GestorMensajes.getInstancia();

        try {
            // Streams de entrada (cliente → servidor) y salida (servidor → cliente)
            BufferedReader entrada = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter salida = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true);

            // Primera línea: el cliente envía su nombre de usuario para identificarse
            nombreUsuario = entrada.readLine();
            if (nombreUsuario == null || nombreUsuario.trim().isEmpty()) {
                System.err.println("[TCP] Conexión sin identificar de " + socket.getRemoteSocketAddress());
                return;
            }
            nombreUsuario = nombreUsuario.trim();
            System.out.println("[TCP] Cliente identificado: " + nombreUsuario);

            // Verificar que el usuario completó el login HTTP antes de conectarse por TCP
            if (!gestor.estaConectado(nombreUsuario)) {
                System.err.println("[TCP] Intento de conexión de usuario no autenticado: " + nombreUsuario);
                socket.close();
                return;
            }

            // Registrar el stream de salida; a partir de aquí el servidor puede hacer push
            gestor.registrarSocketTCP(nombreUsuario, salida);

            // Mantener el hilo bloqueado en lectura para detectar la desconexión.
            // El protocolo TCP es unidireccional (push del servidor), pero readLine()
            // devuelve null cuando el cliente cierra el socket, lo que permite limpiar recursos.
            String lineaEntrada;
            while ((lineaEntrada = entrada.readLine()) != null) {
                // El cliente puede enviar "QUIT" como señal explícita de desconexión
                if ("QUIT".equalsIgnoreCase(lineaEntrada.trim())) break;
            }

        } catch (IOException e) {
            // La conexión se cerró de forma inesperada
            if (nombreUsuario != null) {
                System.out.println("[TCP] Conexión cerrada inesperadamente: " + nombreUsuario);
            }
        } finally {
            // Notificar la desconexión y liberar recursos siempre, incluso ante excepciones
            if (nombreUsuario != null) {
                gestor.desconectarSocketTCP(nombreUsuario);
            }
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("[TCP] Hilo finalizado para: " + nombreUsuario);
        }
    }
}
