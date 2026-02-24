package comun;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Mensaje implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TipoMensaje tipo;
    private final String contenido;
    private final String remitente;
    private final String destinatario;
    private final String timestamp;

    public Mensaje(TipoMensaje tipo, String contenido, String remitente) {
        this(tipo, contenido, remitente, null);
    }

    public Mensaje(TipoMensaje tipo, String contenido, String remitente, String destinatario) {
        this.tipo = tipo;
        this.contenido = contenido;
        this.remitente = remitente;
        this.destinatario = destinatario;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
    }

    public TipoMensaje getTipo() { return tipo; }
    public String getContenido() { return contenido; }
    public String getRemitente() { return remitente; }
    public String getDestinatario() { return destinatario; }
    public String getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + remitente + ": " + contenido;
    }
}
