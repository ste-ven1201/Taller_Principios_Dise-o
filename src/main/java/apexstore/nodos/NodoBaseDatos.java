package apexstore.nodos;

import apexstore.comun.ComunicadorIce;
import apexstore.contrato.ReplicacionPrx;
import apexstore.datos.AlmacenTransacciones;
import apexstore.datos.PersistenciaServant;
import apexstore.datos.ReplicacionServant;
import apexstore.datos.SeguidorReplicacion;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

/**
 * Nodo 5. Se levanta como primaria o como réplica según Datos.Rol.
 * Propiedades: Datos.Rol (primaria|replica), AdaptadorDatos.Endpoints, Datos.Primaria.Endpoint (solo la réplica).
 */
public final class NodoBaseDatos {

    private NodoBaseDatos() { }

    public static void main(String[] args) {
        try (Communicator c = ComunicadorIce.crear(args)) {
            iniciar(c);
            c.waitForShutdown();
        }
    }

    public static AlmacenTransacciones iniciar(Communicator c) {
        boolean primaria = "primaria".equalsIgnoreCase(c.getProperties().getPropertyWithDefault("Datos.Rol", "primaria"));
        AlmacenTransacciones almacen = new AlmacenTransacciones(primaria ? "BD-primaria" : "BD-replica", primaria);
        ObjectAdapter adaptador = c.createObjectAdapter("AdaptadorDatos");
        adaptador.add(new PersistenciaServant(almacen), Util.stringToIdentity("persistencia"));
        adaptador.add(new ReplicacionServant(almacen), Util.stringToIdentity("replicacion"));
        adaptador.activate();
        if (!primaria) {
            String endpointPrimaria = c.getProperties().getProperty("Datos.Primaria.Endpoint");
            ReplicacionPrx primariaPrx = ReplicacionPrx.uncheckedCast(
                    c.stringToProxy("replicacion:" + endpointPrimaria));
            SeguidorReplicacion seguidor = new SeguidorReplicacion(almacen, primariaPrx, 3);
            seguidor.iniciar(400);
        }
        apexstore.comun.Bitacora.info(almacen.nombre(), "listo (" + (primaria ? "primaria" : "réplica en espera") + ")");
        return almacen;
    }
}
