package apexstore.datos;

import apexstore.contrato.EntradaLog;
import apexstore.contrato.Replicacion;
import com.zeroc.Ice.Current;

/** Lollipop replicacion: la primaria expone su log; la réplica lo consume. */
public class ReplicacionServant implements Replicacion {

    private final AlmacenTransacciones almacen;

    public ReplicacionServant(AlmacenTransacciones almacen) {
        this.almacen = almacen;
    }

    @Override
    public long ultimaSecuencia(Current current) {
        return almacen.ultimaSecuencia();
    }

    @Override
    public EntradaLog[] obtenerDesde(long secuencia, int maximo, Current current) {
        return almacen.obtenerDesde(secuencia, maximo);
    }
}
