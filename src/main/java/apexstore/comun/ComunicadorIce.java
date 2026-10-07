package apexstore.comun;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.Util;

/**
 * Crea los Communicator de todos los nodos con los mismos valores por defecto.
 *
 * Ice.Package.contrato=apexstore es OBLIGATORIO: el contrato Slice usa [["java:package:apexstore"]],
 * y sin esta propiedad el runtime no encuentra las clases de las excepciones de usuario
 * (CompraException, NoEsPrimarioException, ...) y las reporta como UnknownUserException.
 */
public final class ComunicadorIce {

    private ComunicadorIce() { }

    public static InitializationData datosPorDefecto() {
        InitializationData datos = new InitializationData();
        datos.properties = Util.createProperties();
        datos.properties.setProperty("Ice.Package.contrato", "apexstore");
        datos.properties.setProperty("Ice.ThreadPool.Server.Size", "4");
        datos.properties.setProperty("Ice.ThreadPool.Server.SizeMax", "32");
        datos.properties.setProperty("Ice.ThreadPool.Client.Size", "4");
        datos.properties.setProperty("Ice.ThreadPool.Client.SizeMax", "32");
        return datos;
    }

    /** Para los main() de cada nodo: los argumentos (--Ice.Config=..., --Prop=valor) tienen prioridad. */
    public static Communicator crear(String[] args) {
        return Util.initialize(args, datosPorDefecto());
    }
}
