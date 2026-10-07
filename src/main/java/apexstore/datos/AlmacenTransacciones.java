package apexstore.datos;

import apexstore.comun.Bitacora;
import apexstore.contrato.EntradaLog;
import apexstore.contrato.EstadoOrden;
import apexstore.contrato.NoEsPrimarioException;
import apexstore.contrato.ResultadoPersistencia;
import apexstore.contrato.Transaccion;
import apexstore.contrato.TransaccionNoEncontrada;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulación del motor transaccional (PostgreSQL) del Nodo 5.
 *
 * Reglas que imitan las propiedades ACID exigidas por RAS-03:
 *  - Atomicidad/aislamiento: cada escritura se ejecuta dentro de un bloque synchronized.
 *  - Consistencia: una orden solo puede pasar de Pendiente a un estado final, nunca al revés,
 *    y un estado final es inmutable (evita doble cobro o cambios contradictorios).
 *  - Durabilidad (simulada): cada cambio se anexa a un log con número de secuencia,
 *    que es lo que consume la réplica.
 * Para producción, esta clase se reemplaza por un repositorio JDBC sobre PostgreSQL.
 */
public class AlmacenTransacciones {

    private final String nombre;
    private final Map<String, Transaccion> tabla = new HashMap<>();
    private final List<EntradaLog> log = new ArrayList<>();
    private long secuencia = 0;
    private boolean primaria;

    public AlmacenTransacciones(String nombre, boolean primaria) {
        this.nombre = nombre;
        this.primaria = primaria;
    }

    public synchronized boolean esPrimaria() {
        return primaria;
    }

    /** Failover: la réplica asume el rol de primaria. */
    public synchronized void promover() {
        if (!primaria) {
            primaria = true;
            Bitacora.info(nombre, "FAILOVER: la réplica fue promovida a primaria (última secuencia " + secuencia + ")");
        }
    }

    public synchronized ResultadoPersistencia persistir(Transaccion tx) throws NoEsPrimarioException {
        if (!primaria) {
            throw new NoEsPrimarioException("El nodo " + nombre + " está en espera y no acepta escrituras");
        }
        Transaccion actual = tabla.get(tx.idOrden);
        ResultadoPersistencia r = new ResultadoPersistencia();
        boolean nuevo = actual == null;
        boolean transicionValida = actual != null
                && actual.estado == EstadoOrden.Pendiente
                && tx.estado != EstadoOrden.Pendiente;
        if (nuevo || transicionValida) {
            Transaccion guardada = tx.clone();
            if (!nuevo) {
                guardada.creadaMs = actual.creadaMs;
            }
            anexar(guardada);
            r.aplicada = true;
            r.actual = guardada.clone();
        } else {
            r.aplicada = false;
            r.actual = actual.clone();
        }
        return r;
    }

    public synchronized Transaccion consultar(String idOrden) throws TransaccionNoEncontrada {
        Transaccion t = tabla.get(idOrden);
        if (t == null) {
            throw new TransaccionNoEncontrada(idOrden);
        }
        return t.clone();
    }

    public synchronized long ultimaSecuencia() {
        return secuencia;
    }

    public synchronized EntradaLog[] obtenerDesde(long desde, int maximo) {
        List<EntradaLog> salida = new ArrayList<>();
        for (EntradaLog e : log) {
            if (e.secuencia > desde) {
                salida.add(new EntradaLog(e.secuencia, e.transaccion.clone()));
                if (salida.size() >= maximo) {
                    break;
                }
            }
        }
        return salida.toArray(new EntradaLog[0]);
    }

    /** Aplica en la réplica las entradas leídas de la primaria. */
    public synchronized void aplicarReplicado(EntradaLog[] entradas) {
        for (EntradaLog e : entradas) {
            if (e.secuencia > secuencia) {
                tabla.put(e.transaccion.idOrden, e.transaccion.clone());
                log.add(new EntradaLog(e.secuencia, e.transaccion.clone()));
                secuencia = e.secuencia;
            }
        }
    }

    private void anexar(Transaccion tx) {
        secuencia++;
        tx.actualizadaMs = Math.max(tx.actualizadaMs, System.currentTimeMillis());
        tabla.put(tx.idOrden, tx);
        log.add(new EntradaLog(secuencia, tx.clone()));
    }

    public synchronized int totalTransacciones() {
        return tabla.size();
    }

    public String nombre() {
        return nombre;
    }
}
