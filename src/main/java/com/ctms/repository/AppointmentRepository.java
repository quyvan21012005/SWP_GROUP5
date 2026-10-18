package com.ctms.repository;

import com.ctms.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByDriverIdOrderByAppointmentTimeDesc(Long driverId);
    Optional<Appointment> findByBookingNumber(String bookingNumber);
    Optional<Appointment> findByContainerNumber(String containerNumber);
    List<Appointment> findAllByOrderByAppointmentTimeDesc();
    List<Appointment> findByStatus(Appointment.AppointmentStatus status);
}
