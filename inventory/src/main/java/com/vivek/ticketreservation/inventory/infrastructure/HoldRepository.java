package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.domain.Hold;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> { }
