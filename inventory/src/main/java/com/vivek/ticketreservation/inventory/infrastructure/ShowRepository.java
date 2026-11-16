package com.vivek.ticketreservation.inventory.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<ShowRow, UUID> { }
