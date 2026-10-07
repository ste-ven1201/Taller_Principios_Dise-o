package apexstore.datos;

import apexstore.contrato.NoEsPrimarioException;
import apexstore.contrato.Persistencia;
import apexstore.contrato.ResultadoPersistencia;
import apexstore.contrato.Transaccion;
import apexstore.contrato.TransaccionNoEncontrada;
import com.zeroc.Ice.Current;

/** Lollipop persistirTransaccionPostgres. */
public class PersistenciaServant implements Persistencia {

    private final AlmacenTransacciones almacen;

    public PersistenciaServant(AlmacenTransacciones almacen) {
        this.almacen = almacen;
    }

    @Override
    public ResultadoPersistencia persistirTransaccionPostgres(Transaccion transaccion, Current current)
            throws NoEsPrimarioException {
        return almacen.persistir(transaccion);
    }

    @Override
    public Transaccion consultarTransaccion(String idOrden, Current current) throws TransaccionNoEncontrada {
        return almacen.consultar(idOrden);
    }
}
