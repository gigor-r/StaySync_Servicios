package com.staysync.servicios.service;

import com.staysync.servicios.dto.request.SolicitudServicioRequest;
import com.staysync.servicios.dto.response.ServicioResponse;
import com.staysync.servicios.dto.response.SolicitudServicioResponse;
import com.staysync.servicios.exception.ServicioNotFoundException;
import com.staysync.servicios.model.Servicio;
import com.staysync.servicios.model.SolicitudServicio;
import com.staysync.servicios.model.SolicitudServicio.EstadoSolicitud;
import com.staysync.servicios.repository.ServicioRepository;
import com.staysync.servicios.repository.SolicitudServicioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ServicioService {

    private final ServicioRepository servicioRepository;
    private final SolicitudServicioRepository solicitudRepository;
    private final RabbitTemplate rabbitTemplate;

    public List<ServicioResponse> listarDisponibles() {
        return servicioRepository.findByDisponibleTrue().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public ServicioResponse obtenerPorId(Long id) {
        return toResponse(findOrThrow(id));
    }

    public List<SolicitudServicioResponse> listarSolicitudesPorReserva(Long reservaId) {
        return solicitudRepository.findByReservaId(reservaId).stream()
                .map(this::toSolicitudResponse).collect(Collectors.toList());
    }

    public List<SolicitudServicioResponse> listarSolicitudesPorUsuario(Long usuarioId) {
        return solicitudRepository.findByUsuarioId(usuarioId).stream()
                .map(this::toSolicitudResponse).collect(Collectors.toList());
    }

    public List<SolicitudServicioResponse> listarTodasSolicitudes() {
        return solicitudRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSolicitudResponse).collect(Collectors.toList());
    }

    @Transactional
    public SolicitudServicioResponse crearSolicitud(SolicitudServicioRequest request) {
        Servicio servicio = findOrThrow(request.getServicioId());

        BigDecimal precioTotal = servicio.getPrecio().multiply(BigDecimal.valueOf(request.getCantidad()));

        SolicitudServicio solicitud = SolicitudServicio.builder()
                .reservaId(request.getReservaId())
                .usuarioId(request.getUsuarioId())
                .servicio(servicio)
                .cantidad(request.getCantidad())
                .fechaServicio(request.getFechaServicio())
                .notas(request.getNotas())
                .precioTotal(precioTotal)
                .estado(EstadoSolicitud.PENDIENTE)
                .build();

        SolicitudServicio guardada = solicitudRepository.save(solicitud);

        publicarEventoServicio(guardada);
        return toSolicitudResponse(guardada);
    }

    @Transactional
    public SolicitudServicioResponse actualizarEstadoSolicitud(Long id, EstadoSolicitud nuevoEstado) {
        SolicitudServicio solicitud = solicitudRepository.findById(id)
                .orElseThrow(() -> new ServicioNotFoundException(id));
        solicitud.setEstado(nuevoEstado);
        return toSolicitudResponse(solicitudRepository.save(solicitud));
    }

    private void publicarEventoServicio(SolicitudServicio solicitud) {
        try {
            Map<String, Object> evento = Map.of(
                    "solicitudId",    solicitud.getId(),
                    "reservaId",      solicitud.getReservaId(),
                    "usuarioId",      solicitud.getUsuarioId(),
                    "servicio",       solicitud.getServicio().getNombre(),
                    "fechaServicio",  solicitud.getFechaServicio().toString()
            );
            rabbitTemplate.convertAndSend("staysync.notificaciones.exchange", "servicio.solicitado", evento);
        } catch (Exception e) {
            log.error("Error publicando evento de servicio: {}", e.getMessage());
        }
    }

    private Servicio findOrThrow(Long id) {
        return servicioRepository.findById(id)
                .orElseThrow(() -> new ServicioNotFoundException(id));
    }

    private ServicioResponse toResponse(Servicio s) {
        return ServicioResponse.builder()
                .id(s.getId())
                .categoriaId(s.getCategoria() != null ? s.getCategoria().getId() : null)
                .categoriaNombre(s.getCategoria() != null ? s.getCategoria().getNombre() : null)
                .nombre(s.getNombre())
                .descripcion(s.getDescripcion())
                .precio(s.getPrecio())
                .disponible(s.getDisponible())
                .requiereReserva(s.getRequiereReserva())
                .createdAt(s.getCreatedAt())
                .build();
    }

    private SolicitudServicioResponse toSolicitudResponse(SolicitudServicio s) {
        return SolicitudServicioResponse.builder()
                .id(s.getId())
                .reservaId(s.getReservaId())
                .usuarioId(s.getUsuarioId())
                .servicioId(s.getServicio().getId())
                .servicioNombre(s.getServicio().getNombre())
                .cantidad(s.getCantidad())
                .fechaServicio(s.getFechaServicio())
                .estado(s.getEstado())
                .notas(s.getNotas())
                .precioTotal(s.getPrecioTotal())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
