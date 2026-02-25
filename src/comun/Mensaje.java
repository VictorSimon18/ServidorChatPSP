package comun;

import java.io.Serializable;
import java.net.URLDecoder;
import java.net.URLEncoder;
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

    private Mensaje(TipoMensaje tipo, String contenido, String remitente, String destinatario, String timestamp) {
        this.tipo = tipo;
        this.contenido = contenido;
        this.remitente = remitente;
        this.destinatario = destinatario;
        this.timestamp = timestamp;
    }

    public TipoMensaje getTipo()        { return tipo; }
    public String getContenido()        { return contenido; }
    public String getRemitente()        { return remitente; }
    public String getDestinatario()     { return destinatario; }
    public String getTimestamp()        { return timestamp; }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + remitente + ": " + contenido;
    }

    // ── Serialización HTTP ──────────────────────────────────────────────────

    public String toHttpString() {
        return tipo.name()
             + "|" + enc(remitente)
             + "|" + enc(destinatario != null ? destinatario : "")
             + "|" + enc(contenido   != null ? contenido   : "")
             + "|" + enc(timestamp);
    }

    public static Mensaje fromHttpString(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", 5);
        if (p.length < 5) return null;
        try {
            TipoMensaje tipo     = TipoMensaje.valueOf(p[0]);
            String remitente    = dec(p[1]);
            String destinatario = dec(p[2]);
            String contenido    = dec(p[3]);
            String timestamp    = dec(p[4]);
            return new Mensaje(tipo, contenido, remitente,
                               destinatario.isEmpty() ? null : destinatario,
                               timestamp);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s != null ? s : "", "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
