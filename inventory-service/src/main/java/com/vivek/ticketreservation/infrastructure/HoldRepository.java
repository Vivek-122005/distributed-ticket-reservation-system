package com.vivek.ticketreservation.infrastructure;

import com.vivek.ticketreservation.domain.Hold;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> { }
